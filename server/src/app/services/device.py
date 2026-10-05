"""Android device use cases: push-token registration and execution reports."""

from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime
from enum import StrEnum
from typing import TYPE_CHECKING, cast

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

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.config import Settings
    from app.models.operation import JsonObject
    from app.schemas.device import DeviceRegisterRequest, DeviceReportRequest


def report_payload_hash(body: DeviceReportRequest) -> str:
    """Hash the outcome part of a report so exact replays can be told from conflicts."""
    content = {
        "execution_state": body.execution_state.value,
        "result": body.result,
        "error": body.error.model_dump() if body.error is not None else None,
    }
    return hashlib.sha256(json.dumps(content, sort_keys=True).encode("utf-8")).hexdigest()


class RegistrationOutcome(StrEnum):
    """Result of :meth:`DeviceService.register`."""

    REGISTERED = "registered"
    OWNER_MISMATCH = "owner_mismatch"
    REVOKED = "revoked"


class DeviceService:
    """Register phones and apply their execution reports."""

    def __init__(self, session: AsyncSession, settings: Settings) -> None:
        """Bind the request's session and settings."""
        self._session = session
        self._settings = settings

    async def register(self, body: DeviceRegisterRequest) -> RegistrationOutcome:
        """Register a phone or rotate its push token, committing on success.

        Re-registering the same ``device_id`` for the same user is idempotent;
        moving it to another user (409) or reviving a revoked device (403) is not.
        """
        outcome = await DeviceRepository(self._session).register(
            DeviceRegistrationRequest(
                user_id=body.user_id,
                device_id=body.device_id,
                platform=DevicePlatform(body.platform.value),
                push_token_ciphertext=body.push_token,
                push_token_fingerprint=hashlib.sha256(body.push_token.encode("utf-8")).hexdigest(),
                last_seen_at=datetime.now(UTC),
            )
        )
        if isinstance(outcome, DeviceOwnerMismatch):
            return RegistrationOutcome.OWNER_MISMATCH
        if isinstance(outcome, DeviceIsRevoked):
            return RegistrationOutcome.REVOKED
        await self._session.commit()
        return RegistrationOutcome.REGISTERED

    async def record_report(self, body: DeviceReportRequest) -> bool:
        """Apply a terminal report and commit; return whether a callback is now due.

        The report must come from the user and device the command was sent to,
        for the same action. An exact replay is accepted without changes; a
        different outcome for an already-terminal operation is a conflict.
        """
        operation = await self._session.get(Operation, body.request_id)
        if operation is None:
            message = "Operation not found"
            raise RequestNotFoundError(message)
        if (
            operation.user_id != body.user_id
            or operation.action.value != body.action.value
            or (operation.device_id is not None and operation.device_id != body.device_id)
        ):
            message = "Operation identity or action mismatch"
            raise RequestNotFoundError(message)

        callback_enabled = self._settings.callback_url is not None
        outcome = await OperationRepository(self._session).record_device_report(
            DeviceReport(
                request_id=body.request_id,
                execution_state=ReportExecutionState(body.execution_state.value),
                result=cast("JsonObject | None", body.result),
                error=body.error.model_dump() if body.error is not None else None,
                report_payload_hash=report_payload_hash(body),
                callback_state=(
                    CallbackState.PENDING if callback_enabled else CallbackState.NOT_REQUIRED
                ),
                next_callback_at=datetime.now(UTC) if callback_enabled else None,
            )
        )
        if outcome is None or outcome.status is ReportOutcomeStatus.CONFLICT:
            message = "Report conflict or operation already terminal"
            raise RequestIdConflictError(message)
        await self._session.commit()
        return callback_enabled
