"""Operation repository: insert/read, delivery, callback and report/timeout primitives.

Scope: this repository implements the primitives needed by
``docs/p1-database-plan.md`` transaction design sections "A. Accept request
và idempotency" (task ``P1-DB-07``), "B. Delivery claim/lease" (task
``P1-DB-08``), "C. Callback claim/lease" (task ``P1-DB-09``), and "D. Device
report và timeout race" (task ``P1-DB-10``). It does not compute the
idempotency fingerprint, does not pick a timeout or retry-backoff duration,
does not classify transient-vs-permanent delivery/callback failure, does not
validate report ownership/schema, does not decide HTTP status codes or
Public error codes, never calls FCM/HTTP/network, and does not commit the
session — all of that is the caller's (service layer's, or the future P2
worker's) responsibility.
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


class ReportExecutionState(StrEnum):
    """Terminal execution outcome reported by the device.

    Deliberately local to this repository rather than importing
    ``app.schemas.device.ExecutionState``: the repository layer must not
    depend on the Pydantic API-schema layer (dependency direction is
    api -> service -> repository). The two enums share the same string
    values by design so a service can convert one to the other trivially.
    """

    SUCCEEDED = "succeeded"
    FAILED = "failed"


class ReportOutcomeStatus(StrEnum):
    """Outcome of :meth:`OperationRepository.record_device_report`.

    ``APPLIED``: the operation was still ``processing`` and is now
    terminal (``succeeded``/``failed``) with the caller-supplied
    result/error, ``report_payload_hash``, and callback scheduling.

    ``IDEMPOTENT_DUPLICATE``: the operation was already terminal and the
    caller-supplied ``report_payload_hash`` matches the hash stored on the
    row (an exact-duplicate report, per ``CONTRACT_DECISIONS.md`` ``D-09``).
    The row is returned unchanged; nothing is re-touched.

    ``CONFLICT``: the operation was already terminal and the
    caller-supplied ``report_payload_hash`` does not match the stored one
    (including the case where the row has no stored hash at all, e.g. it
    reached terminal via :meth:`record_timeout` rather than a report -- a
    late report arriving after a timeout falls into this bucket, per
    ``D-09``: it "does not change the terminal state"). No HTTP status or
    Public error code is decided here; a future service layer maps this.
    """

    APPLIED = "applied"
    IDEMPOTENT_DUPLICATE = "idempotent_duplicate"
    CONFLICT = "conflict"


@dataclass(frozen=True, slots=True)
class ReportOutcome:
    """Typed result of :meth:`OperationRepository.record_device_report`."""

    status: ReportOutcomeStatus
    operation: Operation


@dataclass(frozen=True, slots=True)
class DeviceReport:
    """Fields the caller must supply to record one device-report outcome.

    Mirrors :class:`NewOperation`'s philosophy: the repository never
    validates ownership/action/result schema (that is ``D-04``/``D-07``/
    ``D-08``, the service layer's job) and never decides the
    post-report ``callback_state``/``next_callback_at`` policy -- the caller
    supplies both, exactly like ``expires_at``/``callback_state`` are
    caller-supplied in :meth:`OperationRepository.insert_or_get`.
    """

    request_id: UUID
    execution_state: ReportExecutionState
    result: JsonObject | None
    error: JsonObject | None
    report_payload_hash: str
    callback_state: CallbackState
    next_callback_at: datetime | None


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

    async def claim_due_callbacks(
        self, *, now: datetime, lease_until: datetime, limit: int
    ) -> list[Operation]:
        """Claim up to ``limit`` due callbacks and lease them to this worker.

        Implements ``docs/p1-database-plan.md`` transaction design "C.
        Callback claim/lease" steps 1-2. A row is due when its
        ``request_state`` is terminal (``succeeded``/``failed``/``timed_out``
        -- only a finished operation ever needs a result callback) and
        either: ``callback_state`` is ``pending``/``retry`` with
        ``next_callback_at <= now``; or ``callback_state`` is ``sending``
        with an expired lease (``callback_locked_until <= now`` -- a crashed
        worker's row, recovered here). Candidates are locked with
        ``ORDER BY next_callback_at NULLS LAST, created_at`` plus
        ``FOR UPDATE SKIP LOCKED``, mirroring :meth:`claim_due_deliveries`
        exactly, so concurrent callers never contend on the same row.

        Unlike delivery claiming, no device resolution happens here: a
        callback is an HTTP POST to an external URL, not a device push, so
        every locked candidate is claimed unconditionally -- updated to
        ``callback_state='sending'``, ``callback_attempts += 1``, and
        ``callback_locked_until = lease_until`` (the caller-supplied fencing
        value the ``record_callback_*`` methods below require). ``now``/
        ``lease_until`` are supplied by the caller; this primitive never
        invents a clock reading or a lease duration. No URL/HTTP logic is
        built here (out of scope for P1).

        Returns the claimed :class:`Operation` rows. The caller still owns
        ``commit()``.
        """
        due_predicate = or_(
            and_(
                Operation.callback_state.in_([CallbackState.PENDING, CallbackState.RETRY]),
                Operation.next_callback_at <= now,
            ),
            and_(
                Operation.callback_state == CallbackState.SENDING,
                Operation.callback_locked_until <= now,
            ),
        )
        terminal_states = [RequestState.SUCCEEDED, RequestState.FAILED, RequestState.TIMED_OUT]
        candidates_result = await self._session.execute(
            select(Operation)
            .where(Operation.request_state.in_(terminal_states), due_predicate)
            .order_by(Operation.next_callback_at.asc().nulls_last(), Operation.created_at.asc())
            .limit(limit)
            .with_for_update(skip_locked=True)
        )
        candidates = candidates_result.scalars().all()

        claimed: list[Operation] = []
        for candidate in candidates:
            candidate.callback_state = CallbackState.SENDING
            candidate.callback_attempts += 1
            candidate.callback_locked_until = lease_until
            candidate.updated_at = now
            claimed.append(candidate)

        if claimed:
            await self._session.flush()
        return claimed

    async def record_callback_delivered(self, *, request_id: UUID, lease_until: datetime) -> bool:
        """Fenced: mark a leased callback as successfully delivered.

        Conditional on ``request_id``, ``callback_state='sending'``, and the
        exact ``callback_locked_until == lease_until`` fencing value the
        claim returned (``docs/p1-database-plan.md`` section C steps 4-5). A
        stale/superseded lease matches zero rows and returns ``False``
        without touching the new owner's row.

        On success (``True``): ``callback_state`` becomes ``delivered`` and
        both ``callback_locked_until`` and ``next_callback_at`` are cleared,
        since a delivered callback is never due again. This method never
        touches ``request_state``/``result``/``error``/``completed_at`` --
        callback outcomes must never change the operation's already-terminal
        state (section C step 5).
        """
        result = await self._session.execute(
            update(Operation)
            .where(
                Operation.request_id == request_id,
                Operation.callback_state == CallbackState.SENDING,
                Operation.callback_locked_until == lease_until,
            )
            .values(
                callback_state=CallbackState.DELIVERED,
                callback_locked_until=None,
                next_callback_at=None,
                updated_at=func.now(),
            )
        )
        return cast("CursorResult[Any]", result).rowcount > 0

    async def record_callback_retry(
        self, *, request_id: UUID, lease_until: datetime, next_callback_at: datetime
    ) -> bool:
        """Fenced: return a leased callback to ``retry`` after a failed delivery attempt.

        Same fencing predicate as :meth:`record_callback_delivered`; returns
        ``False`` for a stale/superseded lease.

        On success: ``callback_state`` becomes ``retry``, ``next_callback_at``
        is set to the caller-supplied timestamp (this primitive never
        computes a retry-backoff duration -- that is the future retry
        classifier's job, per ``D-02``), and ``callback_locked_until`` is
        cleared so the row becomes claimable again once due. Never touches
        ``request_state``/``result``/``error``/``completed_at``.
        """
        result = await self._session.execute(
            update(Operation)
            .where(
                Operation.request_id == request_id,
                Operation.callback_state == CallbackState.SENDING,
                Operation.callback_locked_until == lease_until,
            )
            .values(
                callback_state=CallbackState.RETRY,
                next_callback_at=next_callback_at,
                callback_locked_until=None,
                updated_at=func.now(),
            )
        )
        return cast("CursorResult[Any]", result).rowcount > 0

    async def record_callback_dead_letter(self, *, request_id: UUID, lease_until: datetime) -> bool:
        """Fenced: terminally give up on a leased callback after retry exhaustion.

        Same fencing predicate as :meth:`record_callback_delivered`; returns
        ``False`` for a stale/superseded lease.

        On success: ``callback_state`` becomes ``dead_letter`` and both
        ``callback_locked_until`` and ``next_callback_at`` are cleared, since
        a dead-lettered callback is never due again. Never touches
        ``request_state``/``result``/``error``/``completed_at`` -- the
        operation's own terminal outcome is unaffected by callback delivery
        failure (section C step 5).
        """
        result = await self._session.execute(
            update(Operation)
            .where(
                Operation.request_id == request_id,
                Operation.callback_state == CallbackState.SENDING,
                Operation.callback_locked_until == lease_until,
            )
            .values(
                callback_state=CallbackState.DEAD_LETTER,
                callback_locked_until=None,
                next_callback_at=None,
                updated_at=func.now(),
            )
        )
        return cast("CursorResult[Any]", result).rowcount > 0

    async def record_device_report(self, report: DeviceReport) -> ReportOutcome | None:
        """Atomically apply a device report per ``docs/p1-database-plan.md`` section D.

        Implements steps 1-4: ``SELECT ... FOR UPDATE`` locks the row by
        ``request_id`` first (step 1) -- this row lock is exactly what
        guarantees the report-vs-timeout race in :meth:`record_timeout` has
        one winner (step 6): a concurrent ``record_timeout`` conditional
        ``UPDATE`` either blocks until this transaction commits (and then
        finds ``request_state`` no longer ``processing``, so it affects zero
        rows), or already won and committed before this ``SELECT FOR UPDATE``
        runs (so this call takes the "already terminal" branch below).

        Returns ``None`` if no row exists for ``request_id`` (report
        validation/ownership against a real operation is the service layer's
        job, per ``D-04``/``D-07``/``D-08`` -- out of scope here).

        If the locked row is still ``processing``: transitions it to
        ``succeeded``+``result`` or ``failed``+``error`` (per
        ``report.execution_state``), sets ``completed_at``,
        ``delivery_state=report_received``, ``report_payload_hash``, and the
        caller-supplied ``callback_state``/``next_callback_at`` (this
        primitive never decides callback policy). Returns an ``APPLIED``
        outcome wrapping the updated row.

        If the locked row is already terminal: no-op (the row is never
        re-touched). Returns ``IDEMPOTENT_DUPLICATE`` if
        ``report.report_payload_hash`` matches the row's stored hash
        (``D-09`` exact-duplicate replay), otherwise ``CONFLICT`` (``D-09``
        differing-payload-after-terminal, including a late report after an
        already-``timed_out`` row, which never has a stored report hash).
        """
        locked_row = (
            await self._session.execute(
                select(Operation).where(Operation.request_id == report.request_id).with_for_update()
            )
        ).scalar_one_or_none()
        if locked_row is None:
            return None

        if locked_row.request_state != RequestState.PROCESSING:
            if locked_row.report_payload_hash == report.report_payload_hash:
                return ReportOutcome(ReportOutcomeStatus.IDEMPOTENT_DUPLICATE, locked_row)
            return ReportOutcome(ReportOutcomeStatus.CONFLICT, locked_row)

        new_request_state = (
            RequestState.SUCCEEDED
            if report.execution_state is ReportExecutionState.SUCCEEDED
            else RequestState.FAILED
        )
        update_result = await self._session.execute(
            update(Operation)
            .where(
                Operation.request_id == report.request_id,
                Operation.request_state == RequestState.PROCESSING,
            )
            .values(
                request_state=new_request_state,
                result=report.result,
                error=report.error,
                completed_at=func.now(),
                delivery_state=DeliveryState.REPORT_RECEIVED,
                report_payload_hash=report.report_payload_hash,
                callback_state=report.callback_state,
                next_callback_at=report.next_callback_at,
                updated_at=func.now(),
            )
            .returning(Operation)
        )
        applied_row = update_result.scalars().one()
        return ReportOutcome(ReportOutcomeStatus.APPLIED, applied_row)

    async def record_timeout(self, *, request_id: UUID, now: datetime, error: JsonObject) -> bool:
        """Conditionally transition a still-``processing``, expired operation to ``timed_out``.

        Implements ``docs/p1-database-plan.md`` section D step 5: a single
        conditional ``UPDATE ... WHERE request_id=? AND request_state=
        'processing' AND expires_at <= ?``. This primitive does not invent
        the internal ``REPORT_TIMEOUT`` blueprint reason (``D-13``) itself --
        the caller supplies the full ``error`` payload (e.g.
        ``{"code": "REPORT_TIMEOUT", ...}``), keeping this method a generic
        conditional terminal-transition primitive with no opinion on error
        content, matching :meth:`record_delivery_permanent_failure`'s
        philosophy.

        On success: sets ``request_state=timed_out``,
        ``delivery_state=report_timeout``, ``completed_at=now()``, and the
        caller-supplied ``error``.

        Returns whether the update actually fired. ``False`` (0 rows) means
        the operation had already left ``processing`` by the time this ran
        -- either a device report won the race (``record_device_report``
        already made it terminal) or ``expires_at`` was not yet due --
        without needing to distinguish which.
        """
        result = await self._session.execute(
            update(Operation)
            .where(
                Operation.request_id == request_id,
                Operation.request_state == RequestState.PROCESSING,
                Operation.expires_at <= now,
            )
            .values(
                request_state=RequestState.TIMED_OUT,
                delivery_state=DeliveryState.REPORT_TIMEOUT,
                completed_at=func.now(),
                error=error,
                updated_at=func.now(),
            )
        )
        return cast("CursorResult[Any]", result).rowcount > 0
