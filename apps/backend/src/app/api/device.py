"""Internal Android Device API router (/register and /report).

Implements internal Android client endpoints per ``docs/p1-api-plan.md`` and
``CONTRACT_DECISIONS.md``:
- Gated by ``require_device_bearer_token``.
- Device registration / token rotation via ``DeviceRepository.register``.
- Device execution report handling via ``OperationRepository.record_device_report``.
"""

from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession  # noqa: TC002

from app.auth import require_device_bearer_token
from app.config import Settings, get_app_settings
from app.database import get_db_session
from app.errors import RequestIdConflictError, RequestNotFoundError
from app.models.enums import CallbackState, DevicePlatform
from app.models.operation import Operation
from app.repositories.device import (
    DeviceIsRevoked,
    DeviceOwnerMismatch,
    DeviceRegistrationRequest,
    DeviceRepository,
)
from app.repositories.operation import (
    DeviceReport,
    OperationRepository,
    ReportExecutionState,
    ReportOutcomeStatus,
)
from app.schemas.common import OkResponse
from app.schemas.device import (
    DeviceRegisterData,
    DeviceRegisterRequest,
    DeviceReportData,
    DeviceReportRequest,
    ExecutionState,
)

router = APIRouter(prefix="/api/v1/device", tags=["device"])

_DEVICE_OWNER_MISMATCH_MSG = "DEVICE_OWNER_MISMATCH"
_DEVICE_REVOKED_MSG = "DEVICE_REVOKED"
_OPERATION_NOT_FOUND_MSG = "Operation not found"
_IDENTITY_MISMATCH_MSG = "Operation identity or action mismatch"
_REPORT_CONFLICT_MSG = "Report conflict or operation already terminal"


@router.post(
    "/register",
    response_model=OkResponse[DeviceRegisterData],
    dependencies=[Depends(require_device_bearer_token)],
)
async def register_device(
    body: DeviceRegisterRequest,
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> OkResponse[DeviceRegisterData]:
    """Register or update an Android device push token.

    Implements ``D-05`` enrollment policy: idempotent token rotation for the same owner,
    owner reassignment forbidden (409), revoked devices rejected (403).
    """
    if session is None:
        return OkResponse(data=DeviceRegisterData(device_id=body.device_id, registered=True))

    repo = DeviceRepository(session)
    token_fingerprint = hashlib.sha256(body.push_token.encode("utf-8")).hexdigest()

    req = DeviceRegistrationRequest(
        user_id=body.user_id,
        device_id=body.device_id,
        platform=DevicePlatform(body.platform.value),
        push_token_ciphertext=body.push_token,
        push_token_fingerprint=token_fingerprint,
        last_seen_at=datetime.now(UTC),
    )

    outcome = await repo.register(req)

    if isinstance(outcome, DeviceOwnerMismatch):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=_DEVICE_OWNER_MISMATCH_MSG,
        )
    if isinstance(outcome, DeviceIsRevoked):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=_DEVICE_REVOKED_MSG,
        )

    if session is not None:
        await session.commit()

    return OkResponse(data=DeviceRegisterData(device_id=body.device_id, registered=True))


@router.post(
    "/report",
    response_model=OkResponse[DeviceReportData],
    dependencies=[Depends(require_device_bearer_token)],
)
async def report_device_action(
    body: DeviceReportRequest,
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_app_settings)],
) -> OkResponse[DeviceReportData]:
    """Report the execution outcome of an action by an Android device.

    Validates device ownership, matches operation action, applies terminal state transition,
    and enqueues callback if required.
    """
    op = await session.get(Operation, body.request_id) if session is not None else None
    if op is None:
        raise RequestNotFoundError(_OPERATION_NOT_FOUND_MSG)

    if op.user_id != body.user_id or op.action.value != body.action.value:
        raise RequestNotFoundError(_IDENTITY_MISMATCH_MSG)

    error_dict = body.error.model_dump() if body.error is not None else None
    report_content = {
        "execution_state": body.execution_state.value,
        "result": body.result,
        "error": error_dict,
    }
    report_hash = hashlib.sha256(
        json.dumps(report_content, sort_keys=True).encode("utf-8")
    ).hexdigest()

    execution_state = (
        ReportExecutionState.SUCCEEDED
        if body.execution_state is ExecutionState.SUCCEEDED
        else ReportExecutionState.FAILED
    )
    initial_callback_state = (
        CallbackState.PENDING if settings.callback_url else CallbackState.NOT_REQUIRED
    )
    now = datetime.now(UTC)
    next_callback_at = now if settings.callback_url else None

    result_dict: dict[str, object] | None = body.result  # type: ignore[assignment]
    report = DeviceReport(
        request_id=body.request_id,
        execution_state=execution_state,
        result=result_dict,
        error=error_dict,
        report_payload_hash=report_hash,
        callback_state=initial_callback_state,
        next_callback_at=next_callback_at,
    )

    repo = OperationRepository(session)
    outcome = await repo.record_device_report(report)

    if outcome is None or outcome.status is ReportOutcomeStatus.CONFLICT:
        raise RequestIdConflictError(_REPORT_CONFLICT_MSG)

    if session is not None:
        await session.commit()

    return OkResponse(data=DeviceReportData(request_id=body.request_id, report_received=True))
