"""Delivery adapter interface and fake/FCM implementations.

Per ``architeture.md`` section 9.3 and ``claude.md``:
- Delivery adapter sends data messages to devices via FCM or fake transport.
- Translates provider outcomes into stable DeliveryOutcome values.
- Never mutates database or operation state directly.
"""

from __future__ import annotations

import asyncio
import json
import logging
from dataclasses import dataclass
from enum import StrEnum
from typing import TYPE_CHECKING, Protocol

import firebase_admin
from firebase_admin import credentials, messaging
from firebase_admin.exceptions import FirebaseError

from app.config import DeliveryTransport, Settings

if TYPE_CHECKING:
    from app.models.device import Device
    from app.models.operation import Operation

logger = logging.getLogger("app.adapters.delivery")

_FCM_APP_NAME = "app-demo-fcm"


class DeliveryOutcomeStatus(StrEnum):
    """Possible outcomes of a delivery attempt."""

    SENT = "sent"
    TRANSIENT_FAILURE = "transient_failure"
    PERMANENT_FAILURE = "permanent_failure"


@dataclass(frozen=True, slots=True)
class DeliveryOutcome:
    """Result of attempting to deliver a command to a device."""

    status: DeliveryOutcomeStatus
    provider_message_id: str | None = None
    error_reason: str | None = None
    invalid_token: bool = False


class DeliveryAdapter(Protocol):
    """Protocol for device message delivery transports."""

    async def send_command(
        self,
        *,
        device: Device,
        push_token: str,
        operation: Operation,
    ) -> DeliveryOutcome:
        """Deliver a command message to a device."""
        ...


class FakeDeliveryAdapter:
    """Deterministic fake delivery transport for local development and testing."""

    async def send_command(
        self,
        *,
        device: Device,
        push_token: str,
        operation: Operation,
    ) -> DeliveryOutcome:
        """Simulate immediate successful delivery with a deterministic message ID."""
        _ = (device, push_token)
        provider_message_id = f"fake-msg-{operation.request_id}"
        return DeliveryOutcome(
            status=DeliveryOutcomeStatus.SENT,
            provider_message_id=provider_message_id,
        )


def _get_fcm_app(settings: Settings) -> firebase_admin.App:
    """Return the cached Firebase Admin app, initializing it on first use."""
    try:
        return firebase_admin.get_app(_FCM_APP_NAME)
    except ValueError:
        if settings.google_application_credentials is not None:
            cred = credentials.Certificate(str(settings.google_application_credentials))
        else:
            cred = credentials.ApplicationDefault()
        return firebase_admin.initialize_app(
            cred,
            options={"projectId": settings.fcm_project_id},
            name=_FCM_APP_NAME,
        )


def _build_message(*, push_token: str, operation: Operation) -> messaging.Message:
    """Build the flat data-only FCM message matching the Android receiver's contract.

    Per ``architeture.md`` section 13.4, the command must not contain provider
    credential or bearer token; the Android app resolves its own callback
    credentials from local config saved at registration time instead.
    """
    data = {
        "request_id": str(operation.request_id),
        "user_id": operation.user_id,
        "device_id": operation.device_id or "",
        "action": operation.action.value,
        "params_json": json.dumps(operation.params, separators=(",", ":")),
        "issued_at": operation.created_at.isoformat(),
        "expires_at": operation.expires_at.isoformat(),
    }
    return messaging.Message(
        data=data,
        token=push_token,
        android=messaging.AndroidConfig(priority="high"),
    )


class FcmDeliveryAdapter:
    """FCM delivery transport using Firebase Admin SDK."""

    def __init__(self, settings: Settings) -> None:
        """Initialize FcmDeliveryAdapter with validated settings."""
        self._settings = settings

    async def send_command(
        self,
        *,
        device: Device,
        push_token: str,
        operation: Operation,
    ) -> DeliveryOutcome:
        """Deliver a data message to Android device via FCM."""
        _ = device
        app = _get_fcm_app(self._settings)
        message = _build_message(push_token=push_token, operation=operation)

        try:
            message_id = await asyncio.to_thread(messaging.send, message, app=app)
        except messaging.UnregisteredError as exc:
            logger.info("FCM token unregistered for request %s", operation.request_id)
            return DeliveryOutcome(
                status=DeliveryOutcomeStatus.PERMANENT_FAILURE,
                error_reason=str(exc),
                invalid_token=True,
            )
        except (messaging.SenderIdMismatchError, messaging.ThirdPartyAuthError) as exc:
            logger.warning("FCM permanent send failure for request %s", operation.request_id)
            return DeliveryOutcome(
                status=DeliveryOutcomeStatus.PERMANENT_FAILURE,
                error_reason=str(exc),
            )
        except (messaging.QuotaExceededError, FirebaseError) as exc:
            logger.warning("FCM transient send failure for request %s", operation.request_id)
            return DeliveryOutcome(
                status=DeliveryOutcomeStatus.TRANSIENT_FAILURE,
                error_reason=str(exc),
            )

        return DeliveryOutcome(
            status=DeliveryOutcomeStatus.SENT,
            provider_message_id=message_id,
        )


def get_delivery_adapter(settings: Settings) -> DeliveryAdapter:
    """Return the configured delivery transport adapter."""
    if settings.delivery_transport is DeliveryTransport.FCM:
        return FcmDeliveryAdapter(settings)
    return FakeDeliveryAdapter()
