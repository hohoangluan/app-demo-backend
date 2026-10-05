"""Persistence for phones (``devices``): register/rotate and find the active one.

Re-registering the same device for the same user rotates its push token;
moving a device to another user or reviving a revoked one is refused; a new
active device deactivates the user's other devices. The caller commits.
"""

from dataclasses import dataclass
from datetime import UTC, datetime

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.device import Device
from app.models.enums import DevicePlatform, DeviceStatus


@dataclass(frozen=True, slots=True)
class DeviceRegistrationRequest:
    """Input for `DeviceRepository.register`."""

    user_id: str
    device_id: str
    platform: DevicePlatform
    push_token_ciphertext: str
    push_token_fingerprint: str
    last_seen_at: datetime


@dataclass(frozen=True, slots=True)
class DeviceRegistered:
    """A new device row was inserted and activated for its user."""

    device: Device


@dataclass(frozen=True, slots=True)
class DeviceRotated:
    """An existing device row for the same user/device_id was refreshed in place."""

    device: Device


@dataclass(frozen=True, slots=True)
class DeviceOwnerMismatch:
    """The `device_id` is already registered to a different `user_id`."""

    device_id: str
    existing_user_id: str


@dataclass(frozen=True, slots=True)
class DeviceIsRevoked:
    """The `device_id` exists but is revoked and must not accept registration."""

    device_id: str


type DeviceRegisterOutcome = (
    DeviceRegistered | DeviceRotated | DeviceOwnerMismatch | DeviceIsRevoked
)


class DeviceRepository:
    """Persistence primitives for `devices`: register/rotate and resolve latest active."""

    def __init__(self, session: AsyncSession) -> None:
        """Bind the repository to a caller-owned session/transaction."""
        self._session = session

    async def register(self, request: DeviceRegistrationRequest) -> DeviceRegisterOutcome:
        """Insert or rotate one device row (see module docstring); does not commit."""
        result = await self._session.execute(
            select(Device).where(Device.device_id == request.device_id).with_for_update()
        )
        existing = result.scalar_one_or_none()

        if existing is not None:
            return await self._rotate_or_reject(existing, request)

        return await self._insert_and_deactivate_siblings(request)

    async def _rotate_or_reject(
        self, existing: Device, request: DeviceRegistrationRequest
    ) -> DeviceRegisterOutcome:
        """Apply the same-`device_id` branch: rotate for the same owner, else reject."""
        if existing.user_id != request.user_id:
            return DeviceOwnerMismatch(
                device_id=request.device_id, existing_user_id=existing.user_id
            )
        if existing.status is DeviceStatus.REVOKED:
            return DeviceIsRevoked(device_id=request.device_id)

        existing.push_token_ciphertext = request.push_token_ciphertext
        existing.push_token_fingerprint = request.push_token_fingerprint
        existing.last_seen_at = request.last_seen_at
        existing.status = DeviceStatus.ACTIVE
        existing.updated_at = datetime.now(UTC)
        await self._session.flush()
        return DeviceRotated(device=existing)

    async def _insert_and_deactivate_siblings(
        self, request: DeviceRegistrationRequest
    ) -> DeviceRegisterOutcome:
        """Insert a brand-new active device row and deactivate the user's other active rows."""
        await self._session.execute(
            update(Device)
            .where(Device.user_id == request.user_id, Device.status == DeviceStatus.ACTIVE)
            .values(status=DeviceStatus.INACTIVE, updated_at=datetime.now(UTC))
        )

        new_device = Device(
            user_id=request.user_id,
            device_id=request.device_id,
            platform=request.platform,
            push_token_ciphertext=request.push_token_ciphertext,
            push_token_fingerprint=request.push_token_fingerprint,
            status=DeviceStatus.ACTIVE,
            last_seen_at=request.last_seen_at,
        )
        self._session.add(new_device)
        await self._session.flush()
        return DeviceRegistered(device=new_device)

    async def get_by_device_id(self, device_id: str) -> Device | None:
        """Return the device row for `device_id` regardless of status, or `None`."""
        result = await self._session.execute(select(Device).where(Device.device_id == device_id))
        return result.scalar_one_or_none()

    async def get_latest_active_device(self, user_id: str) -> Device | None:
        """Return the most-recently-seen active device for `user_id`, or `None`.

        Queries in the `(user_id, status, last_seen_at DESC)` shape served by
        `ix_devices_resolver`.
        """
        result = await self._session.execute(
            select(Device)
            .where(Device.user_id == user_id, Device.status == DeviceStatus.ACTIVE)
            .order_by(Device.last_seen_at.desc())
            .limit(1)
        )
        return result.scalar_one_or_none()
