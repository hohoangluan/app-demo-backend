"""API tests for Device API router endpoints (/register and /report)."""

import hashlib
import hmac
import secrets
from collections.abc import AsyncGenerator

import pytest
from fastapi import status
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.application import create_app
from app.config import AppEnvironment, DeliveryTransport, Settings
from app.database import get_db_session

_DEVICE_API_DOMAIN = b"app-demo-auth/v1/device-api"


def _device_digest_hex(raw_token: str) -> str:
    return hmac.new(raw_token.encode("utf-8"), _DEVICE_API_DOMAIN, hashlib.sha256).hexdigest()


@pytest.fixture
def auth_setup() -> tuple[Settings, str]:
    """Provide settings and raw token for device authentication tests."""
    raw_token = secrets.token_urlsafe(32)
    token_hash = _device_digest_hex(raw_token)
    settings = Settings.model_validate(
        {
            "app_env": AppEnvironment.TEST,
            "http_port": 8000,
            "database_url": "postgresql+asyncpg://test:test@localhost:5432/app_test",
            "public_api_token_hash": "1" * 64,
            "public_api_client_id": "test-client-1",
            "public_api_scopes": {"service:execute", "requests:read"},
            "device_api_token_hash": token_hash,
            "field_encryption_key": "test-only-field-key",
            "delivery_transport": DeliveryTransport.FAKE,
        }
    )
    return settings, raw_token


async def _dummy_get_db_session() -> AsyncGenerator[AsyncSession | None]:
    """Override get_db_session so unit tests do not raise RuntimeError."""
    yield None


@pytest.mark.asyncio
async def test_device_register_missing_auth(auth_setup: tuple[Settings, str]) -> None:
    """POST /api/v1/device/register without bearer token returns 401."""
    settings, _ = auth_setup
    app = create_app(settings)
    app.dependency_overrides[get_db_session] = _dummy_get_db_session

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        payload = {
            "user_id": "user-1",
            "device_id": "device-1",
            "platform": "android",
            "push_token": "token-123",
        }
        response = await client.post("/api/v1/device/register", json=payload)

    assert response.status_code == status.HTTP_401_UNAUTHORIZED
    assert response.json() == {"detail": "UNAUTHORIZED"}
