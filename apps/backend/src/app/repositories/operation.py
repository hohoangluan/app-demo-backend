"""Operation repository: insert/read, and delivery claim/lease primitives.

Scope: this repository implements the primitives needed by
``docs/p1-database-plan.md`` transaction design sections "A. Accept request
và idempotency" (task ``P1-DB-07``) and "B. Delivery claim/lease" (task
``P1-DB-08``). It does not compute the idempotency fingerprint, does not pick
a timeout or retry-backoff duration, does not classify transient-vs-permanent
delivery failure, does not decide HTTP status codes or Public error codes,
never calls FCM/network, and does not commit the session — all of that is
the caller's (service layer's, or the future P2 worker's) responsibility.
Callback-claim and report/timeout transitions are out of scope (future tasks
``P1-DB-09``/``10``).
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import TYPE_CHECKING, Any, cast

from sqlalchemy import and_, func, or_, select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert

from app.models.enums import CallbackState, DeliveryState, RequestState
from app.models.operation import JsonObject, Operation
from app.repositories.device import DeviceRepository

if TYPE_CHECKING:
    from datetime import datetime
    from uuid import UUID

    from sqlalchemy.engine import CursorResult
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.actions import Action
    from app.actions import Operation as OperationName


class OperationInsertStatus(StrEnum):
    """Outcome of :meth:`OperationRepository.insert_or_get`.

    ``INSERTED``: no prior row existed for ``request_id``; the candidate row
    is now persisted (the caller must still ``commit()``).

    ``IDEMPOTENT_MATCH``: a row already existed with the same ``request_id``,
    the same ``client_id``, and the same ``request_fingerprint`` as the
    candidate. The existing row is returned unchanged (``created_at`` and
    every other field are untouched).

    ``FINGERPRINT_CONFLICT``: a row already existed with the same
    ``request_id`` but a different ``client_id`` and/or
    ``request_fingerprint``. The existing row is returned so the caller has
    it available, but no HTTP status or Public error code is decided here.
    """

    INSERTED = "inserted"
    IDEMPOTENT_MATCH = "idempotent_match"
    FINGERPRINT_CONFLICT = "fingerprint_conflict"


@dataclass(frozen=True, slots=True)
class OperationInsertOutcome:
    """Typed result of an insert-or-read attempt: status plus the row."""

    status: OperationInsertStatus
    operation: Operation


@dataclass(frozen=True, slots=True)
class NewOperation:
    """Fields the caller must supply to attempt inserting an operation row.

    The repository never computes any of these: the fingerprint, timeout
    (``expires_at``), and ``callback_state`` policy are all decided by the
    service layer before calling :meth:`OperationRepository.insert_or_get`.
    """

    request_id: UUID
    client_id: str
    user_id: str
    operation: OperationName
    action: Action
    params: JsonObject
    request_fingerprint: str
    callback_state: CallbackState
    expires_at: datetime


class OperationRepository:
    """Query/locking/persistence primitives for the ``operations`` table."""

    def __init__(self, session: AsyncSession) -> None:
        """Bind the repository to the caller-owned session/transaction."""
        self._session = session

    async def insert_or_get(self, candidate: NewOperation) -> OperationInsertOutcome:
        """Attempt ``INSERT ... ON CONFLICT (request_id) DO NOTHING RETURNING``.

        On success, the candidate row is persisted with
        ``request_state=processing``, ``delivery_state=received``, and
        ``next_delivery_at`` set equal to ``created_at`` (both bound to the
        same ``now()`` expression evaluated once for this statement), per
        ``docs/p1-database-plan.md`` transaction design "A". On conflict, the
        existing row is read back (in the same transaction) and classified
        as an idempotent match or a fingerprint conflict; neither branch
        mutates the existing row. The caller still owns ``commit()``.
        """
        now_expr = func.now()
        insert_statement = (
            pg_insert(Operation)
            .values(
                request_id=candidate.request_id,
                client_id=candidate.client_id,
                user_id=candidate.user_id,
                operation=candidate.operation,
                action=candidate.action,
                params=candidate.params,
                request_fingerprint=candidate.request_fingerprint,
                request_state=RequestState.PROCESSING,
                delivery_state=DeliveryState.RECEIVED,
                next_delivery_at=now_expr,
                callback_state=candidate.callback_state,
                expires_at=candidate.expires_at,
                created_at=now_expr,
                updated_at=now_expr,
            )
            .on_conflict_do_nothing(index_elements=[Operation.request_id])
            .returning(Operation)
        )
        inserted_result = await self._session.execute(insert_statement)
        inserted_row = inserted_result.scalars().first()
        if inserted_row is not None:
            return OperationInsertOutcome(OperationInsertStatus.INSERTED, inserted_row)

        existing_row = (
            await self._session.execute(
                select(Operation).where(Operation.request_id == candidate.request_id)
            )
        ).scalar_one()

        if (
            existing_row.client_id == candidate.client_id
            and existing_row.request_fingerprint == candidate.request_fingerprint
        ):
            return OperationInsertOutcome(OperationInsertStatus.IDEMPOTENT_MATCH, existing_row)
        return OperationInsertOutcome(OperationInsertStatus.FINGERPRINT_CONFLICT, existing_row)

    async def get_by_request_id(self, *, client_id: str, request_id: UUID) -> Operation | None:
        """Return the operation owned by ``client_id``, or ``None``.

        Ownership is filtered in the query's ``WHERE`` clause (not checked
        in Python after fetching), so a request ID that exists but belongs
        to a different client returns ``None`` rather than the row.
        """
        result = await self._session.execute(
            select(Operation).where(
                Operation.request_id == request_id,
                Operation.client_id == client_id,
            )
        )
        return result.scalar_one_or_none()

    async def claim_due_deliveries(
        self, *, now: datetime, lease_until: datetime, limit: int
    ) -> list[Operation]:
        """Claim up to ``limit`` due deliveries and lease them to this worker.

        Implements ``docs/p1-database-plan.md`` transaction design "B.
        Delivery claim/lease" steps 1-3. A row is due when it is still
        ``processing`` and either: ``delivery_state`` is ``received``/``retry``
        with ``next_delivery_at <= now``; or ``delivery_state`` is ``sending``
        with an expired lease (``delivery_locked_until <= now`` — a crashed
        worker's row, recovered here). Candidates are locked with
        ``ORDER BY next_delivery_at NULLS LAST, created_at`` plus
        ``FOR UPDATE SKIP LOCKED`` so concurrent callers never contend on the
        same row and each claims a disjoint batch.

        For each locked candidate this resolves the latest active device for
        the operation's ``user_id`` (:meth:`DeviceRepository.get_latest_active_device`,
        same session/transaction) and, only if one exists, updates the row to
        ``delivery_state='sending'``, sets ``device_id``, increments
        ``delivery_attempts``, and sets ``delivery_locked_until = lease_until``
        (the exact fencing value the caller must present to the ``record_*``
        methods below). ``now``/``lease_until`` are supplied by the caller;
        this primitive never invents a clock reading or a lease duration.

        Judgment call (undocumented in ``docs/p1-database-plan.md``): when a
        locked candidate's user has no active device, that row is left
        untouched -- still ``processing``/due, unlocked -- and simply excluded
        from the returned batch, rather than transitioning it to any failure
        state. The doc does not specify this case; inventing a failure
        transition here would encode delivery-failure classification, which
        this generic primitive must not do. A future claim attempt (once a
        device registers, or per whatever policy P2 decides) can pick the row
        up again. Flag this to the caller/reviewer as a judgment call, not a
        settled contract.

        Returns the claimed :class:`Operation` rows (a subset of up to
        ``limit`` locked candidates; fewer if some had no active device). The
        caller still owns ``commit()``.
        """
        due_predicate = or_(
            and_(
                Operation.delivery_state.in_([DeliveryState.RECEIVED, DeliveryState.RETRY]),
                Operation.next_delivery_at <= now,
            ),
            and_(
                Operation.delivery_state == DeliveryState.SENDING,
                Operation.delivery_locked_until <= now,
            ),
        )
        candidates_result = await self._session.execute(
            select(Operation)
            .where(Operation.request_state == RequestState.PROCESSING, due_predicate)
            .order_by(Operation.next_delivery_at.asc().nulls_last(), Operation.created_at.asc())
            .limit(limit)
            .with_for_update(skip_locked=True)
        )
        candidates = candidates_result.scalars().all()

        device_repository = DeviceRepository(self._session)
        claimed: list[Operation] = []
        for candidate in candidates:
            device = await device_repository.get_latest_active_device(candidate.user_id)
            if device is None:
                continue
            candidate.device_id = device.device_id
            candidate.delivery_state = DeliveryState.SENDING
            candidate.delivery_attempts += 1
            candidate.delivery_locked_until = lease_until
            candidate.updated_at = now
            claimed.append(candidate)

        if claimed:
            await self._session.flush()
        return claimed

    async def record_delivery_sent(self, *, request_id: UUID, lease_until: datetime) -> bool:
        """Fenced: mark a leased delivery as successfully sent to the device.

        Conditional on ``request_id``, ``delivery_state='sending'``, and the
        exact ``delivery_locked_until == lease_until`` fencing value the
        claim returned (``docs/p1-database-plan.md`` section B steps 5-6). If
        the lease was reclaimed by another worker in the meantime, this
        predicate matches zero rows and returns ``False`` without touching
        the new owner's row -- the caller must treat that as "my outcome is
        stale, do nothing further".

        On success (``True``): ``delivery_state`` becomes ``sent`` and both
        ``delivery_locked_until`` and ``next_delivery_at`` are cleared, since
        a sent delivery is no longer due for (re)claim by
        :meth:`claim_due_deliveries`. This method never touches
        ``request_state``/``result``/``error``/``completed_at``: completing
        the *operation* is the device report's job (task ``P1-DB-10``), not
        this delivery-only primitive.
        """
        result = await self._session.execute(
            update(Operation)
            .where(
                Operation.request_id == request_id,
                Operation.delivery_state == DeliveryState.SENDING,
                Operation.delivery_locked_until == lease_until,
            )
            .values(
                delivery_state=DeliveryState.SENT,
                delivery_locked_until=None,
                next_delivery_at=None,
                updated_at=func.now(),
            )
        )
        return cast("CursorResult[Any]", result).rowcount > 0

    async def record_delivery_transient_failure(
        self, *, request_id: UUID, lease_until: datetime, next_delivery_at: datetime
    ) -> bool:
        """Fenced: return a leased delivery to ``retry`` after a transient failure.

        Same fencing predicate as :meth:`record_delivery_sent`; returns
        ``False`` (zero rows affected) for a stale/superseded lease.

        On success: ``delivery_state`` becomes ``retry``, ``next_delivery_at``
        is set to the caller-supplied timestamp (this primitive never
        computes a retry-backoff duration -- that is the future retry
        classifier's job), and ``delivery_locked_until`` is cleared so the row
        becomes claimable again once due.
        """
        result = await self._session.execute(
            update(Operation)
            .where(
                Operation.request_id == request_id,
                Operation.delivery_state == DeliveryState.SENDING,
                Operation.delivery_locked_until == lease_until,
            )
            .values(
                delivery_state=DeliveryState.RETRY,
                next_delivery_at=next_delivery_at,
                delivery_locked_until=None,
                updated_at=func.now(),
            )
        )
        return cast("CursorResult[Any]", result).rowcount > 0

    async def record_delivery_permanent_failure(
        self, *, request_id: UUID, lease_until: datetime, error: JsonObject
    ) -> bool:
        """Fenced: terminally fail a leased delivery and the owning operation.

        Same fencing predicate as :meth:`record_delivery_sent`, plus an
        additional ``request_state='processing'`` guard: this only accepts
        operations that are still processing, so a race against a report or
        timeout that already terminated the operation safely no-ops (zero
        rows affected) instead of corrupting an already-terminal row.

        On success: ``delivery_state`` becomes ``failed``; the *operation*
        also terminally fails (``request_state='failed'``,
        ``completed_at=now()``, ``error`` set to the caller-supplied
        ``{code, message, details}`` payload -- this primitive never invents
        error content, satisfying ``ck_operations_terminal_payload`` since
        ``result`` was already ``NULL`` while ``processing``). Both
        ``delivery_locked_until`` and ``next_delivery_at`` are cleared, since
        a terminally failed delivery is never due again.
        """
        result = await self._session.execute(
            update(Operation)
            .where(
                Operation.request_id == request_id,
                Operation.delivery_state == DeliveryState.SENDING,
                Operation.delivery_locked_until == lease_until,
                Operation.request_state == RequestState.PROCESSING,
            )
            .values(
                delivery_state=DeliveryState.FAILED,
                delivery_locked_until=None,
                next_delivery_at=None,
                request_state=RequestState.FAILED,
                error=error,
                completed_at=func.now(),
                updated_at=func.now(),
            )
        )
        return cast("CursorResult[Any]", result).rowcount > 0
