"""PostgreSQL integration tests for callback claim/lease and report/timeout (tasks P1-DB-09/10).

Scope note (deliberate, per explicit speed directive for this task): this
covers only the main successful flow for each new primitive --
``claim_due_callbacks`` + ``record_callback_delivered``, a successful device
report, a failing device report, an exact-duplicate report replay, and
``record_timeout``. It intentionally does not add exhaustive
constraint-violation, fencing-rejection, or concurrency-race coverage
(``PG-18``/``PG-19``/``PG-20``/``PG-21``/``PG-23`` are not exercised here);
that is an explicit, acknowledged scope reduction for this round, not an
oversight, matching the analogous fencing/race coverage already proven for
delivery claim/lease in ``test_operation_delivery_claim.py``.

All setup bypasses ``OperationRepository.insert_or_get`` in favor of
constructing ``Operation`` rows directly, so each test has exact,
independent control over ``request_state``/``callback_state``/``expires_at``
without depending on database wall-clock timing.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING
from uuid import UUID, uuid4

from app.actions import Action
from app.actions import Operation as OperationName
from app.models.enums import CallbackState, DeliveryState, RequestState
from app.models.operation import JsonObject, Operation
from app.repositories.operation import (
    DeviceReport,
    OperationRepository,
    ReportExecutionState,
    ReportOutcomeStatus,
)

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession


def _direct_operation(  # noqa: PLR0913 -- test builder: every field is an independent override
    *,
    user_id: str = "user-1",
    request_id: UUID | None = None,
    request_state: RequestState = RequestState.PROCESSING,
    callback_state: CallbackState = CallbackState.NOT_REQUIRED,
    callback_attempts: int = 0,
    callback_locked_until: datetime | None = None,
    next_callback_at: datetime | None = None,
    result: JsonObject | None = None,
    error: JsonObject | None = None,
    report_payload_hash: str | None = None,
    completed_at: datetime | None = None,
    expires_at: datetime | None = None,
    fingerprint_seed: str = "a",
) -> Operation:
    """Build an ``Operation`` model instance with exact callback/report-state control.

    Bypasses ``OperationRepository.insert_or_get`` on purpose: these tests
    need precise, independent control over ``request_state``,
    ``callback_state``, and ``expires_at`` that the insert-or-get primitive
    (which always starts a row ``processing``/``callback pending or
    not_required`` per caller policy) does not offer directly.
    """
    now = datetime.now(UTC)
    return Operation(
        request_id=request_id if request_id is not None else uuid4(),
        client_id="client-1",
        user_id=user_id,
        operation=OperationName.RIDE_QUOTE,
        action=Action.RIDE_QUOTE,
        params={},
        request_fingerprint=fingerprint_seed * 64,
        request_state=request_state,
        result=result,
        error=error,
        callback_state=callback_state,
        callback_attempts=callback_attempts,
        callback_locked_until=callback_locked_until,
        next_callback_at=next_callback_at,
        delivery_state=DeliveryState.SENT,
        report_payload_hash=report_payload_hash,
        expires_at=expires_at if expires_at is not None else now + timedelta(seconds=300),
        completed_at=completed_at,
    )


# ---------------------------------------------------------------------------
# claim_due_callbacks + record_callback_delivered: happy path
# ---------------------------------------------------------------------------


async def test_claim_due_callback_then_record_delivered_marks_callback_delivered(
    db_session: AsyncSession,
) -> None:
    """A due callback on a terminal operation is claimed, then marked delivered."""
    now = datetime.now(UTC)
    operation = _direct_operation(
        request_state=RequestState.SUCCEEDED,
        callback_state=CallbackState.PENDING,
        next_callback_at=now - timedelta(seconds=5),
        result={"quote_id": "q-1"},
        completed_at=now,
    )
    db_session.add(operation)
    await db_session.commit()

    repository = OperationRepository(db_session)
    lease_until = now + timedelta(minutes=5)
    claimed = await repository.claim_due_callbacks(now=now, lease_until=lease_until, limit=10)
    await db_session.commit()

    assert len(claimed) == 1
    claimed_row = claimed[0]
    assert claimed_row.request_id == operation.request_id
    assert claimed_row.callback_state is CallbackState.SENDING
    assert claimed_row.callback_attempts == 1
    assert claimed_row.callback_locked_until == lease_until

    affected = await repository.record_callback_delivered(
        request_id=operation.request_id, lease_until=lease_until
    )
    await db_session.commit()
    assert affected is True

    result = await repository.get_by_request_id(
        client_id=operation.client_id, request_id=operation.request_id
    )
    assert result is not None
    assert result.callback_state is CallbackState.DELIVERED
    assert result.callback_locked_until is None
    assert result.next_callback_at is None
    # Callback outcomes never change the already-terminal request state/result.
    assert result.request_state is RequestState.SUCCEEDED
    assert result.result == {"quote_id": "q-1"}


# ---------------------------------------------------------------------------
# record_device_report: successful report happy path
# ---------------------------------------------------------------------------


async def test_record_device_report_success_transitions_processing_to_succeeded(
    db_session: AsyncSession,
) -> None:
    """A successful device report on a ``processing`` operation transitions it to ``succeeded``."""
    operation = _direct_operation(request_state=RequestState.PROCESSING)
    db_session.add(operation)
    await db_session.commit()

    repository = OperationRepository(db_session)
    report = DeviceReport(
        request_id=operation.request_id,
        execution_state=ReportExecutionState.SUCCEEDED,
        result={"quote_id": "q-42", "price_cents": 1500},
        error=None,
        report_payload_hash="b" * 64,
        callback_state=CallbackState.PENDING,
        next_callback_at=datetime.now(UTC),
    )
    outcome = await repository.record_device_report(report)
    await db_session.commit()

    assert outcome is not None
    assert outcome.status is ReportOutcomeStatus.APPLIED
    assert outcome.operation.request_state is RequestState.SUCCEEDED
    assert outcome.operation.result == {"quote_id": "q-42", "price_cents": 1500}
    assert outcome.operation.error is None
    assert outcome.operation.completed_at is not None
    assert outcome.operation.delivery_state is DeliveryState.REPORT_RECEIVED
    assert outcome.operation.report_payload_hash == "b" * 64
    assert outcome.operation.callback_state is CallbackState.PENDING


# ---------------------------------------------------------------------------
# record_device_report: failing report happy path
# ---------------------------------------------------------------------------


async def test_record_device_report_failure_transitions_processing_to_failed(
    db_session: AsyncSession,
) -> None:
    """A failing device report on a ``processing`` operation transitions it to ``failed``."""
    operation = _direct_operation(request_state=RequestState.PROCESSING)
    db_session.add(operation)
    await db_session.commit()

    repository = OperationRepository(db_session)
    error_payload: JsonObject = {
        "code": "ACTION_REJECTED",
        "message": "user denied permission",
        "details": None,
    }
    report = DeviceReport(
        request_id=operation.request_id,
        execution_state=ReportExecutionState.FAILED,
        result=None,
        error=error_payload,
        report_payload_hash="c" * 64,
        callback_state=CallbackState.PENDING,
        next_callback_at=datetime.now(UTC),
    )
    outcome = await repository.record_device_report(report)
    await db_session.commit()

    assert outcome is not None
    assert outcome.status is ReportOutcomeStatus.APPLIED
    assert outcome.operation.request_state is RequestState.FAILED
    assert outcome.operation.error == error_payload
    assert outcome.operation.result is None
    assert outcome.operation.completed_at is not None


# ---------------------------------------------------------------------------
# record_device_report: exact-duplicate replay is an idempotent no-op (D-09)
# ---------------------------------------------------------------------------


async def test_record_device_report_exact_duplicate_replay_is_idempotent_noop(
    db_session: AsyncSession,
) -> None:
    """Submitting the exact same report hash twice: the second call is a no-op."""
    operation = _direct_operation(request_state=RequestState.PROCESSING)
    db_session.add(operation)
    await db_session.commit()

    repository = OperationRepository(db_session)
    report = DeviceReport(
        request_id=operation.request_id,
        execution_state=ReportExecutionState.SUCCEEDED,
        result={"quote_id": "q-dup"},
        error=None,
        report_payload_hash="d" * 64,
        callback_state=CallbackState.PENDING,
        next_callback_at=datetime.now(UTC),
    )
    first_outcome = await repository.record_device_report(report)
    await db_session.commit()
    assert first_outcome is not None
    assert first_outcome.status is ReportOutcomeStatus.APPLIED
    first_completed_at = first_outcome.operation.completed_at

    second_outcome = await repository.record_device_report(report)
    await db_session.commit()

    assert second_outcome is not None
    assert second_outcome.status is ReportOutcomeStatus.IDEMPOTENT_DUPLICATE

    unchanged = await repository.get_by_request_id(
        client_id=operation.client_id, request_id=operation.request_id
    )
    assert unchanged is not None
    assert unchanged.request_state is RequestState.SUCCEEDED
    assert unchanged.result == {"quote_id": "q-dup"}
    assert unchanged.completed_at == first_completed_at


# ---------------------------------------------------------------------------
# record_timeout: happy path
# ---------------------------------------------------------------------------


async def test_record_timeout_transitions_expired_processing_operation_to_timed_out(
    db_session: AsyncSession,
) -> None:
    """A ``processing`` operation past its ``expires_at`` deadline transitions to ``timed_out``."""
    now = datetime.now(UTC)
    operation = _direct_operation(
        request_state=RequestState.PROCESSING,
        expires_at=now - timedelta(seconds=1),
    )
    db_session.add(operation)
    await db_session.commit()

    repository = OperationRepository(db_session)
    error_payload: JsonObject = {
        "code": "REPORT_TIMEOUT",
        "message": "device did not report within the deadline",
        "details": None,
    }
    fired = await repository.record_timeout(
        request_id=operation.request_id, now=now, error=error_payload
    )
    await db_session.commit()
    assert fired is True

    result = await repository.get_by_request_id(
        client_id=operation.client_id, request_id=operation.request_id
    )
    assert result is not None
    assert result.request_state is RequestState.TIMED_OUT
    assert result.delivery_state is DeliveryState.REPORT_TIMEOUT
    assert result.error == error_payload
    assert result.result is None
    assert result.completed_at is not None
