"""Public contract-only routers for OpenAPI generation."""

from typing import Any, Final, Never
from uuid import UUID

from fastapi import APIRouter, Depends, status
from fastapi.security import HTTPBearer

from app.schemas.common import AcceptedResponse, ErrorResponse, OkResponse, RequestStatusData
from app.schemas.service_requests import (
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
)
from app.schemas.service_results import ActionResult

public_bearer = HTTPBearer(scheme_name="PublicBearerAuth")
router = APIRouter(dependencies=[Depends(public_bearer)])

PUBLIC_VALIDATION_RESPONSE: Final[dict[int | str, dict[str, Any]]] = {
    status.HTTP_400_BAD_REQUEST: {
        "model": ErrorResponse,
        "description": "Invalid request",
        "content": {
            "application/json": {
                "example": {
                    "status": "error",
                    "error": {
                        "code": "INVALID_REQUEST",
                        "message": "Invalid request",
                        "details": {},
                    },
                }
            }
        },
    }
}


def _contract_only() -> Never:
    """Prevent contract-only handlers from being used as runtime behavior."""
    raise NotImplementedError


@router.post(
    "/api/v1/service/ride/quote",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
    responses=PUBLIC_VALIDATION_RESPONSE,
)
async def ride_quote(_request: RideQuoteRequest) -> Never:
    """Describe the ride quote endpoint contract."""
    _contract_only()


@router.post(
    "/api/v1/service/ride/confirm",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
    responses=PUBLIC_VALIDATION_RESPONSE,
)
async def ride_confirm(_request: RideConfirmRequest) -> Never:
    """Describe the ride confirmation endpoint contract."""
    _contract_only()


@router.post(
    "/api/v1/service/music/play",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
    responses=PUBLIC_VALIDATION_RESPONSE,
)
async def music_play(_request: MusicPlayRequest) -> Never:
    """Describe the music play endpoint contract."""
    _contract_only()


@router.post(
    "/api/v1/service/music/stop",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
    responses=PUBLIC_VALIDATION_RESPONSE,
)
async def music_stop(_request: MusicStopRequest) -> Never:
    """Describe the music stop endpoint contract."""
    _contract_only()


@router.post(
    "/api/v1/service/music/volume",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
    responses=PUBLIC_VALIDATION_RESPONSE,
)
async def music_volume(_request: MusicVolumeRequest) -> Never:
    """Describe the music volume endpoint contract."""
    _contract_only()


@router.post(
    "/api/v1/service/navigation/start",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
    responses=PUBLIC_VALIDATION_RESPONSE,
)
async def navigation_start(_request: NavigationStartRequest) -> Never:
    """Describe the navigation start endpoint contract."""
    _contract_only()


@router.post(
    "/api/v1/service/navigation/stop",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
    responses=PUBLIC_VALIDATION_RESPONSE,
)
async def navigation_stop(_request: NavigationStopRequest) -> Never:
    """Describe the navigation stop endpoint contract."""
    _contract_only()


@router.post(
    "/api/v1/service/emergency/call",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
    responses=PUBLIC_VALIDATION_RESPONSE,
)
async def emergency_call(_request: EmergencyCallRequest) -> Never:
    """Describe the emergency call endpoint contract."""
    _contract_only()


@router.post(
    "/api/v1/service/contact/call",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
    responses=PUBLIC_VALIDATION_RESPONSE,
)
async def contact_call(_request: ContactCallRequest) -> Never:
    """Describe the contact call endpoint contract."""
    _contract_only()


@router.post(
    "/api/v1/service/location/get",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
    responses=PUBLIC_VALIDATION_RESPONSE,
)
async def location_get(_request: LocationGetRequest) -> Never:
    """Describe the device location lookup endpoint contract."""
    _contract_only()


@router.get(
    "/api/v1/requests/{request_id}",
    response_model=OkResponse[RequestStatusData[ActionResult]],
    status_code=status.HTTP_200_OK,
)
async def request_status(request_id: UUID) -> Never:
    """Describe the Public Request Status endpoint contract."""
    del request_id
    _contract_only()
