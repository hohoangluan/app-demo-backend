"""Internal Device contract-only routers for OpenAPI generation."""

from typing import Never

from fastapi import APIRouter, Depends, status
from fastapi.security import HTTPBearer

from app.schemas.common import OkResponse
from app.schemas.device import (
    DeviceEventData,
    DeviceEventRequest,
    DeviceRegisterData,
    DeviceRegisterRequest,
    DeviceReportData,
    DeviceReportRequest,
)
from app.schemas.glasses import (
    GlassesLinkData,
    GlassesLinkRequest,
    GlassesUnlinkData,
    GlassesUnlinkRequest,
)

device_bearer = HTTPBearer(scheme_name="DeviceBearerAuth")
router = APIRouter(dependencies=[Depends(device_bearer)])


def _contract_only() -> Never:
    """Prevent contract-only handlers from being used as runtime behavior."""
    raise NotImplementedError


@router.post(
    "/api/v1/device/register",
    response_model=OkResponse[DeviceRegisterData],
    status_code=status.HTTP_200_OK,
)
async def register_device(_request: DeviceRegisterRequest) -> Never:
    """Describe the Android device registration endpoint contract."""
    _contract_only()


@router.post(
    "/api/v1/device/report",
    response_model=OkResponse[DeviceReportData],
    status_code=status.HTTP_200_OK,
)
async def report_device_result(_request: DeviceReportRequest) -> Never:
    """Describe the Android device report endpoint contract."""
    _contract_only()


@router.post(
    "/api/v1/device/event",
    response_model=OkResponse[DeviceEventData],
    status_code=status.HTTP_200_OK,
)
async def post_device_event(_request: DeviceEventRequest) -> Never:
    """Describe the spontaneous Android event endpoint contract."""
    _contract_only()


@router.post(
    "/api/v1/device/glasses/link",
    response_model=OkResponse[GlassesLinkData],
    status_code=status.HTTP_200_OK,
)
async def link_glasses_device(_request: GlassesLinkRequest) -> Never:
    """Describe the glasses pairing endpoint contract."""
    _contract_only()


@router.post(
    "/api/v1/device/glasses/unlink",
    response_model=OkResponse[GlassesUnlinkData],
    status_code=status.HTTP_200_OK,
)
async def unlink_glasses_device(_request: GlassesUnlinkRequest) -> Never:
    """Describe the glasses unpairing endpoint contract."""
    _contract_only()
