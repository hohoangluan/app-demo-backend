"""PostgreSQL integration tests for `GlassesDeviceRepository`.

Covers the pairing behavior confirmed in
`docs/superpowers/specs/2026-08-03-glasses-pairing-design.md` section 4/9:
new link, idempotent relink, one-active-pairing-per-user replacement,
owner-conflict rejection, ownership transfer after unlink, and unlink itself.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import TYPE_CHECKING

from sqlalchemy import select

from app.models.enums import GlassesLinkStatus
from app.models.glasses_device import GlassesDevice
from app.repositories.glasses_device import (
    GlassesDeviceRepository,
    GlassesLinked,
    GlassesOwnerConflict,
    GlassesOwnershipTransferred,
    GlassesRelinked,
)

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.database import AsyncSessionFactory


async def _get_by_device_id(
    session_factory: AsyncSessionFactory, device_id: str
) -> GlassesDevice | None:
    """Fetch a glasses_devices row through a brand-new session."""
    async with session_factory() as session:
        result = await session.execute(
            select(GlassesDevice).where(GlassesDevice.device_id == device_id)
        )
        return result.scalar_one_or_none()


async def test_link_new_device_id_creates_active_pairing(
    db_session: AsyncSession,
) -> None:
    """Linking an unseen device_id inserts a new active pairing."""
    repo = GlassesDeviceRepository(db_session)

    outcome = await repo.link(user_id="user-1", device_id="glasses-a")
    await db_session.commit()

    assert isinstance(outcome, GlassesLinked)
    assert outcome.device.user_id == "user-1"
    assert outcome.device.status is GlassesLinkStatus.ACTIVE


async def test_link_new_device_deactivates_users_previous_active_pairing(
    db_session: AsyncSession,
    postgres_session_factory: AsyncSessionFactory,
) -> None:
    """Linking glasses B for a user deactivates that user's previously active glasses A."""
    repo = GlassesDeviceRepository(db_session)

    await repo.link(user_id="user-1", device_id="glasses-a")
    await db_session.commit()
    await repo.link(user_id="user-1", device_id="glasses-b")
    await db_session.commit()

    glasses_a = await _get_by_device_id(postgres_session_factory, "glasses-a")
    glasses_b = await _get_by_device_id(postgres_session_factory, "glasses-b")
    assert glasses_a is not None
    assert glasses_b is not None
    assert glasses_a.status is GlassesLinkStatus.INACTIVE
    assert glasses_b.status is GlassesLinkStatus.ACTIVE


async def test_link_same_device_and_user_is_idempotent(
    db_session: AsyncSession,
) -> None:
    """Re-linking the same device_id/user_id reactivates in place, not an error."""
    repo = GlassesDeviceRepository(db_session)

    await repo.link(user_id="user-1", device_id="glasses-a")
    await db_session.commit()

    outcome = await repo.link(user_id="user-1", device_id="glasses-a")
    await db_session.commit()

    assert isinstance(outcome, GlassesRelinked)
    assert outcome.device.status is GlassesLinkStatus.ACTIVE


async def test_link_rejects_conflict_for_device_id_active_under_different_user(
    db_session: AsyncSession,
    postgres_session_factory: AsyncSessionFactory,
) -> None:
    """A different user linking an actively-paired device_id is rejected, not reassigned."""
    repo = GlassesDeviceRepository(db_session)

    await repo.link(user_id="user-1", device_id="glasses-a")
    await db_session.commit()

    outcome = await repo.link(user_id="user-2", device_id="glasses-a")
    await db_session.rollback()

    assert outcome == GlassesOwnerConflict(device_id="glasses-a", existing_user_id="user-1")

    stored = await _get_by_device_id(postgres_session_factory, "glasses-a")
    assert stored is not None
    assert stored.user_id == "user-1"


async def test_link_transfers_ownership_when_existing_pairing_is_inactive(
    db_session: AsyncSession,
    postgres_session_factory: AsyncSessionFactory,
) -> None:
    """A device_id unlinked by its previous owner can be linked by a new user."""
    repo = GlassesDeviceRepository(db_session)

    await repo.link(user_id="user-1", device_id="glasses-a")
    await db_session.commit()
    await repo.unlink(user_id="user-1")
    await db_session.commit()

    outcome = await repo.link(user_id="user-2", device_id="glasses-a")
    await db_session.commit()

    assert isinstance(outcome, GlassesOwnershipTransferred)
    assert outcome.device.user_id == "user-2"
    assert outcome.device.status is GlassesLinkStatus.ACTIVE

    stored = await _get_by_device_id(postgres_session_factory, "glasses-a")
    assert stored is not None
    assert stored.user_id == "user-2"


async def test_unlink_deactivates_active_pairing(
    db_session: AsyncSession,
    postgres_session_factory: AsyncSessionFactory,
) -> None:
    """Unlinking a user with an active pairing deactivates it and reports a change."""
    repo = GlassesDeviceRepository(db_session)
    await repo.link(user_id="user-1", device_id="glasses-a")
    await db_session.commit()

    changed = await repo.unlink(user_id="user-1")
    await db_session.commit()

    assert changed is True
    stored = await _get_by_device_id(postgres_session_factory, "glasses-a")
    assert stored is not None
    assert stored.status is GlassesLinkStatus.INACTIVE


async def test_unlink_is_noop_when_nothing_linked(
    db_session: AsyncSession,
) -> None:
    """Unlinking a user with no active pairing is idempotent, not an error."""
    repo = GlassesDeviceRepository(db_session)

    changed = await repo.unlink(user_id="user-none")
    await db_session.commit()

    assert changed is False


async def test_get_active_user_id_returns_none_when_no_active_pairing(
    db_session: AsyncSession,
) -> None:
    """The resolver used by the Public Service API returns None for an unpaired device_id."""
    repo = GlassesDeviceRepository(db_session)

    assert await repo.get_active_user_id("glasses-unknown") is None


async def test_get_active_user_id_ignores_inactive_pairing(
    db_session: AsyncSession,
) -> None:
    """The resolver never returns a user_id for an inactive (unlinked) pairing."""
    now = datetime.now(UTC)
    db_session.add(
        GlassesDevice(
            user_id="user-1",
            device_id="glasses-a",
            status=GlassesLinkStatus.INACTIVE,
            linked_at=now,
        )
    )
    await db_session.commit()

    repo = GlassesDeviceRepository(db_session)
    assert await repo.get_active_user_id("glasses-a") is None


async def test_get_active_user_id_returns_owner_for_active_pairing(
    db_session: AsyncSession,
) -> None:
    """The resolver returns the owning user_id for an active pairing."""
    repo = GlassesDeviceRepository(db_session)
    await repo.link(user_id="user-1", device_id="glasses-a")
    await db_session.commit()

    assert await repo.get_active_user_id("glasses-a") == "user-1"
