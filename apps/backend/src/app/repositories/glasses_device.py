"""Glasses pairing persistence primitives: link/unlink/resolve.

Mirrors `DeviceRepository`'s shape (`app/repositories/device.py`): the
row for the given `device_id` is locked with `with_for_update` before any
branch decision, one active pairing per `user_id` is enforced by
deactivating siblings on insert, and this repository never calls
`session.commit()` -- the caller owns the transaction boundary.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from typing import TYPE_CHECKING, Any, cast

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.enums import GlassesLinkStatus
from app.models.glasses_device import GlassesDevice

if TYPE_CHECKING:
    from sqlalchemy.engine import CursorResult


@dataclass(frozen=True, slots=True)
class GlassesLinked:
    """A new `glasses_devices` row was inserted and activated for its user."""

    device: GlassesDevice


@dataclass(frozen=True, slots=True)
class GlassesRelinked:
    """The same `device_id`/`user_id` pairing was reactivated in place."""

    device: GlassesDevice


@dataclass(frozen=True, slots=True)
class GlassesOwnershipTransferred:
    """A previously-inactive pairing was reassigned to a new `user_id`."""

    device: GlassesDevice


@dataclass(frozen=True, slots=True)
class GlassesOwnerConflict:
    """The `device_id` is actively paired to a different `user_id`."""

    device_id: str
    existing_user_id: str


type GlassesLinkOutcome = (
    GlassesLinked | GlassesRelinked | GlassesOwnershipTransferred | GlassesOwnerConflict
)


class GlassesDeviceRepository:
    """Persistence primitives for `glasses_devices`: link, unlink, and resolve."""

    def __init__(self, session: AsyncSession) -> None:
        """Bind the repository to a caller-owned session/transaction."""
        self._session = session

    async def link(self, *, user_id: str, device_id: str) -> GlassesLinkOutcome:
        """Insert-or-relink one pairing row per the locked ownership policy.

        Does not commit; the caller owns the transaction boundary for this
        use case.
        """
        result = await self._session.execute(
            select(GlassesDevice).where(GlassesDevice.device_id == device_id).with_for_update()
        )
        existing = result.scalar_one_or_none()

        if existing is not None:
            return await self._relink_or_reject(existing, user_id=user_id)

        return await self._insert_and_deactivate_siblings(user_id=user_id, device_id=device_id)

    async def _relink_or_reject(
        self, existing: GlassesDevice, *, user_id: str
    ) -> GlassesLinkOutcome:
        """Apply the same-`device_id` branch: reactivate for the same/inactive owner."""
        now = datetime.now(UTC)

        if existing.user_id == user_id:
            existing.status = GlassesLinkStatus.ACTIVE
            existing.linked_at = now
            existing.updated_at = now
            await self._session.flush()
            return GlassesRelinked(device=existing)

        if existing.status is GlassesLinkStatus.ACTIVE:
            return GlassesOwnerConflict(
                device_id=existing.device_id, existing_user_id=existing.user_id
            )

        await self._deactivate_active_pairings_for_user(user_id)
        existing.user_id = user_id
        existing.status = GlassesLinkStatus.ACTIVE
        existing.linked_at = now
        existing.updated_at = now
        await self._session.flush()
        return GlassesOwnershipTransferred(device=existing)

    async def _insert_and_deactivate_siblings(
        self, *, user_id: str, device_id: str
    ) -> GlassesLinkOutcome:
        """Insert a brand-new active pairing and deactivate the user's other active rows."""
        await self._deactivate_active_pairings_for_user(user_id)

        now = datetime.now(UTC)
        new_device = GlassesDevice(
            user_id=user_id,
            device_id=device_id,
            status=GlassesLinkStatus.ACTIVE,
            linked_at=now,
        )
        self._session.add(new_device)
        await self._session.flush()
        return GlassesLinked(device=new_device)

    async def _deactivate_active_pairings_for_user(self, user_id: str) -> None:
        await self._session.execute(
            update(GlassesDevice)
            .where(
                GlassesDevice.user_id == user_id,
                GlassesDevice.status == GlassesLinkStatus.ACTIVE,
            )
            .values(status=GlassesLinkStatus.INACTIVE, updated_at=datetime.now(UTC))
        )

    async def unlink(self, *, user_id: str) -> bool:
        """Deactivate `user_id`'s active pairing, if any. Returns whether a row changed."""
        result = await self._session.execute(
            update(GlassesDevice)
            .where(
                GlassesDevice.user_id == user_id,
                GlassesDevice.status == GlassesLinkStatus.ACTIVE,
            )
            .values(status=GlassesLinkStatus.INACTIVE, updated_at=datetime.now(UTC))
        )
        return cast("CursorResult[Any]", result).rowcount > 0

    async def get_active_user_id(self, device_id: str) -> str | None:
        """Return the `user_id` actively paired to `device_id`, or `None`."""
        result = await self._session.execute(
            select(GlassesDevice.user_id).where(
                GlassesDevice.device_id == device_id,
                GlassesDevice.status == GlassesLinkStatus.ACTIVE,
            )
        )
        return result.scalar_one_or_none()

    async def get_active_device_id(self, user_id: str) -> str | None:
        """Return the glasses currently paired to ``user_id``, or ``None``."""
        result = await self._session.execute(
            select(GlassesDevice.device_id).where(
                GlassesDevice.user_id == user_id,
                GlassesDevice.status == GlassesLinkStatus.ACTIVE,
            )
        )
        return result.scalar_one_or_none()
