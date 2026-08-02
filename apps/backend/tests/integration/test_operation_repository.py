"""PostgreSQL integration tests for ``OperationRepository`` (task P1-DB-07).

Covers integration matrix rows PG-06 through PG-12 and PG-24 from
``docs/p1-database-plan.md``. Constraint-rejection tests (PG-07, PG-08)
bypass the repository and insert the ``Operation`` SQLAlchemy model
directly, proving the named DB CHECK constraints themselves reject invalid
rows independent of any repository or Python-side validation.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING, cast
from uuid import UUID, uuid4

import pytest
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError

from app.actions import Action
from app.actions import Operation as OperationName
from app.models.enums import CallbackState, RequestState
from app.models.operation import JsonObject, Operation
from app.repositories.operation import (
    NewOperation,
    OperationInsertOutcome,
    OperationInsertStatus,
    OperationRepository,
)

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.database import AsyncSessionFactory


def _new_operation(  # noqa: PLR0913 -- test builder: every field is an independent override
    *,
    request_id: UUID | None = None,
    client_id: str = "client-1",
    user_id: str = "user-1",
    operation: OperationName = OperationName.RIDE_QUOTE,
    action: Action = Action.RIDE_QUOTE,
    params: JsonObject | None = None,
    request_fingerprint: str = "a" * 64,
    callback_state: CallbackState = CallbackState.NOT_REQUIRED,
) -> NewOperation:
    """Build a valid :class:`NewOperation` candidate with sane defaults."""
    now = datetime.now(UTC)
    return NewOperation(
        request_id=request_id if request_id is not None else uuid4(),
        client_id=client_id,
        user_id=user_id,
        operation=operation,
        action=action,
        params=params if params is not None else {},
        request_fingerprint=request_fingerprint,
        callback_state=callback_state,
        expires_at=now + timedelta(seconds=60),
    )


def _direct_operation(  # noqa: PLR0913 -- test builder: every field is an independent override
    *,
    request_state: RequestState = RequestState.PROCESSING,
    result: JsonObject | None = None,
    error: JsonObject | None = None,
    completed_at: datetime | None = None,
    operation: OperationName = OperationName.RIDE_QUOTE,
    action: Action = Action.RIDE_QUOTE,
    request_fingerprint: str = "a" * 64,
    delivery_attempts: int = 0,
    callback_attempts: int = 0,
) -> Operation:
    """Build an ``Operation`` model instance for direct-insert constraint tests.

    Bypasses ``OperationRepository`` on purpose so these tests exercise the
    named PostgreSQL CHECK constraints themselves rather than any
    Python-side validation.
    """
    now = datetime.now(UTC)
    return Operation(
        request_id=uuid4(),
        client_id="client-1",
        user_id="user-1",
        operation=operation,
        action=action,
        params={},
        request_fingerprint=request_fingerprint,
        request_state=request_state,
        result=result,
        error=error,
        callback_state=CallbackState.NOT_REQUIRED,
        delivery_attempts=delivery_attempts,
        callback_attempts=callback_attempts,
        expires_at=now + timedelta(seconds=60),
        completed_at=completed_at,
    )


async def _row_count(session: AsyncSession) -> int:
    return (await session.execute(select(func.count()).select_from(Operation))).scalar_one()


# ---------------------------------------------------------------------------
# PG-06: valid row + JSONB params round-trip
# ---------------------------------------------------------------------------


async def test_insert_persists_jsonb_params_and_timestamps_round_trip(
    db_session: AsyncSession,
    postgres_session_factory: AsyncSessionFactory,
) -> None:
    """Nested/unicode/bool/number JSONB params and timestamps round-trip exactly."""
    repository = OperationRepository(db_session)
    request_id = uuid4()
    complex_params: JsonObject = {
        "pickup": {"lat": 10.762622, "lng": 106.660172},
        "waypoints": ["a", "b", "c"],
        "notes": "Đón ở cổng chính, quán café Sài Gòn",
        "priority": True,
        "seats": 4,
        "fare_estimate": 125000.5,
        "meta": {"nested": {"flag": False, "tags": ["x", "y"]}},
    }
    candidate = _new_operation(request_id=request_id, params=complex_params)

    outcome = await repository.insert_or_get(candidate)
    await db_session.commit()

    assert outcome.status is OperationInsertStatus.INSERTED
    assert isinstance(outcome.operation.request_id, UUID)
    assert outcome.operation.params == complex_params
    assert outcome.operation.next_delivery_at == outcome.operation.created_at

    # Re-read through an independent connection/session to prove real
    # persistence, not just an in-session identity-map echo.
    async with postgres_session_factory() as verification_session:
        verification_repository = OperationRepository(verification_session)
        fetched = await verification_repository.get_by_request_id(
            client_id=candidate.client_id, request_id=request_id
        )

    assert fetched is not None
    assert fetched.request_id == request_id
    assert fetched.params == complex_params
    for timestamp in (
        fetched.created_at,
        fetched.updated_at,
        fetched.expires_at,
    ):
        assert timestamp.tzinfo is not None
    assert fetched.next_delivery_at is not None
    assert fetched.next_delivery_at.tzinfo is not None
    assert fetched.next_delivery_at == fetched.created_at


# ---------------------------------------------------------------------------
# PG-07: invalid enum/mapping/attempt/hash rejected by named CHECK constraints
# ---------------------------------------------------------------------------


async def test_invalid_operation_and_action_enum_value_is_rejected(
    db_session: AsyncSession,
) -> None:
    """A value outside the fixed 9-action set violates the enum CHECK."""
    invalid_value = cast("OperationName", "not_a_real_action")
    operation = _direct_operation(
        operation=invalid_value, action=cast("Action", "not_a_real_action")
    )
    db_session.add(operation)

    with pytest.raises(IntegrityError) as exc_info:
        await db_session.commit()
    await db_session.rollback()

    message = str(exc_info.value)
    assert "ck_operations_operation" in message or "ck_operations_action" in message


async def test_operation_action_mismatch_is_rejected(db_session: AsyncSession) -> None:
    """``operation != action`` violates ``ck_operations_mapping``."""
    operation = _direct_operation(operation=OperationName.RIDE_QUOTE, action=Action.RIDE_CONFIRM)
    db_session.add(operation)

    with pytest.raises(IntegrityError) as exc_info:
        await db_session.commit()
    await db_session.rollback()

    assert "ck_operations_mapping" in str(exc_info.value)


async def test_negative_delivery_attempts_is_rejected(db_session: AsyncSession) -> None:
    """Negative ``delivery_attempts`` violates ``ck_operations_delivery_attempts``."""
    operation = _direct_operation(delivery_attempts=-1)
    db_session.add(operation)

    with pytest.raises(IntegrityError) as exc_info:
        await db_session.commit()
    await db_session.rollback()

    assert "ck_operations_delivery_attempts" in str(exc_info.value)


async def test_negative_callback_attempts_is_rejected(db_session: AsyncSession) -> None:
    """Negative ``callback_attempts`` violates ``ck_operations_callback_attempts``."""
    operation = _direct_operation(callback_attempts=-1)
    db_session.add(operation)

    with pytest.raises(IntegrityError) as exc_info:
        await db_session.commit()
    await db_session.rollback()

    assert "ck_operations_callback_attempts" in str(exc_info.value)


async def test_wrong_length_request_fingerprint_is_rejected(db_session: AsyncSession) -> None:
    """A fingerprint that is not exactly 64 characters is rejected."""
    operation = _direct_operation(request_fingerprint="too-short")
    db_session.add(operation)

    with pytest.raises(IntegrityError) as exc_info:
        await db_session.commit()
    await db_session.rollback()

    assert "ck_operations_request_fingerprint" in str(exc_info.value)


# ---------------------------------------------------------------------------
# PG-08: ck_operations_terminal_payload accept/reject per request_state
# ---------------------------------------------------------------------------


async def test_processing_with_null_result_error_completed_at_is_accepted(
    db_session: AsyncSession,
) -> None:
    """``processing`` with all-null result/error/completed_at is valid."""
    operation = _direct_operation(request_state=RequestState.PROCESSING)
    db_session.add(operation)

    await db_session.commit()

    persisted = await db_session.get(Operation, operation.request_id)
    assert persisted is not None
    assert persisted.request_state is RequestState.PROCESSING


async def test_processing_with_non_null_result_is_rejected(db_session: AsyncSession) -> None:
    """``processing`` with a non-null result violates the terminal-payload check."""
    operation = _direct_operation(request_state=RequestState.PROCESSING, result={"x": 1})
    db_session.add(operation)

    with pytest.raises(IntegrityError) as exc_info:
        await db_session.commit()
    await db_session.rollback()

    assert "ck_operations_terminal_payload" in str(exc_info.value)


async def test_succeeded_with_result_and_completed_at_is_accepted(
    db_session: AsyncSession,
) -> None:
    """``succeeded`` with non-null result, null error, non-null completed_at is valid."""
    operation = _direct_operation(
        request_state=RequestState.SUCCEEDED,
        result={"ok": True},
        completed_at=datetime.now(UTC),
    )
    db_session.add(operation)

    await db_session.commit()

    persisted = await db_session.get(Operation, operation.request_id)
    assert persisted is not None
    assert persisted.request_state is RequestState.SUCCEEDED


async def test_succeeded_without_completed_at_is_rejected(db_session: AsyncSession) -> None:
    """``succeeded`` missing ``completed_at`` violates the terminal-payload check."""
    operation = _direct_operation(
        request_state=RequestState.SUCCEEDED,
        result={"ok": True},
        completed_at=None,
    )
    db_session.add(operation)

    with pytest.raises(IntegrityError) as exc_info:
        await db_session.commit()
    await db_session.rollback()

    assert "ck_operations_terminal_payload" in str(exc_info.value)


async def test_failed_with_error_and_completed_at_is_accepted(db_session: AsyncSession) -> None:
    """``failed`` with null result, non-null error, non-null completed_at is valid."""
    operation = _direct_operation(
        request_state=RequestState.FAILED,
        error={"code": "E", "message": "boom"},
        completed_at=datetime.now(UTC),
    )
    db_session.add(operation)

    await db_session.commit()

    persisted = await db_session.get(Operation, operation.request_id)
    assert persisted is not None
    assert persisted.request_state is RequestState.FAILED


async def test_failed_without_error_is_rejected(db_session: AsyncSession) -> None:
    """``failed`` missing ``error`` violates the terminal-payload check."""
    operation = _direct_operation(
        request_state=RequestState.FAILED,
        error=None,
        completed_at=datetime.now(UTC),
    )
    db_session.add(operation)

    with pytest.raises(IntegrityError) as exc_info:
        await db_session.commit()
    await db_session.rollback()

    assert "ck_operations_terminal_payload" in str(exc_info.value)


async def test_timed_out_with_error_and_completed_at_is_accepted(
    db_session: AsyncSession,
) -> None:
    """``timed_out`` with null result, non-null error, non-null completed_at is valid."""
    operation = _direct_operation(
        request_state=RequestState.TIMED_OUT,
        error={"code": "REQUEST_TIMEOUT", "message": "no report"},
        completed_at=datetime.now(UTC),
    )
    db_session.add(operation)

    await db_session.commit()

    persisted = await db_session.get(Operation, operation.request_id)
    assert persisted is not None
    assert persisted.request_state is RequestState.TIMED_OUT


async def test_timed_out_with_non_null_result_is_rejected(db_session: AsyncSession) -> None:
    """``timed_out`` with a non-null result violates the terminal-payload check."""
    operation = _direct_operation(
        request_state=RequestState.TIMED_OUT,
        result={"oops": True},
        error={"code": "REQUEST_TIMEOUT", "message": "no report"},
        completed_at=datetime.now(UTC),
    )
    db_session.add(operation)

    with pytest.raises(IntegrityError) as exc_info:
        await db_session.commit()
    await db_session.rollback()

    assert "ck_operations_terminal_payload" in str(exc_info.value)


# ---------------------------------------------------------------------------
# PG-09 / PG-10: sequential idempotency
# ---------------------------------------------------------------------------


async def test_sequential_insert_same_client_and_fingerprint_is_idempotent_match(
    db_session: AsyncSession,
) -> None:
    """Repeating the identical candidate returns the original row unchanged."""
    repository = OperationRepository(db_session)
    request_id = uuid4()
    candidate = _new_operation(
        request_id=request_id, client_id="client-1", request_fingerprint="a" * 64
    )

    first_outcome = await repository.insert_or_get(candidate)
    await db_session.commit()
    assert first_outcome.status is OperationInsertStatus.INSERTED
    original_created_at = first_outcome.operation.created_at
    original_delivery_attempts = first_outcome.operation.delivery_attempts
    original_callback_attempts = first_outcome.operation.callback_attempts

    second_outcome = await repository.insert_or_get(candidate)
    await db_session.commit()

    assert second_outcome.status is OperationInsertStatus.IDEMPOTENT_MATCH
    assert second_outcome.operation.request_id == request_id
    assert second_outcome.operation.created_at == original_created_at
    assert second_outcome.operation.delivery_attempts == original_delivery_attempts
    assert second_outcome.operation.callback_attempts == original_callback_attempts
    assert await _row_count(db_session) == 1


async def test_sequential_insert_different_fingerprint_is_conflict(
    db_session: AsyncSession,
) -> None:
    """A second candidate with the same ID/client but a different fingerprint conflicts."""
    repository = OperationRepository(db_session)
    request_id = uuid4()
    first_candidate = _new_operation(
        request_id=request_id, client_id="client-1", request_fingerprint="a" * 64
    )
    second_candidate = _new_operation(
        request_id=request_id, client_id="client-1", request_fingerprint="b" * 64
    )

    first_outcome = await repository.insert_or_get(first_candidate)
    await db_session.commit()
    assert first_outcome.status is OperationInsertStatus.INSERTED

    second_outcome = await repository.insert_or_get(second_candidate)
    await db_session.commit()

    assert second_outcome.status is OperationInsertStatus.FINGERPRINT_CONFLICT
    assert second_outcome.operation.request_fingerprint == "a" * 64
    assert await _row_count(db_session) == 1


# ---------------------------------------------------------------------------
# PG-11 / PG-12: concurrent insert races
# ---------------------------------------------------------------------------


async def _attempt_insert(
    session_factory: AsyncSessionFactory,
    candidate: NewOperation,
    barrier: asyncio.Barrier,
) -> OperationInsertOutcome:
    """Open an independent session, wait at the barrier, then attempt insert."""
    async with session_factory() as session:
        repository = OperationRepository(session)
        await asyncio.wait_for(barrier.wait(), timeout=5)
        outcome = await repository.insert_or_get(candidate)
        await session.commit()
        return outcome


async def test_concurrent_insert_same_fingerprint_has_exactly_one_inserted_winner(
    postgres_session_factory: AsyncSessionFactory,
    db_session: AsyncSession,
) -> None:
    """Two connections racing the identical candidate: one inserts, one matches.

    Under ``INSERT ... ON CONFLICT (request_id) DO NOTHING``, PostgreSQL
    serializes the two concurrent inserters on the primary key: whichever
    transaction commits first wins the ``RETURNING`` row (``INSERTED``); the
    other blocks on the conflicting key, then — once the winner has
    committed — proceeds with zero rows affected, so the repository falls
    through to its post-conflict read. Because both attempts use the same
    ``client_id``/``request_fingerprint``, that read is an idempotent match.
    Exactly one row can ever exist for one primary key, so there is no
    scenario producing two winners or two losers.
    """
    request_id = uuid4()
    candidate = _new_operation(
        request_id=request_id, client_id="client-1", request_fingerprint="c" * 64
    )
    barrier = asyncio.Barrier(2)

    outcome_a, outcome_b = await asyncio.wait_for(
        asyncio.gather(
            _attempt_insert(postgres_session_factory, candidate, barrier),
            _attempt_insert(postgres_session_factory, candidate, barrier),
        ),
        timeout=10,
    )

    statuses = {outcome_a.status, outcome_b.status}
    assert statuses == {OperationInsertStatus.INSERTED, OperationInsertStatus.IDEMPOTENT_MATCH}
    assert await _row_count(db_session) == 1


async def test_concurrent_insert_different_fingerprint_has_deterministic_conflict(
    postgres_session_factory: AsyncSessionFactory,
    db_session: AsyncSession,
) -> None:
    """Two connections racing different payloads for the same ID: one wins, one conflicts.

    Same serialization argument as the identical-payload race above, except
    the loser's post-conflict read finds a different ``request_fingerprint``
    than its own candidate, so it is classified as ``FINGERPRINT_CONFLICT``
    rather than an idempotent match.
    """
    request_id = uuid4()
    candidate_a = _new_operation(
        request_id=request_id, client_id="client-1", request_fingerprint="d" * 64
    )
    candidate_b = _new_operation(
        request_id=request_id, client_id="client-1", request_fingerprint="e" * 64
    )
    barrier = asyncio.Barrier(2)

    outcome_a, outcome_b = await asyncio.wait_for(
        asyncio.gather(
            _attempt_insert(postgres_session_factory, candidate_a, barrier),
            _attempt_insert(postgres_session_factory, candidate_b, barrier),
        ),
        timeout=10,
    )

    statuses = {outcome_a.status, outcome_b.status}
    assert statuses == {
        OperationInsertStatus.INSERTED,
        OperationInsertStatus.FINGERPRINT_CONFLICT,
    }
    assert await _row_count(db_session) == 1


# ---------------------------------------------------------------------------
# PG-24: ownership-filtered lookup
# ---------------------------------------------------------------------------


async def test_get_by_request_id_filters_by_client_ownership(db_session: AsyncSession) -> None:
    """A different client_id gets ``None``, never the row nor an error."""
    repository = OperationRepository(db_session)
    request_id = uuid4()
    candidate = _new_operation(request_id=request_id, client_id="client-a")

    await repository.insert_or_get(candidate)
    await db_session.commit()

    owner_read = await repository.get_by_request_id(client_id="client-a", request_id=request_id)
    stranger_read = await repository.get_by_request_id(client_id="client-b", request_id=request_id)

    assert owner_read is not None
    assert owner_read.request_id == request_id
    assert stranger_read is None
