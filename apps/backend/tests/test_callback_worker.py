"""Unit tests for CallbackAdapter and SSRF validation."""

from datetime import UTC, datetime
from uuid import uuid4

import pytest

from app.adapters.callback import (
    CallbackAdapter,
    CallbackOutcomeStatus,
    SsfValidationError,
    validate_callback_url,
)
from app.config import AppEnvironment, DeliveryTransport, Settings
from app.models.enums import Action, Operation
from app.models.operation import Operation as OperationModel


@pytest.fixture
def settings() -> Settings:
    """Fixture providing test configuration for callback tests."""
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
            "callback_url": "https://example.com/webhook",
            "callback_allowed_hosts": ["example.com"],
        }
    )


def test_validate_callback_url_ssrf_allowed() -> None:
    """Verify allowed hostname passes SSRF validation."""
    validate_callback_url("https://example.com/webhook", ["example.com"])


def test_validate_callback_url_ssrf_rejected() -> None:
    """Verify non-allowed hostname fails SSRF validation."""
    with pytest.raises(SsfValidationError):
        validate_callback_url("https://malicious.com/webhook", ["example.com"])


def test_validate_callback_url_private_ip_rejected() -> None:
    """Verify loopback IP target fails SSRF validation."""
    with pytest.raises(SsfValidationError):
        validate_callback_url("http://127.0.0.1/webhook", ["example.com"])


@pytest.mark.asyncio
async def test_callback_adapter_unconfigured_url_dead_letter() -> None:
    """Verify CallbackAdapter returns DEAD_LETTER when callback_url is unconfigured."""
    empty_settings = Settings.model_validate(
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
    adapter = CallbackAdapter(empty_settings)
    now = datetime.now(UTC)
    op = OperationModel(
        request_id=uuid4(),
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
    outcome = await adapter.send_callback(op)
    assert outcome.status is CallbackOutcomeStatus.DEAD_LETTER
