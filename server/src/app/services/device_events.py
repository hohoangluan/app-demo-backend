"""Validate, redact, resolve and forward spontaneous Android events."""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.errors import DeviceEventForwardError, DeviceNotFoundError, GlassesDeviceNotLinkedError
from app.models.enums import DeviceStatus

if TYPE_CHECKING:
    from app.adapters.device_events import DeviceEventAdapter
    from app.repositories.device import DeviceRepository
    from app.repositories.glasses_device import GlassesDeviceRepository
    from app.repositories.user import UserRepository
    from app.schemas.device import DeviceEventRequest

_DEVICE_NOT_FOUND = "Active Android device not found"
_GLASSES_NOT_FOUND = "No active glasses pairing for this device"
_FORWARD_FAILED = "Could not forward device event"


class DeviceEventService:
    """Coordinate phone ownership, privacy policy and glasses forwarding."""

    def __init__(
        self,
        device_repository: DeviceRepository,
        glasses_repository: GlassesDeviceRepository,
        user_repository: UserRepository,
        adapter: DeviceEventAdapter,
    ) -> None:
        """Bind repositories and the outbound adapter for one request."""
        self._devices = device_repository
        self._glasses = glasses_repository
        self._users = user_repository
        self._adapter = adapter

    async def forward(self, event: DeviceEventRequest) -> str:
        """Forward an event and return its resolved glasses device id."""
        phone = await self._devices.get_by_device_id(str(event.device_id))
        if phone is None or phone.status is not DeviceStatus.ACTIVE:
            raise DeviceNotFoundError(_DEVICE_NOT_FOUND)
        glasses_device_id = await self._glasses.get_active_device_id(phone.user_id)
        if glasses_device_id is None:
            raise GlassesDeviceNotLinkedError(_GLASSES_NOT_FOUND)

        payload: dict[str, object] = {
            "device_id": glasses_device_id,
            "type": event.type,
        }
        if event.type == "call_incoming":
            caller = event.caller.model_dump(exclude_none=True) if event.caller else {}
            user = await self._users.get_by_public_user_id(phone.user_id)
            policy = user.announce_caller if user is not None else "name"
            if policy == "ring_only":
                caller = {}
            elif policy == "number_only":
                caller = {
                    key: value
                    for key, value in caller.items()
                    if key in {"contact_id", "number_tail"}
                }
            payload["caller"] = caller

        try:
            await self._adapter.forward(payload)
        except Exception as exc:
            raise DeviceEventForwardError(_FORWARD_FAILED) from exc
        return glasses_device_id
