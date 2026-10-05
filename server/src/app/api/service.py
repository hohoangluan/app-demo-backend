"""Public function endpoints: validate the body, accept it as an operation, return 202."""

from __future__ import annotations

from typing import Annotated, Any, Final

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession  # noqa: TC002

from app.actions import Action
from app.adapters.music_catalog import SpotifyCatalog
from app.auth import ClientPrincipal, require_public_scope
from app.config import PublicApiScope, Settings, get_app_settings
from app.database import get_db_session
from app.schemas.common import AcceptedData, AcceptedResponse
from app.schemas.service_requests import (  # noqa: TC001
    CallAnswerRequest,
    CallRejectRequest,
    CapabilitiesGetRequest,
    ContactCallRequest,
    EmergencyCallRequest,
    LocationGetRequest,
    MusicPlayRequest,
    MusicStopRequest,
    MusicVolumeRequest,
    NavigationStartRequest,
    NavigationStopRequest,
    RideConfirmRequest,
    RideQuoteRequest,
    ServiceRequest,
)
from app.services.operation import OperationService
from app.workers.wake import WorkerWakeSignals, get_worker_wake_signals

router = APIRouter(prefix="/api/v1/service", tags=["service"])


def get_operation_service(
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_app_settings)],
) -> OperationService:
    """Build the per-request operation service."""
    return OperationService(session, settings, SpotifyCatalog(settings))


class Acceptor:
    """Everything a function endpoint needs to accept one request."""

    def __init__(
        self,
        principal: Annotated[
            ClientPrincipal, Depends(require_public_scope(PublicApiScope.SERVICE_EXECUTE))
        ],
        service: Annotated[OperationService, Depends(get_operation_service)],
        wake_signals: Annotated[WorkerWakeSignals, Depends(get_worker_wake_signals)],
    ) -> None:
        """Collect the authenticated client, the service and the worker wake-up signal."""
        self._principal = principal
        self._service = service
        self._wake_signals = wake_signals

    async def __call__(self, action: Action, body: ServiceRequest) -> AcceptedResponse:
        """Persist the request, wake the delivery worker and build the 202 envelope."""
        operation = await self._service.submit(
            client_id=self._principal.client_id, action=action, request=body
        )
        self._wake_signals.delivery.set()
        return AcceptedResponse(
            data=AcceptedData(
                request_id=operation.request_id,
                operation=operation.operation,
                status_url=f"/api/v1/requests/{operation.request_id}",
                accepted_at=operation.created_at,
            )
        )


Accept = Annotated[Acceptor, Depends()]
_ACCEPTED: Final[dict[str, Any]] = {
    "response_model": AcceptedResponse,
    "status_code": status.HTTP_202_ACCEPTED,
}


@router.post("/ride/quote", **_ACCEPTED)
async def post_ride_quote(body: RideQuoteRequest, accept: Accept) -> AcceptedResponse:
    """Ask the phone for a ride quote."""
    return await accept(Action.RIDE_QUOTE, body)


@router.post("/ride/confirm", **_ACCEPTED)
async def post_ride_confirm(body: RideConfirmRequest, accept: Accept) -> AcceptedResponse:
    """Confirm or cancel a ride quote."""
    return await accept(Action.RIDE_CONFIRM, body)


@router.post("/music/play", **_ACCEPTED)
async def post_music_play(body: MusicPlayRequest, accept: Accept) -> AcceptedResponse:
    """Play a song."""
    return await accept(Action.MUSIC_PLAY, body)


@router.post("/music/stop", **_ACCEPTED)
async def post_music_stop(body: MusicStopRequest, accept: Accept) -> AcceptedResponse:
    """Stop music playback."""
    return await accept(Action.MUSIC_STOP, body)


@router.post("/music/volume", **_ACCEPTED)
async def post_music_volume(body: MusicVolumeRequest, accept: Accept) -> AcceptedResponse:
    """Change the music volume."""
    return await accept(Action.MUSIC_VOLUME, body)


@router.post("/navigation/start", **_ACCEPTED)
async def post_navigation_start(body: NavigationStartRequest, accept: Accept) -> AcceptedResponse:
    """Start walking navigation."""
    return await accept(Action.NAVIGATION_START, body)


@router.post("/navigation/stop", **_ACCEPTED)
async def post_navigation_stop(body: NavigationStopRequest, accept: Accept) -> AcceptedResponse:
    """Stop walking navigation."""
    return await accept(Action.NAVIGATION_STOP, body)


@router.post("/emergency/call", **_ACCEPTED)
async def post_emergency_call(body: EmergencyCallRequest, accept: Accept) -> AcceptedResponse:
    """Call the emergency contact and text them the location."""
    return await accept(Action.EMERGENCY_CALL, body)


@router.post("/contact/call", **_ACCEPTED)
async def post_contact_call(body: ContactCallRequest, accept: Accept) -> AcceptedResponse:
    """Call a contact by name."""
    return await accept(Action.CONTACT_CALL, body)


@router.post("/location/get", **_ACCEPTED)
async def post_location_get(body: LocationGetRequest, accept: Accept) -> AcceptedResponse:
    """Read the phone's current location."""
    return await accept(Action.LOCATION_GET, body)


@router.post("/capabilities", **_ACCEPTED)
async def post_capabilities_get(body: CapabilitiesGetRequest, accept: Accept) -> AcceptedResponse:
    """Read the phone's permission/capability snapshot."""
    return await accept(Action.CAPABILITIES_GET, body)


@router.post("/call/answer", **_ACCEPTED)
async def post_call_answer(body: CallAnswerRequest, accept: Accept) -> AcceptedResponse:
    """Answer the ringing call."""
    return await accept(Action.CALL_ANSWER, body)


@router.post("/call/reject", **_ACCEPTED)
async def post_call_reject(body: CallRejectRequest, accept: Accept) -> AcceptedResponse:
    """Reject the ringing call."""
    return await accept(Action.CALL_REJECT, body)
