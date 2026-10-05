"""Persistence for the ``operations`` table: accept, delivery, report, timeout, callback.

Every state change is a conditional ``UPDATE`` (or ``SELECT ... FOR UPDATE``)
so concurrent workers, reports and timeouts have exactly one winner. Leased
rows are fenced by ``*_locked_until``: a worker whose lease was reclaimed
updates zero rows. Policy (fingerprints, timeouts, backoff, error codes) and
``commit()`` belong to the caller.
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

    ``IDEMPOTENT_MATCH`` and ``FINGERPRINT_CONFLICT`` both return the existing
    row untouched; they differ in whether client and fingerprint match.
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
    """Values for a new operation row, all decided by the service layer."""

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
    """Terminal outcome reported by the phone (local enum: repositories never import schemas)."""

    SUCCEEDED = "succeeded"
    FAILED = "failed"


class ReportOutcomeStatus(StrEnum):
    """Outcome of :meth:`OperationRepository.record_device_report`.

    ``APPLIED``: the operation was processing and is now terminal.
    ``IDEMPOTENT_DUPLICATE``: already terminal with the same report hash; nothing changes.
    ``CONFLICT``: already terminal with a different (or no) report hash, e.g. a
    late report after a timeout; nothing changes.
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
    """One validated phone report plus the callback scheduling the caller chose."""

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
        """Insert a ``processing`` row due for delivery now, or classify the existing one."""
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
        """Return the operation only if ``client_id`` owns it."""
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
        """Lease up to ``limit`` due deliveries to this worker (``FOR UPDATE SKIP LOCKED``).

        Due means processing and either received/retry with ``next_delivery_at <= now``
        or ``sending`` with an expired lease (crash recovery). Each claimed row gets
        the user's latest active phone as ``device_id``; rows whose user has no active
        phone are skipped and stay due.
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
        """Fenced: mark the leased delivery as sent (the operation stays processing)."""
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
        """Fenced: put the leased delivery back to ``retry`` at ``next_delivery_at``."""
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
        """Fenced: fail the delivery and the still-processing operation with ``error``."""
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
        """Lease up to ``limit`` due callbacks of terminal operations to this worker.

        Due means pending/retry with ``next_callback_at <= now`` or ``sending`` with
        an expired lease. Callback outcomes never change the operation's own state.
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
        """Fenced: mark the leased callback as delivered."""
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
        """Fenced: schedule the leased callback for another attempt."""
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
        """Fenced: give up on the leased callback after retries are exhausted."""
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
        """Apply a report under a row lock; ``None`` if the operation does not exist.

        The ``FOR UPDATE`` lock serializes this with :meth:`record_timeout`, so a
        report and a timeout can never both win.
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

    async def list_expired(self, *, now: datetime, limit: int) -> list[Operation]:
        """Return up to ``limit`` processing operations whose deadline has passed."""
        result = await self._session.execute(
            select(Operation)
            .where(Operation.request_state == RequestState.PROCESSING, Operation.expires_at <= now)
            .limit(limit)
        )
        return list(result.scalars().all())

    async def record_timeout(
        self,
        *,
        request_id: UUID,
        now: datetime,
        error: JsonObject,
        schedule_callback: bool = False,
    ) -> bool:
        """Move an expired, still-processing operation to ``timed_out``; ``False`` if it was not."""
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
                **(
                    {"callback_state": CallbackState.PENDING, "next_callback_at": now}
                    if schedule_callback
                    else {}
                ),
            )
        )
        return cast("CursorResult[Any]", result).rowcount > 0
