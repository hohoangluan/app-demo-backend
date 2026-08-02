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
from app.schemas.service_requests import (  # noqa: TC001
    ContactCallRequest,
    EmergencyCallRequest,
    MusicPlayRequest,
    MusicStopRequest,
    MusicVolumeRequest,
    NavigationStartRequest,
    NavigationStopRequest,
    RideConfirmRequest,
    RideQuoteRequest,
    ServiceRequest,
)
from app.services.operation import AcceptOperationStatus, OperationService

router = APIRouter(prefix="/api/v1/service", tags=["service"])

_REQUEST_ID_CONFLICT_MSG = "Existing request ID has different fingerprint or client"


async def _accept_operation(
    path: str,
    body: ServiceRequest,
    principal: ClientPrincipal,
    session: AsyncSession,
    settings: Settings,
) -> AcceptedResponse:
    """Accept an operation for a specific action route."""
    route = ACTION_ROUTE_MAP[(HttpMethod.POST, path)]
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
    """Accept or reuse a music volume change operation."""
    return await _accept_operation(
        "/api/v1/service/music/volume", body, principal, session, settings
    )


@router.post(
    "/emergency/call",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def post_emergency_call(
    body: EmergencyCallRequest,
    principal: Annotated[
        ClientPrincipal, Depends(require_public_scope(PublicApiScope.SERVICE_EXECUTE))
    ],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_app_settings)],
) -> AcceptedResponse:
    """Accept or reuse an emergency call operation."""
    return await _accept_operation(
        "/api/v1/service/emergency/call", body, principal, session, settings
    )


@router.post(
    "/contact/call",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def post_contact_call(
    body: ContactCallRequest,
    principal: Annotated[
        ClientPrincipal, Depends(require_public_scope(PublicApiScope.SERVICE_EXECUTE))
    ],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_app_settings)],
) -> AcceptedResponse:
    """Accept or reuse a contact call operation."""
    return await _accept_operation(
        "/api/v1/service/contact/call", body, principal, session, settings
    )


@router.post(
    "/music/play",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def post_music_play(
    body: MusicPlayRequest,
    principal: Annotated[
        ClientPrincipal, Depends(require_public_scope(PublicApiScope.SERVICE_EXECUTE))
    ],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_app_settings)],
) -> AcceptedResponse:
    """Accept or reuse a music play operation."""
    return await _accept_operation("/api/v1/service/music/play", body, principal, session, settings)


@router.post(
    "/music/stop",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def post_music_stop(
    body: MusicStopRequest,
    principal: Annotated[
        ClientPrincipal, Depends(require_public_scope(PublicApiScope.SERVICE_EXECUTE))
    ],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_app_settings)],
) -> AcceptedResponse:
    """Accept or reuse a music stop operation."""
    return await _accept_operation("/api/v1/service/music/stop", body, principal, session, settings)


@router.post(
    "/navigation/start",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def post_navigation_start(
    body: NavigationStartRequest,
    principal: Annotated[
        ClientPrincipal, Depends(require_public_scope(PublicApiScope.SERVICE_EXECUTE))
    ],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_app_settings)],
) -> AcceptedResponse:
    """Accept or reuse a navigation start operation."""
    return await _accept_operation(
        "/api/v1/service/navigation/start", body, principal, session, settings
    )


@router.post(
    "/navigation/stop",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def post_navigation_stop(
    body: NavigationStopRequest,
    principal: Annotated[
        ClientPrincipal, Depends(require_public_scope(PublicApiScope.SERVICE_EXECUTE))
    ],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_app_settings)],
) -> AcceptedResponse:
    """Accept or reuse a navigation stop operation."""
    return await _accept_operation(
        "/api/v1/service/navigation/stop", body, principal, session, settings
    )


@router.post(
    "/ride/quote",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def post_ride_quote(
    body: RideQuoteRequest,
    principal: Annotated[
        ClientPrincipal, Depends(require_public_scope(PublicApiScope.SERVICE_EXECUTE))
    ],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_app_settings)],
) -> AcceptedResponse:
    """Accept or reuse a ride quote operation."""
    return await _accept_operation("/api/v1/service/ride/quote", body, principal, session, settings)


@router.post(
    "/ride/confirm",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def post_ride_confirm(
    body: RideConfirmRequest,
    principal: Annotated[
        ClientPrincipal, Depends(require_public_scope(PublicApiScope.SERVICE_EXECUTE))
    ],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_app_settings)],
) -> AcceptedResponse:
    """Accept or reuse a ride confirm operation."""
    return await _accept_operation(
        "/api/v1/service/ride/confirm", body, principal, session, settings
    )
