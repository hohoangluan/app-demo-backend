"""Unit tests for DeliveryWorker and DeliveryAdapter."""

from datetime import UTC, datetime
from uuid import uuid4

import pytest

from app.adapters.delivery import DeliveryOutcomeStatus, FakeDeliveryAdapter
from app.config import AppEnvironment, DeliveryTransport, Settings
from app.models.device import Device
from app.models.enums import Action, DevicePlatform, DeviceStatus, Operation
from app.models.operation import Operation as OperationModel


@pytest.fixture
def settings() -> Settings:
    """Fixture providing test configuration for delivery worker tests."""
    return Settings.model_validate(
        {
            "app_env": AppEnvironment.TEST,
            "http_port": 8000,
            "database_url": "postgresql+asyncpg://test:test@localhost:5432/app_test",
            "public_api_token_hash": "1" * 64,
            "public_api_client_id": "test-client",
            "public_api_scopes": {"service:execute", "requests:read"},
            "device_api_token_hash": "2" * 64,
            "field_encryption_key": "test-only-field-key",
            "delivery_transport": DeliveryTransport.FAKE,
        }
    )


@pytest.mark.asyncio
async def test_fake_delivery_adapter_returns_sent_outcome() -> None:
    """Verify FakeDeliveryAdapter returns SENT outcome with deterministic message ID."""
    adapter = FakeDeliveryAdapter()
    now = datetime.now(UTC)
    device = Device(
        user_id="user-1",
        device_id="device-1",
        platform=DevicePlatform.ANDROID,
        push_token_ciphertext="token-1",
        push_token_fingerprint="fp-1",
        status=DeviceStatus.ACTIVE,
        last_seen_at=now,
    )
    req_id = uuid4()
    op = OperationModel(
        request_id=req_id,
        client_id="client-1",
        user_id="user-1",
        operation=Operation.MUSIC_VOLUME,
        action=Action.MUSIC_VOLUME,
        params={"level": 50},
        request_fingerprint="f" * 64,
        created_at=now,
        updated_at=now,
        expires_at=now,
    )

    outcome = await adapter.send_command(device=device, push_token="token-1", operation=op)
    assert outcome.status is DeliveryOutcomeStatus.SENT
    assert outcome.provider_message_id == f"fake-msg-{req_id}"
