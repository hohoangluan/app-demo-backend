"""Public function service router for accepting device control operations."""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession  # noqa: TC002

from app.actions import ACTION_ROUTE_MAP, HttpMethod
from app.auth import ClientPrincipal, require_public_scope
from app.config import PublicApiScope, Settings, get_app_settings
from app.database import get_db_session
from app.errors import RequestIdConflictError
from app.schemas.common import AcceptedData, AcceptedResponse
from app.schemas.service_requests import MusicVolumeRequest  # noqa: TC001
from app.services.operation import AcceptOperationStatus, OperationService

router = APIRouter(prefix="/api/v1/service", tags=["service"])

_REQUEST_ID_CONFLICT_MSG = "Existing request ID has different fingerprint or client"


@router.post(
    "/music/volume",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def post_music_volume(
    body: MusicVolumeRequest,
    principal: Annotated[
        ClientPrincipal, Depends(require_public_scope(PublicApiScope.SERVICE_EXECUTE))
    ],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_app_settings)],
) -> AcceptedResponse:
    """Accept or reuse a music volume change operation.

    Per ``docs/p1-api-plan.md`` and ``CONTRACT_DECISIONS.md`` ``D-10``, returns
    ``HTTP 202`` with status URL and original ``accepted_at`` timestamp.
    """
    route = ACTION_ROUTE_MAP[(HttpMethod.POST, "/api/v1/service/music/volume")]
    service = OperationService(session, settings)
    result = await service.accept(client_id=principal.client_id, route=route, request=body)

    if result.status is AcceptOperationStatus.CONFLICT:
        raise RequestIdConflictError(_REQUEST_ID_CONFLICT_MSG)

    if session is not None:
        await session.commit()

    status_url = f"/api/v1/requests/{body.request_id}"

    data = AcceptedData(
        request_id=body.request_id,
        operation=route.operation,
        status_url=status_url,
        accepted_at=result.operation.created_at,
    )
    return AcceptedResponse(data=data)
