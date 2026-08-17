"""Glasses-pairing business rules: link, unlink, and Public API resolution.

Mirrors `services/auth.py`'s `confirm_device_link` boundary: computes the
outcome-to-error mapping and calls repository primitives, never builds an
HTTP response and never commits the caller's session.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.errors import GlassesDeviceNotLinkedError, GlassesDeviceOwnerConflictError
from app.repositories.glasses_device import GlassesOwnerConflict

if TYPE_CHECKING:
    from app.models.glasses_device import GlassesDevice
    from app.repositories.glasses_device import GlassesDeviceRepository

_OWNER_CONFLICT_MSG = "Glasses device_id is already linked to a different account"
_NOT_LINKED_MSG = "No active glasses pairing found for this device_id"


async def link_glasses_device(
    repository: GlassesDeviceRepository, *, user_id: str, device_id: str
) -> GlassesDevice:
    """Link `device_id` to `user_id`, raising on an active-owner conflict."""
    outcome = await repository.link(user_id=user_id, device_id=device_id)
    if isinstance(outcome, GlassesOwnerConflict):
        raise GlassesDeviceOwnerConflictError(_OWNER_CONFLICT_MSG, details={"device_id": device_id})
    return outcome.device


async def unlink_glasses_device(repository: GlassesDeviceRepository, *, user_id: str) -> bool:
    """Deactivate `user_id`'s active glasses pairing, if any."""
    return await repository.unlink(user_id=user_id)


async def resolve_glasses_device_owner(
    repository: GlassesDeviceRepository, *, device_id: str
) -> str:
    """Resolve a Public Service API `device_id` to its owning internal `user_id`."""
    user_id = await repository.get_active_user_id(device_id)
    if user_id is None:
        raise GlassesDeviceNotLinkedError(_NOT_LINKED_MSG, details={"device_id": device_id})
    return user_id
