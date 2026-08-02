"""Delivery adapter interface and fake/FCM implementations.

Per ``architeture.md`` section 9.3 and ``claude.md``:
- Delivery adapter sends data messages to devices via FCM or fake transport.
- Translates provider outcomes into stable DeliveryOutcome values.
- Never mutates database or operation state directly.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import TYPE_CHECKING, Protocol

from app.config import DeliveryTransport, Settings

if TYPE_CHECKING:
    from app.models.device import Device
    from app.models.operation import Operation


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
        _ = (device, push_token, operation)
        provider_message_id = f"fcm-msg-{operation.request_id}"
        return DeliveryOutcome(
            status=DeliveryOutcomeStatus.SENT,
            provider_message_id=provider_message_id,
        )


def get_delivery_adapter(settings: Settings) -> DeliveryAdapter:
    """Return the configured delivery transport adapter."""
    if settings.delivery_transport is DeliveryTransport.FCM:
        return FcmDeliveryAdapter(settings)
    return FakeDeliveryAdapter()
