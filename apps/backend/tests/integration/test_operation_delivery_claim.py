"""PostgreSQL integration tests for the delivery claim/lease primitive (task P1-DB-08).

Covers integration matrix rows PG-13 through PG-17 from
``docs/p1-database-plan.md`` ("B. Delivery claim/lease"), plus focused
happy-path and fencing-rejection coverage for each fenced outcome-recording
method (`record_delivery_sent`, `record_delivery_transient_failure`,
`record_delivery_permanent_failure`) that PG-16 exercises under concurrency.

All setup bypasses ``OperationRepository.insert_or_get`` in favor of
constructing ``Operation`` rows directly, so each test has exact, independent
control over ``delivery_state``/``next_delivery_at``/``delivery_locked_until``/
``created_at`` without depending on database wall-clock timing.
"""

from __future__ import annotations

import asyncio
import hashlib
from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING
from uuid import UUID, uuid4

from sqlalchemy import update

from app.actions import Action
from app.actions import Operation as OperationName
from app.models.enums import CallbackState, DeliveryState, DevicePlatform, RequestState
from app.models.operation import JsonObject, Operation
from app.repositories.device import (
    DeviceRegistered,
    DeviceRegisterOutcome,
    DeviceRegistrationRequest,
    DeviceRepository,
)
from app.repositories.operation import OperationRepository

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.database import AsyncSessionFactory
    from app.models.device import Device


def _direct_operation(  # noqa: PLR0913 -- test builder: every field is an independent override
    *,
    user_id: str,
    request_id: UUID | None = None,
    delivery_state: DeliveryState = DeliveryState.RECEIVED,
    delivery_attempts: int = 0,
    delivery_locked_until: datetime | None = None,
    next_delivery_at: datetime | None = None,
    device_id: str | None = None,
    request_state: RequestState = RequestState.PROCESSING,
    result: JsonObject | None = None,
    error: JsonObject | None = None,
    completed_at: datetime | None = None,
    created_at: datetime | None = None,
    fingerprint_seed: str = "a",
) -> Operation:
    """Build an ``Operation`` model instance with exact delivery-state control.

    Bypasses ``OperationRepository.insert_or_get`` on purpose: claim/lease
    tests need precise, independent control over ``delivery_state``,
    ``next_delivery_at``, ``delivery_locked_until``, and ``created_at`` that
    the insert-or-get primitive (which always starts a row at
    ``received``/``next_delivery_at=created_at=now()``) does not offer.
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
        callback_state=CallbackState.NOT_REQUIRED,
        delivery_state=delivery_state,
        delivery_attempts=delivery_attempts,
        delivery_locked_until=delivery_locked_until,
        next_delivery_at=next_delivery_at,
        device_id=device_id,
        expires_at=now + timedelta(seconds=300),
        created_at=created_at if created_at is not None else now,
        completed_at=completed_at,
    )


async def _register_active_device(session: AsyncSession, *, user_id: str, device_id: str) -> Device:
    """Register and return an active device for ``user_id`` via ``DeviceRepository``."""
    repository = DeviceRepository(session)
    outcome: DeviceRegisterOutcome = await repository.register(
        DeviceRegistrationRequest(
            user_id=user_id,
            device_id=device_id,
            platform=DevicePlatform.ANDROID,
            push_token_ciphertext=f"ciphertext-{device_id}",
            push_token_fingerprint=hashlib.sha256(device_id.encode()).hexdigest(),
            last_seen_at=datetime.now(UTC),
        )
    )
    assert isinstance(outcome, DeviceRegistered)
    return outcome.device


# ---------------------------------------------------------------------------
# PG-13: two concurrent claimers get disjoint batches covering all due rows
# ---------------------------------------------------------------------------


async def _claim_in_new_session(
    session_factory: AsyncSessionFactory,
    *,
    now: datetime,
    lease_until: datetime,
    limit: int,
    barrier: asyncio.Barrier,
) -> list[UUID]:
    """Open an independent session, wait at the barrier, then attempt a claim."""
    async with session_factory() as session:
        repository = OperationRepository(session)
        await asyncio.wait_for(barrier.wait(), timeout=5)
        claimed = await repository.claim_due_deliveries(
            now=now, lease_until=lease_until, limit=limit
        )
        request_ids = [operation.request_id for operation in claimed]
        await session.commit()
        return request_ids


async def test_pg13_concurrent_claimers_get_disjoint_batches_covering_all_due_rows(
    db_session: AsyncSession,
    postgres_session_factory: AsyncSessionFactory,
) -> None:
    """Two claimers racing over the same due batch never claim the same row twice."""
    now = datetime.now(UTC)
    due_at = now - timedelta(seconds=5)
    request_ids: list[UUID] = []
    for index in range(6):
        user_id = f"user-pg13-{index}"
        await _register_active_device(db_session, user_id=user_id, device_id=f"device-pg13-{index}")
        operation = _direct_operation(
            user_id=user_id, next_delivery_at=due_at, fingerprint_seed=chr(ord("a") + index)
        )
        db_session.add(operation)
        request_ids.append(operation.request_id)
    await db_session.commit()

    lease_until = now + timedelta(minutes=5)
    barrier = asyncio.Barrier(2)
    claimed_a, claimed_b = await asyncio.wait_for(
        asyncio.gather(
            _claim_in_new_session(
                postgres_session_factory, now=now, lease_until=lease_until, limit=3, barrier=barrier
            ),
            _claim_in_new_session(
                postgres_session_factory, now=now, lease_until=lease_until, limit=3, barrier=barrier
            ),
        ),
        timeout=10,
    )

    set_a, set_b = set(claimed_a), set(claimed_b)
    assert set_a.isdisjoint(set_b)
    assert len(claimed_a) == 3
    assert len(claimed_b) == 3
    assert set_a | set_b == set(request_ids)


# ---------------------------------------------------------------------------
# PG-14: a row claimed with a still-valid lease is not reclaimed
# ---------------------------------------------------------------------------


async def test_pg14_row_with_unexpired_lease_is_not_reclaimed_by_another_claimer(
    db_session: AsyncSession,
    postgres_session_factory: AsyncSessionFactory,
) -> None:
    """Claiming a row locks it for the lease duration; a second claimer gets nothing."""
    now = datetime.now(UTC)
    user_id = "user-pg14"
    await _register_active_device(db_session, user_id=user_id, device_id="device-pg14")
    operation = _direct_operation(user_id=user_id, next_delivery_at=now - timedelta(seconds=5))
    db_session.add(operation)
    await db_session.commit()

    repository = OperationRepository(db_session)
    lease_until = now + timedelta(minutes=5)
    first_claim = await repository.claim_due_deliveries(now=now, lease_until=lease_until, limit=10)
    await db_session.commit()
    assert [row.request_id for row in first_claim] == [operation.request_id]

    async with postgres_session_factory() as second_session:
        second_repository = OperationRepository(second_session)
        second_claim = await second_repository.claim_due_deliveries(
            now=now, lease_until=now + timedelta(minutes=10), limit=10
        )
        await second_session.commit()

    assert second_claim == []


# ---------------------------------------------------------------------------
# PG-15: an expired `sending` lease (crashed worker) is reclaimed
# ---------------------------------------------------------------------------


async def test_pg15_expired_sending_lease_is_reclaimed_with_incremented_attempts(
    db_session: AsyncSession,
) -> None:
    """A `sending` row whose lease has expired is treated as due and reclaimed."""
    now = datetime.now(UTC)
    user_id = "user-pg15"
    await _register_active_device(db_session, user_id=user_id, device_id="device-pg15")
    operation = _direct_operation(
        user_id=user_id,
        delivery_state=DeliveryState.SENDING,
        delivery_locked_until=now - timedelta(minutes=1),
        delivery_attempts=1,
        device_id="stale-device-id",
    )
    db_session.add(operation)
    await db_session.commit()

    repository = OperationRepository(db_session)
    new_lease = now + timedelta(minutes=5)
    claimed = await repository.claim_due_deliveries(now=now, lease_until=new_lease, limit=10)
    await db_session.commit()

    assert len(claimed) == 1
    claimed_row = claimed[0]
    assert claimed_row.request_id == operation.request_id
    assert claimed_row.delivery_attempts == 2
    assert claimed_row.delivery_locked_until == new_lease
    assert claimed_row.delivery_state is DeliveryState.SENDING
    assert claimed_row.device_id == "device-pg15"


# ---------------------------------------------------------------------------
# PG-16: fencing -- a stale worker's outcome after reclaim affects zero rows
# ---------------------------------------------------------------------------


async def test_pg16_stale_worker_outcome_after_reclaim_affects_zero_rows_and_does_not_clobber(
    db_session: AsyncSession,
    postgres_session_factory: AsyncSessionFactory,
) -> None:
    """Worker A's outcome call with a superseded lease value is a safe no-op."""
    now = datetime.now(UTC)
    user_id = "user-pg16"
    await _register_active_device(db_session, user_id=user_id, device_id="device-pg16")
    operation = _direct_operation(user_id=user_id, next_delivery_at=now - timedelta(seconds=5))
    db_session.add(operation)
    await db_session.commit()

    repository_a = OperationRepository(db_session)
    lease_a = now + timedelta(seconds=30)
    claimed_a = await repository_a.claim_due_deliveries(now=now, lease_until=lease_a, limit=10)
    await db_session.commit()
    assert len(claimed_a) == 1

    # Simulate worker A's lease expiring (crash/stall) by forcing it into the past.
    await db_session.execute(
        update(Operation)
        .where(Operation.request_id == operation.request_id)
        .values(delivery_locked_until=now - timedelta(seconds=1))
    )
    await db_session.commit()

    async with postgres_session_factory() as session_b:
        repository_b = OperationRepository(session_b)
        lease_b = now + timedelta(minutes=5)
        claimed_b = await repository_b.claim_due_deliveries(now=now, lease_until=lease_b, limit=10)
        await session_b.commit()
    assert len(claimed_b) == 1
    assert claimed_b[0].delivery_attempts == 2

    stale_affected = await repository_a.record_delivery_sent(
        request_id=operation.request_id, lease_until=lease_a
    )
    await db_session.commit()
    assert stale_affected is False

    async with postgres_session_factory() as verify_session:
        verify_repository = OperationRepository(verify_session)
        current = await verify_repository.get_by_request_id(
            client_id=operation.client_id, request_id=operation.request_id
        )
    assert current is not None
    assert current.delivery_state is DeliveryState.SENDING
    assert current.delivery_locked_until == lease_b
    assert current.delivery_attempts == 2


# ---------------------------------------------------------------------------
# PG-17: only due rows are claimed, in next_delivery_at NULLS LAST, created_at order
# ---------------------------------------------------------------------------


async def test_pg17_only_due_rows_claimed_in_next_delivery_then_created_at_order(
    db_session: AsyncSession,
) -> None:
    """Future ``next_delivery_at`` is excluded; due rows are claimed in the documented order."""
    now = datetime.now(UTC)
    for index in range(4):
        await _register_active_device(
            db_session, user_id=f"user-pg17-{index}", device_id=f"device-pg17-{index}"
        )

    earliest_due_tie_a = _direct_operation(
        user_id="user-pg17-0",
        next_delivery_at=now - timedelta(seconds=30),
        created_at=now - timedelta(seconds=100),
        fingerprint_seed="a",
    )
    earliest_due_tie_b = _direct_operation(
        user_id="user-pg17-1",
        next_delivery_at=now - timedelta(seconds=30),
        created_at=now - timedelta(seconds=50),
        fingerprint_seed="b",
    )
    later_due = _direct_operation(
        user_id="user-pg17-2",
        next_delivery_at=now - timedelta(seconds=10),
        created_at=now - timedelta(seconds=200),
        fingerprint_seed="c",
    )
    not_due_yet = _direct_operation(
        user_id="user-pg17-3",
        next_delivery_at=now + timedelta(seconds=60),
        created_at=now - timedelta(seconds=5),
        fingerprint_seed="d",
    )
    for operation in (earliest_due_tie_a, earliest_due_tie_b, later_due, not_due_yet):
        db_session.add(operation)
    await db_session.commit()

    repository = OperationRepository(db_session)
    claimed = await repository.claim_due_deliveries(
        now=now, lease_until=now + timedelta(minutes=5), limit=10
    )
    await db_session.commit()

    assert [row.request_id for row in claimed] == [
        earliest_due_tie_a.request_id,
        earliest_due_tie_b.request_id,
        later_due.request_id,
    ]

    unclaimed = await repository.get_by_request_id(
        client_id=not_due_yet.client_id, request_id=not_due_yet.request_id
    )
    assert unclaimed is not None
    assert unclaimed.delivery_state is DeliveryState.RECEIVED
    assert unclaimed.delivery_locked_until is None


# ---------------------------------------------------------------------------
# Judgment call: due operation whose user has no active device is left unclaimed
# ---------------------------------------------------------------------------


async def test_claim_leaves_due_operation_unclaimed_when_user_has_no_active_device(
    db_session: AsyncSession,
) -> None:
    """No active device for the operation's user: skip it, don't invent a failure transition.

    This is a deliberate judgment call (see ``OperationRepository.claim_due_deliveries``
    docstring): the row stays ``received``/due and unlocked for a future claim
    attempt, rather than being force-failed by this generic primitive.
    """
    now = datetime.now(UTC)
    operation = _direct_operation(
        user_id="user-without-device", next_delivery_at=now - timedelta(seconds=5)
    )
    db_session.add(operation)
    await db_session.commit()

    repository = OperationRepository(db_session)
    claimed = await repository.claim_due_deliveries(
        now=now, lease_until=now + timedelta(minutes=5), limit=10
    )
    await db_session.commit()

    assert claimed == []
    unchanged = await repository.get_by_request_id(
        client_id=operation.client_id, request_id=operation.request_id
    )
    assert unchanged is not None
    assert unchanged.delivery_state is DeliveryState.RECEIVED
    assert unchanged.delivery_locked_until is None
    assert unchanged.delivery_attempts == 0
    assert unchanged.device_id is None


# ---------------------------------------------------------------------------
# record_delivery_sent: happy path and stale-lease rejection
# ---------------------------------------------------------------------------


async def test_record_delivery_sent_happy_path_clears_lease_and_next_delivery(
    db_session: AsyncSession,
) -> None:
    """A matching lease moves to ``sent`` and clears lease/next-delivery, not request_state."""
    now = datetime.now(UTC)
    user_id = "user-sent-happy"
    await _register_active_device(db_session, user_id=user_id, device_id="device-sent-happy")
    operation = _direct_operation(user_id=user_id, next_delivery_at=now - timedelta(seconds=5))
    db_session.add(operation)
    await db_session.commit()

    repository = OperationRepository(db_session)
    lease_until = now + timedelta(minutes=5)
    claimed = await repository.claim_due_deliveries(now=now, lease_until=lease_until, limit=10)
    await db_session.commit()
    assert len(claimed) == 1

    affected = await repository.record_delivery_sent(
        request_id=operation.request_id, lease_until=lease_until
    )
    await db_session.commit()
    assert affected is True

    result = await repository.get_by_request_id(
        client_id=operation.client_id, request_id=operation.request_id
    )
    assert result is not None
    assert result.delivery_state is DeliveryState.SENT
    assert result.delivery_locked_until is None
    assert result.next_delivery_at is None
    assert result.request_state is RequestState.PROCESSING
    assert result.result is None
    assert result.error is None


async def test_record_delivery_sent_with_stale_lease_affects_zero_rows(
    db_session: AsyncSession,
) -> None:
    """A lease value that doesn't match the current lease is rejected as stale."""
    now = datetime.now(UTC)
    user_id = "user-sent-stale"
    await _register_active_device(db_session, user_id=user_id, device_id="device-sent-stale")
    operation = _direct_operation(user_id=user_id, next_delivery_at=now - timedelta(seconds=5))
    db_session.add(operation)
    await db_session.commit()

    repository = OperationRepository(db_session)
    lease_until = now + timedelta(minutes=5)
    await repository.claim_due_deliveries(now=now, lease_until=lease_until, limit=10)
    await db_session.commit()

    wrong_lease = lease_until + timedelta(seconds=1)
    affected = await repository.record_delivery_sent(
        request_id=operation.request_id, lease_until=wrong_lease
    )
    await db_session.commit()
    assert affected is False

    result = await repository.get_by_request_id(
        client_id=operation.client_id, request_id=operation.request_id
    )
    assert result is not None
    assert result.delivery_state is DeliveryState.SENDING
    assert result.delivery_locked_until == lease_until


# ---------------------------------------------------------------------------
# record_delivery_transient_failure: happy path and stale-lease rejection
# ---------------------------------------------------------------------------


async def test_record_delivery_transient_failure_happy_path_sets_retry_and_next_delivery(
    db_session: AsyncSession,
) -> None:
    """A matching lease transitions to ``retry`` with the caller-supplied ``next_delivery_at``."""
    now = datetime.now(UTC)
    user_id = "user-retry-happy"
    await _register_active_device(db_session, user_id=user_id, device_id="device-retry-happy")
    operation = _direct_operation(user_id=user_id, next_delivery_at=now - timedelta(seconds=5))
    db_session.add(operation)
    await db_session.commit()

    repository = OperationRepository(db_session)
    lease_until = now + timedelta(minutes=5)
    await repository.claim_due_deliveries(now=now, lease_until=lease_until, limit=10)
    await db_session.commit()

    next_delivery_at = now + timedelta(minutes=1)
    affected = await repository.record_delivery_transient_failure(
        request_id=operation.request_id, lease_until=lease_until, next_delivery_at=next_delivery_at
    )
    await db_session.commit()
    assert affected is True

    result = await repository.get_by_request_id(
        client_id=operation.client_id, request_id=operation.request_id
    )
    assert result is not None
    assert result.delivery_state is DeliveryState.RETRY
    assert result.next_delivery_at == next_delivery_at
    assert result.delivery_locked_until is None
    assert result.request_state is RequestState.PROCESSING


async def test_record_delivery_transient_failure_with_stale_lease_affects_zero_rows(
    db_session: AsyncSession,
) -> None:
    """A stale lease value leaves the row's ``sending`` state untouched."""
    now = datetime.now(UTC)
    user_id = "user-retry-stale"
    await _register_active_device(db_session, user_id=user_id, device_id="device-retry-stale")
    operation = _direct_operation(user_id=user_id, next_delivery_at=now - timedelta(seconds=5))
    db_session.add(operation)
    await db_session.commit()

    repository = OperationRepository(db_session)
    lease_until = now + timedelta(minutes=5)
    await repository.claim_due_deliveries(now=now, lease_until=lease_until, limit=10)
    await db_session.commit()

    wrong_lease = lease_until + timedelta(seconds=1)
    affected = await repository.record_delivery_transient_failure(
        request_id=operation.request_id,
        lease_until=wrong_lease,
        next_delivery_at=now + timedelta(minutes=1),
    )
    await db_session.commit()
    assert affected is False

    result = await repository.get_by_request_id(
        client_id=operation.client_id, request_id=operation.request_id
    )
    assert result is not None
    assert result.delivery_state is DeliveryState.SENDING
    assert result.delivery_locked_until == lease_until


# ---------------------------------------------------------------------------
# record_delivery_permanent_failure: happy path, stale lease, terminal-race guard
# ---------------------------------------------------------------------------


async def test_record_delivery_permanent_failure_happy_path_terminates_operation(
    db_session: AsyncSession,
) -> None:
    """A matching lease transitions delivery to ``failed`` and terminates the operation."""
    now = datetime.now(UTC)
    user_id = "user-permanent-happy"
    await _register_active_device(db_session, user_id=user_id, device_id="device-permanent-happy")
    operation = _direct_operation(user_id=user_id, next_delivery_at=now - timedelta(seconds=5))
    db_session.add(operation)
    await db_session.commit()

    repository = OperationRepository(db_session)
    lease_until = now + timedelta(minutes=5)
    await repository.claim_due_deliveries(now=now, lease_until=lease_until, limit=10)
    await db_session.commit()

    error_payload: JsonObject = {
        "code": "TARGET_UNAVAILABLE",
        "message": "device unreachable",
        "details": None,
    }
    affected = await repository.record_delivery_permanent_failure(
        request_id=operation.request_id, lease_until=lease_until, error=error_payload
    )
    await db_session.commit()
    assert affected is True

    result = await repository.get_by_request_id(
        client_id=operation.client_id, request_id=operation.request_id
    )
    assert result is not None
    assert result.delivery_state is DeliveryState.FAILED
    assert result.delivery_locked_until is None
    assert result.next_delivery_at is None
    assert result.request_state is RequestState.FAILED
    assert result.error == error_payload
    assert result.result is None
    assert result.completed_at is not None


async def test_record_delivery_permanent_failure_with_stale_lease_affects_zero_rows(
    db_session: AsyncSession,
) -> None:
    """A stale lease value leaves both delivery and request state untouched."""
    now = datetime.now(UTC)
    user_id = "user-permanent-stale"
    await _register_active_device(db_session, user_id=user_id, device_id="device-permanent-stale")
    operation = _direct_operation(user_id=user_id, next_delivery_at=now - timedelta(seconds=5))
    db_session.add(operation)
    await db_session.commit()

    repository = OperationRepository(db_session)
    lease_until = now + timedelta(minutes=5)
    await repository.claim_due_deliveries(now=now, lease_until=lease_until, limit=10)
    await db_session.commit()

    wrong_lease = lease_until + timedelta(seconds=1)
    affected = await repository.record_delivery_permanent_failure(
        request_id=operation.request_id,
        lease_until=wrong_lease,
        error={"code": "X", "message": "boom", "details": None},
    )
    await db_session.commit()
    assert affected is False

    result = await repository.get_by_request_id(
        client_id=operation.client_id, request_id=operation.request_id
    )
    assert result is not None
    assert result.delivery_state is DeliveryState.SENDING
    assert result.request_state is RequestState.PROCESSING
    assert result.error is None


async def test_record_delivery_permanent_failure_noops_when_request_already_terminal(
    db_session: AsyncSession,
) -> None:
    """A race against an already-terminal operation (e.g. a report won first) safely no-ops.

    Constructs the (otherwise-unreachable-through-normal-flow) edge case where
    ``delivery_state='sending'`` with a valid lease coexists with an already
    ``succeeded`` ``request_state``, simulating a report/timeout terminating
    the operation while a stale delivery lease was still technically valid.
    The permanent-failure guard's extra ``request_state='processing'``
    predicate must reject this rather than corrupt the terminal row.
    """
    now = datetime.now(UTC)
    lease_until = now + timedelta(minutes=5)
    operation = _direct_operation(
        user_id="user-race-terminal",
        delivery_state=DeliveryState.SENDING,
        delivery_locked_until=lease_until,
        request_state=RequestState.SUCCEEDED,
        result={"ok": True},
        completed_at=now,
    )
    db_session.add(operation)
    await db_session.commit()

    repository = OperationRepository(db_session)
    affected = await repository.record_delivery_permanent_failure(
        request_id=operation.request_id,
        lease_until=lease_until,
        error={"code": "X", "message": "boom", "details": None},
    )
    await db_session.commit()
    assert affected is False

    result = await repository.get_by_request_id(
        client_id=operation.client_id, request_id=operation.request_id
    )
    assert result is not None
    assert result.request_state is RequestState.SUCCEEDED
    assert result.result == {"ok": True}
    assert result.error is None
    assert result.delivery_state is DeliveryState.SENDING
