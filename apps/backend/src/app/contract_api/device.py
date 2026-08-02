"""Internal Device contract-only routers for OpenAPI generation."""

from typing import Never

from fastapi import APIRouter, Depends, status
from fastapi.security import HTTPBearer

from app.schemas.common import OkResponse
from app.schemas.device import (
    DeviceRegisterData,
    DeviceRegisterRequest,
    DeviceReportData,
    DeviceReportRequest,
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
