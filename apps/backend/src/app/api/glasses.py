"""Internal glasses-pairing router (/glasses/link and /glasses/unlink).

Gated by ``require_device_bearer_token`` -- the same shared secret used by
``/api/v1/device/register`` and ``/api/v1/device/report`` -- because the demo
Android app has no per-user session/login flow to call this with a
``UserPrincipal`` instead (see ``docs/superpowers/specs/
2026-08-03-glasses-pairing-design.md`` section 10 for the accepted trade-off).
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession  # noqa: TC002

from app.auth import require_device_bearer_token
from app.database import get_db_session
from app.repositories.glasses_device import GlassesDeviceRepository
from app.schemas.common import OkResponse
from app.schemas.glasses import (
    GlassesLinkData,
    GlassesLinkRequest,
    GlassesUnlinkData,
    GlassesUnlinkRequest,
)
from app.services.glasses import link_glasses_device, unlink_glasses_device

router = APIRouter(
    prefix="/api/v1/device/glasses",
    tags=["glasses"],
    dependencies=[Depends(require_device_bearer_token)],
)


@router.post("/link", response_model=OkResponse[GlassesLinkData])
async def link_glasses(
    body: GlassesLinkRequest,
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> OkResponse[GlassesLinkData]:
    """Pair a glasses device_id to the given app user_id."""
    repository = GlassesDeviceRepository(session)
    device = await link_glasses_device(repository, user_id=body.user_id, device_id=body.device_id)
    await session.commit()
    return OkResponse(data=GlassesLinkData(device_id=device.device_id, linked=True))


@router.post("/unlink", response_model=OkResponse[GlassesUnlinkData])
async def unlink_glasses(
    body: GlassesUnlinkRequest,
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> OkResponse[GlassesUnlinkData]:
    """Unpair whichever glasses device_id is currently active for user_id."""
    repository = GlassesDeviceRepository(session)
    changed = await unlink_glasses_device(repository, user_id=body.user_id)
    await session.commit()
    return OkResponse(data=GlassesUnlinkData(unlinked=changed))
