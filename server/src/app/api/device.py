"""Internal Device API used by the Android app: register, report, event, link."""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.adapters.device_events import DeviceEventAdapter
from app.auth import UserPrincipal, require_device_bearer_token, require_user_session
from app.config import Settings, get_app_settings
from app.database import get_db_session
from app.repositories.device import DeviceRepository
from app.repositories.glasses_device import GlassesDeviceRepository
from app.repositories.user import UserRepository
from app.schemas.auth import DeviceLinkData, DeviceLinkRequest
from app.schemas.common import OkResponse
from app.schemas.device import (
    DeviceEventData,
    DeviceEventRequest,
    DeviceRegisterData,
    DeviceRegisterRequest,
    DeviceReportData,
    DeviceReportRequest,
)
from app.services.auth import confirm_device_link
from app.services.device import DeviceService, RegistrationOutcome
from app.services.device_events import DeviceEventService
from app.workers.wake import WorkerWakeSignals, get_worker_wake_signals

router = APIRouter(prefix="/api/v1/device", tags=["device"])

Session = Annotated[AsyncSession, Depends(get_db_session)]
AppSettings = Annotated[Settings, Depends(get_app_settings)]

_REGISTRATION_ERRORS = {
    RegistrationOutcome.OWNER_MISMATCH: (status.HTTP_409_CONFLICT, "DEVICE_OWNER_MISMATCH"),
    RegistrationOutcome.REVOKED: (status.HTTP_403_FORBIDDEN, "DEVICE_REVOKED"),
}


@router.post(
    "/register",
    response_model=OkResponse[DeviceRegisterData],
    dependencies=[Depends(require_device_bearer_token)],
)
async def register_device(
    body: DeviceRegisterRequest, session: Session, settings: AppSettings
) -> OkResponse[DeviceRegisterData]:
    """Register the phone's push token for a user."""
    outcome = await DeviceService(session, settings).register(body)
    if outcome in _REGISTRATION_ERRORS:
        status_code, detail = _REGISTRATION_ERRORS[outcome]
        raise HTTPException(status_code=status_code, detail=detail)
    return OkResponse(data=DeviceRegisterData(device_id=body.device_id))


@router.post(
    "/report",
    response_model=OkResponse[DeviceReportData],
    dependencies=[Depends(require_device_bearer_token)],
)
async def report_device_action(
    body: DeviceReportRequest,
    session: Session,
    settings: AppSettings,
    wake_signals: Annotated[WorkerWakeSignals, Depends(get_worker_wake_signals)],
) -> OkResponse[DeviceReportData]:
    """Record the phone's terminal result for one command."""
    if await DeviceService(session, settings).record_report(body):
        wake_signals.callback.set()
    return OkResponse(data=DeviceReportData(request_id=body.request_id))


@router.post(
    "/event",
    response_model=OkResponse[DeviceEventData],
    dependencies=[Depends(require_device_bearer_token)],
)
async def post_device_event(
    body: DeviceEventRequest, session: Session, settings: AppSettings
) -> OkResponse[DeviceEventData]:
    """Forward an unsolicited phone event (incoming call) to the glasses server."""
    service = DeviceEventService(
        DeviceRepository(session),
        GlassesDeviceRepository(session),
        UserRepository(session),
        DeviceEventAdapter(settings),
    )
    glasses_device_id = await service.forward(body)
    return OkResponse(
        data=DeviceEventData(device_id=body.device_id, glasses_device_id=glasses_device_id)
    )


@router.post("/link", response_model=OkResponse[DeviceLinkData])
async def link_device(
    body: DeviceLinkRequest,
    principal: Annotated[UserPrincipal, Depends(require_user_session)],
    session: Session,
) -> OkResponse[DeviceLinkData]:
    """Confirm that an already-registered phone belongs to the logged-in user (read-only)."""
    device = await confirm_device_link(
        DeviceRepository(session), public_user_id=principal.public_user_id, device_id=body.device_id
    )
    return OkResponse(
        data=DeviceLinkData(device_id=device.device_id, platform=device.platform.value)
    )
