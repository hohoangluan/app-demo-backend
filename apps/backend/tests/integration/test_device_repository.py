"""PostgreSQL integration tests for `DeviceRepository` (task `P1-DB-06`).

Covers register/rotate outcomes for the locked D-05 enrollment policy and the
`devices` rows of the integration test matrix (`docs/p1-database-plan.md`):
`PG-03` (unique `device_id`), `PG-04` (latest-active resolver), and `PG-05`
(named CHECK constraints).
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING
from uuid import uuid4

import pytest
from sqlalchemy import insert, select
from sqlalchemy.exc import IntegrityError

from app.models.device import Device
from app.models.enums import DevicePlatform, DeviceStatus
from app.repositories.device import (
    DeviceIsRevoked,
    DeviceOwnerMismatch,
    DeviceRegistered,
    DeviceRegistrationRequest,
    DeviceRepository,
    DeviceRotated,
)

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.database import AsyncSessionFactory


def _registration_request(
    *,
    user_id: str,
    device_id: str,
    fingerprint_seed: str = "a",
    last_seen_at: datetime | None = None,
) -> DeviceRegistrationRequest:
    """Build a `DeviceRegistrationRequest` with a distinct deterministic fingerprint."""
    return DeviceRegistrationRequest(
        user_id=user_id,
        device_id=device_id,
        platform=DevicePlatform.ANDROID,
        push_token_ciphertext=f"ciphertext-{fingerprint_seed}",
        push_token_fingerprint=fingerprint_seed * 64,
        last_seen_at=last_seen_at or datetime.now(UTC),
    )


async def _get_by_device_id(session_factory: AsyncSessionFactory, device_id: str) -> Device | None:
    """Fetch a device row through a brand-new session, bypassing any identity-map cache."""
    async with session_factory() as session:
        result = await session.execute(select(Device).where(Device.device_id == device_id))
        return result.scalar_one_or_none()


async def test_register_new_device_deactivates_previous_active_device_for_same_user(
    db_session: AsyncSession,
    postgres_session_factory: AsyncSessionFactory,
) -> None:
    """Registering device B for a user deactivates that user's previously active device A."""
    repo = DeviceRepository(db_session)

    outcome_a = await repo.register(
        _registration_request(user_id="user-1", device_id="device-a", fingerprint_seed="a")
    )
    await db_session.commit()
    assert isinstance(outcome_a, DeviceRegistered)

    outcome_b = await repo.register(
        _registration_request(user_id="user-1", device_id="device-b", fingerprint_seed="b")
    )
    await db_session.commit()
    assert isinstance(outcome_b, DeviceRegistered)

    device_a = await _get_by_device_id(postgres_session_factory, "device-a")
    device_b = await _get_by_device_id(postgres_session_factory, "device-b")
    assert device_a is not None
    assert device_b is not None
    assert device_a.status is DeviceStatus.INACTIVE
    assert device_b.status is DeviceStatus.ACTIVE


async def test_register_same_device_id_and_user_rotates_token_and_last_seen(
    db_session: AsyncSession,
    postgres_session_factory: AsyncSessionFactory,
) -> None:
    """Re-registering the same device_id/user_id rotates the token in place, idempotently."""
    repo = DeviceRepository(db_session)
    first_seen = datetime.now(UTC) - timedelta(hours=1)

    first_outcome = await repo.register(
        _registration_request(
            user_id="user-1", device_id="device-a", fingerprint_seed="a", last_seen_at=first_seen
        )
    )
    await db_session.commit()
    assert isinstance(first_outcome, DeviceRegistered)

    rotated_at = datetime.now(UTC)
    second_outcome = await repo.register(
        _registration_request(
            user_id="user-1",
            device_id="device-a",
            fingerprint_seed="b",
            last_seen_at=rotated_at,
        )
    )
    await db_session.commit()

    assert isinstance(second_outcome, DeviceRotated)
    assert second_outcome.device.push_token_fingerprint == "b" * 64
    assert second_outcome.device.status is DeviceStatus.ACTIVE

    stored = await _get_by_device_id(postgres_session_factory, "device-a")
    assert stored is not None
    assert stored.push_token_fingerprint == "b" * 64
    assert stored.push_token_ciphertext == "ciphertext-b"
    assert stored.last_seen_at == rotated_at
    assert stored.status is DeviceStatus.ACTIVE


async def test_register_rejects_owner_mismatch_for_existing_device_id(
    db_session: AsyncSession,
    postgres_session_factory: AsyncSessionFactory,
) -> None:
    """A different user_id registering an existing device_id is rejected, not reassigned."""
    repo = DeviceRepository(db_session)
    await repo.register(
        _registration_request(user_id="user-1", device_id="device-a", fingerprint_seed="a")
    )
    await db_session.commit()

    outcome = await repo.register(
        _registration_request(user_id="user-2", device_id="device-a", fingerprint_seed="b")
    )
    await db_session.rollback()

    assert outcome == DeviceOwnerMismatch(device_id="device-a", existing_user_id="user-1")

    stored = await _get_by_device_id(postgres_session_factory, "device-a")
    assert stored is not None
    assert stored.user_id == "user-1"
    assert stored.push_token_fingerprint == "a" * 64


async def test_register_rejects_registration_for_revoked_device(
    db_session: AsyncSession,
    postgres_session_factory: AsyncSessionFactory,
) -> None:
    """A revoked device_id must not accept a new registration, even from its own owner."""
    now = datetime.now(UTC)
    db_session.add(
        Device(
            user_id="user-1",
            device_id="device-a",
            platform=DevicePlatform.ANDROID,
            push_token_ciphertext="original-ciphertext",
            push_token_fingerprint="a" * 64,
            status=DeviceStatus.REVOKED,
            last_seen_at=now,
        )
    )
    await db_session.commit()

    repo = DeviceRepository(db_session)
    outcome = await repo.register(
        _registration_request(user_id="user-1", device_id="device-a", fingerprint_seed="b")
    )
    await db_session.rollback()

    assert outcome == DeviceIsRevoked(device_id="device-a")

    stored = await _get_by_device_id(postgres_session_factory, "device-a")
    assert stored is not None
    assert stored.status is DeviceStatus.REVOKED
    assert stored.push_token_fingerprint == "a" * 64


async def test_get_latest_active_device_returns_none_when_user_has_no_active_device(
    db_session: AsyncSession,
) -> None:
    """The resolver returns None for a user with no rows at all."""
    repo = DeviceRepository(db_session)
    assert await repo.get_latest_active_device("user-none") is None


async def test_get_latest_active_device_ignores_inactive_revoked_and_other_users(
    db_session: AsyncSession,
) -> None:
    """The resolver picks only the newest active row for the requested user (`PG-04`).

    Hand-inserts rows directly rather than going through `register`, so the
    resolver is proven independently of the register/deactivate transaction.
    """
    now = datetime.now(UTC)
    db_session.add_all(
        [
            Device(
                user_id="user-1",
                device_id="device-older-active",
                platform=DevicePlatform.ANDROID,
                push_token_ciphertext="c1",
                push_token_fingerprint="a" * 64,
                status=DeviceStatus.ACTIVE,
                last_seen_at=now - timedelta(hours=2),
            ),
            Device(
                user_id="user-1",
                device_id="device-newer-active",
                platform=DevicePlatform.ANDROID,
                push_token_ciphertext="c2",
                push_token_fingerprint="b" * 64,
                status=DeviceStatus.ACTIVE,
                last_seen_at=now - timedelta(minutes=5),
            ),
            Device(
                user_id="user-1",
                device_id="device-newest-inactive",
                platform=DevicePlatform.ANDROID,
                push_token_ciphertext="c3",
                push_token_fingerprint="c" * 64,
                status=DeviceStatus.INACTIVE,
                last_seen_at=now,
            ),
            Device(
                user_id="user-1",
                device_id="device-newest-revoked",
                platform=DevicePlatform.ANDROID,
                push_token_ciphertext="c4",
                push_token_fingerprint="d" * 64,
                status=DeviceStatus.REVOKED,
                last_seen_at=now,
            ),
            Device(
                user_id="user-2",
                device_id="device-other-user-active",
                platform=DevicePlatform.ANDROID,
                push_token_ciphertext="c5",
                push_token_fingerprint="e" * 64,
                status=DeviceStatus.ACTIVE,
                last_seen_at=now,
            ),
        ]
    )
    await db_session.commit()

    repo = DeviceRepository(db_session)
    resolved = await repo.get_latest_active_device("user-1")

    assert resolved is not None
    assert resolved.device_id == "device-newer-active"


async def test_duplicate_device_id_violates_unique_constraint_without_corrupting_next_session(
    db_session: AsyncSession,
    postgres_session_factory: AsyncSessionFactory,
) -> None:
    """`uq_devices_device_id` rejects a raw duplicate insert (`PG-03`).

    A fresh, independent session proves the failed transaction does not
    corrupt subsequent database use.
    """
    now = datetime.now(UTC)
    db_session.add(
        Device(
            user_id="user-1",
            device_id="device-dup",
            platform=DevicePlatform.ANDROID,
            push_token_ciphertext="c1",
            push_token_fingerprint="a" * 64,
            status=DeviceStatus.ACTIVE,
            last_seen_at=now,
        )
    )
    await db_session.commit()

    db_session.add(
        Device(
            user_id="user-2",
            device_id="device-dup",
            platform=DevicePlatform.ANDROID,
            push_token_ciphertext="c2",
            push_token_fingerprint="b" * 64,
            status=DeviceStatus.ACTIVE,
            last_seen_at=now,
        )
    )
    with pytest.raises(IntegrityError, match="uq_devices_device_id"):
        await db_session.commit()
    await db_session.rollback()

    async with postgres_session_factory() as fresh_session:
        fresh_session.add(
            Device(
                user_id="user-3",
                device_id="device-independent",
                platform=DevicePlatform.ANDROID,
                push_token_ciphertext="c3",
                push_token_fingerprint="c" * 64,
                status=DeviceStatus.ACTIVE,
                last_seen_at=now,
            )
        )
        await fresh_session.commit()

        stored = await fresh_session.scalar(
            select(Device).where(Device.device_id == "device-independent")
        )
        assert stored is not None


async def test_invalid_platform_value_is_rejected_by_check_constraint(
    db_session: AsyncSession,
) -> None:
    """`ck_devices_platform` rejects a platform value outside the allowed set (`PG-05`)."""
    now = datetime.now(UTC)
    statement = insert(Device).values(
        id=uuid4(),
        user_id="user-1",
        device_id="device-bad-platform",
        platform="ios",
        push_token_ciphertext="c",
        push_token_fingerprint="a" * 64,
        status="active",
        last_seen_at=now,
    )
    with pytest.raises(IntegrityError, match="ck_devices_platform"):
        await db_session.execute(statement)
    await db_session.rollback()


async def test_invalid_status_value_is_rejected_by_check_constraint(
    db_session: AsyncSession,
) -> None:
    """`ck_devices_status` rejects a status value outside the allowed set (`PG-05`)."""
    now = datetime.now(UTC)
    statement = insert(Device).values(
        id=uuid4(),
        user_id="user-1",
        device_id="device-bad-status",
        platform="android",
        push_token_ciphertext="c",
        push_token_fingerprint="a" * 64,
        status="pending",
        last_seen_at=now,
    )
    with pytest.raises(IntegrityError, match="ck_devices_status"):
        await db_session.execute(statement)
    await db_session.rollback()


async def test_short_push_token_fingerprint_is_rejected_by_check_constraint(
    db_session: AsyncSession,
) -> None:
    """`ck_devices_push_token_fingerprint` rejects a fingerprint shorter than 64 (`PG-05`)."""
    now = datetime.now(UTC)
    db_session.add(
        Device(
            user_id="user-1",
            device_id="device-bad-fingerprint",
            platform=DevicePlatform.ANDROID,
            push_token_ciphertext="c",
            push_token_fingerprint="short",
            status=DeviceStatus.ACTIVE,
            last_seen_at=now,
        )
    )
    with pytest.raises(IntegrityError, match="ck_devices_push_token_fingerprint"):
        await db_session.commit()
    await db_session.rollback()
