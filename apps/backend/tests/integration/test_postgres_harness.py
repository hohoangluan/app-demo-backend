"""Smoke tests proving the PostgreSQL integration-test harness works end to end."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING
from uuid import uuid4

import pytest
from sqlalchemy import text

from app.actions import Action
from app.actions import Operation as ActionOperation
from app.models.device import Device
from app.models.enums import CallbackState, DevicePlatform, DeviceStatus, RequestState
from app.models.operation import Operation
from tests.integration.conftest import NonTestDatabaseUrlError, ensure_test_only_database_url

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession


def test_guard_rejects_a_non_test_looking_database_url() -> None:
    """Refuse a URL whose database name does not contain 'test'."""
    with pytest.raises(NonTestDatabaseUrlError):
        ensure_test_only_database_url(
            "postgresql+asyncpg://app_demo:app_demo@127.0.0.1:5432/app_demo"
        )


def test_guard_accepts_a_test_looking_database_url() -> None:
    """Allow a database name that clearly contains 'test'."""
    ensure_test_only_database_url(
        "postgresql+asyncpg://app_demo_test:app_demo_test@127.0.0.1:57432/app_demo_test"
    )


async def test_migration_creates_devices_and_operations_tables(
    postgres_engine: AsyncEngine,
) -> None:
    """Assert the migrated schema has both tables with key columns and checks."""
    async with postgres_engine.connect() as connection:
        table_names = (
            (
                await connection.execute(
                    text(
                        "SELECT table_name FROM information_schema.tables "
                        "WHERE table_schema = 'public' "
                        "AND table_name IN ('devices', 'operations')"
                    )
                )
            )
            .scalars()
            .all()
        )
        assert set(table_names) == {"devices", "operations"}

        fingerprint_column = (
            await connection.execute(
                text(
                    "SELECT data_type, character_maximum_length "
                    "FROM information_schema.columns "
                    "WHERE table_name = 'devices' AND column_name = 'push_token_fingerprint'"
                )
            )
        ).one()
        assert fingerprint_column.data_type == "character"
        assert fingerprint_column.character_maximum_length == 64

        constraint_names = (
            (
                await connection.execute(
                    text(
                        "SELECT conname FROM pg_constraint WHERE conname IN ("
                        "'ck_operations_terminal_payload', 'ck_operations_mapping', "
                        "'pk_operations', 'pk_devices', 'uq_devices_device_id')"
                    )
                )
            )
            .scalars()
            .all()
        )
        assert set(constraint_names) == {
            "ck_operations_terminal_payload",
            "ck_operations_mapping",
            "pk_operations",
            "pk_devices",
            "uq_devices_device_id",
        }


async def test_insert_minimal_valid_device_and_operation_rows(
    db_session: AsyncSession,
) -> None:
    """Insert one valid row per table, respecting every CHECK constraint."""
    now = datetime.now(UTC)
    device = Device(
        user_id="user-1",
        device_id=f"device-{uuid4()}",
        platform=DevicePlatform.ANDROID,
        push_token_ciphertext="ciphertext",
        push_token_fingerprint="a" * 64,
        status=DeviceStatus.ACTIVE,
        last_seen_at=now,
    )
    operation = Operation(
        request_id=uuid4(),
        client_id="client-1",
        user_id="user-1",
        operation=ActionOperation.RIDE_QUOTE,
        action=Action.RIDE_QUOTE,
        params={},
        request_fingerprint="b" * 64,
        request_state=RequestState.PROCESSING,
        callback_state=CallbackState.NOT_REQUIRED,
        expires_at=now + timedelta(seconds=60),
    )

    db_session.add_all([device, operation])
    await db_session.commit()

    stored_device = await db_session.get(Device, device.id)
    stored_operation = await db_session.get(Operation, operation.request_id)

    assert stored_device is not None
    assert stored_device.push_token_fingerprint == "a" * 64
    assert stored_operation is not None
    assert stored_operation.params == {}
    assert stored_operation.request_state is RequestState.PROCESSING
