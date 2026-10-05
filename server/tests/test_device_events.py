"""Spontaneous device-event privacy and forwarding tests."""

from __future__ import annotations

from types import SimpleNamespace
from typing import cast

import httpx
import pytest
from pydantic import ValidationError

from app.adapters.device_events import DeviceEventAdapter
from app.config import AppEnvironment, DeliveryTransport, Settings
from app.models.enums import DeviceStatus
from app.schemas.device import DeviceEventRequest
from app.services.device_events import DeviceEventService


class _Devices:
    async def get_by_device_id(self, device_id: str) -> SimpleNamespace:
        assert device_id == "phone-1"
        return SimpleNamespace(status=DeviceStatus.ACTIVE, user_id="user-1")


class _Glasses:
    async def get_active_device_id(self, user_id: str) -> str:
        assert user_id == "user-1"
        return "glasses-1"


class _Users:
    def __init__(self, policy: str) -> None:
        self._policy = policy

    async def get_by_public_user_id(self, user_id: str) -> SimpleNamespace:
        assert user_id == "user-1"
        return SimpleNamespace(announce_caller=self._policy)


class _Adapter:
    def __init__(self) -> None:
        self.payloads: list[dict[str, object]] = []

    async def forward(self, payload: dict[str, object]) -> None:
        self.payloads.append(payload)


def _incoming() -> DeviceEventRequest:
    return DeviceEventRequest.model_validate(
        {
            "device_id": "phone-1",
            "type": "call_incoming",
            "caller": {
                "contact_id": "contact-1",
                "name": "Mẹ",
                "number_tail": "456",
            },
        }
    )


@pytest.mark.parametrize(
    ("policy", "expected_caller"),
    [
        (
            "name",
            {
                "contact_id": "contact-1",
                "name": "Mẹ",
                "number_tail": "456",
                "duplicate_name": False,
            },
        ),
        ("number_only", {"contact_id": "contact-1", "number_tail": "456"}),
        ("ring_only", {}),
    ],
)
async def test_event_forwarded_without_request_id(
    policy: str, expected_caller: dict[str, object]
) -> None:
    """Apply caller privacy policy and never invent an action request id."""
    adapter = _Adapter()
    service = DeviceEventService(_Devices(), _Glasses(), _Users(policy), adapter)  # type: ignore[arg-type]

    assert await service.forward(_incoming()) == "glasses-1"
    assert adapter.payloads == [
        {
            "device_id": "glasses-1",
            "type": "call_incoming",
            "caller": expected_caller,
        }
    ]
    assert "request_id" not in adapter.payloads[0]


async def test_call_ended_forwards_no_caller() -> None:
    """Keep call_ended silent and free of stale caller data."""
    adapter = _Adapter()
    service = DeviceEventService(_Devices(), _Glasses(), _Users("name"), adapter)  # type: ignore[arg-type]
    event = DeviceEventRequest(device_id="phone-1", type="call_ended")

    await service.forward(event)
    assert adapter.payloads == [{"device_id": "glasses-1", "type": "call_ended"}]


@pytest.mark.parametrize(
    "caller",
    [
        {"number": "0901234567"},
        {"number_tail": "01234567"},
        {"name": "090 123 4567"},
    ],
)
def test_full_number_cannot_enter_host_schema(caller: dict[str, object]) -> None:
    """Reject every representation that could carry a full phone number."""
    with pytest.raises(ValidationError):
        DeviceEventRequest.model_validate(
            {"device_id": "phone-1", "type": "call_incoming", "caller": caller}
        )


def _settings() -> Settings:
    return Settings.model_validate(
        {
            "app_env": AppEnvironment.TEST,
            "http_port": 8000,
            "database_url": "postgresql+asyncpg://test:test@localhost:5432/app_test",
            "public_api_token_hash": "1" * 64,
            "public_api_client_id": "test-client",
            "public_api_scopes": {"service:execute", "requests:read"},
            "device_api_token_hash": "2" * 64,
            "field_encryption_key": "test-key",
            "delivery_transport": DeliveryTransport.FAKE,
            "callback_url": "http://127.0.0.1:8001/internal/action-results",
            "callback_token": "callback-secret",
            "callback_allowed_hosts": "127.0.0.1",
        }
    )


async def test_adapter_uses_distinct_internal_event_endpoint_and_bearer() -> None:
    """Forward to the event endpoint with callback auth and no idempotency id."""
    seen: dict[str, object] = {}

    async def receive(request: httpx.Request) -> httpx.Response:
        seen["path"] = request.url.path
        seen["authorization"] = request.headers.get("authorization")
        seen["body"] = request.content
        return httpx.Response(200)

    client = httpx.AsyncClient(transport=httpx.MockTransport(receive))
    try:
        adapter = DeviceEventAdapter(_settings(), client)
        await adapter.forward({"device_id": "glasses-1", "type": "call_ended"})
    finally:
        await client.aclose()

    assert seen["path"] == "/internal/device-events"
    assert seen["authorization"] == "Bearer callback-secret"
    assert b"request_id" not in cast("bytes", seen["body"])
