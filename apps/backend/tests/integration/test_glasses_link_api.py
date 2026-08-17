"""PostgreSQL integration tests for POST /api/v1/device/glasses/link and /unlink."""

from __future__ import annotations

import hashlib
import hmac
import secrets
from typing import TYPE_CHECKING

import pytest
from httpx import ASGITransport, AsyncClient

from app.application import create_app
from app.config import AppEnvironment, DeliveryTransport, Settings
from app.database import get_db_session

if TYPE_CHECKING:
    from collections.abc import AsyncGenerator, AsyncIterator

    from sqlalchemy.ext.asyncio import AsyncSession

    from app.database import AsyncSessionFactory

_DEVICE_API_DOMAIN = b"app-demo-auth/v1/device-api"


def _device_digest_hex(raw_token: str) -> str:
    return hmac.new(raw_token.encode("utf-8"), _DEVICE_API_DOMAIN, hashlib.sha256).hexdigest()


@pytest.fixture
async def glasses_client(
    postgres_session_factory: AsyncSessionFactory,
) -> AsyncIterator[tuple[AsyncClient, str]]:
    """Yield an HTTP client wired to real PostgreSQL, plus a valid Device bearer token."""
    raw_token = secrets.token_urlsafe(32)
    settings = Settings.model_validate(
        {
            "app_env": AppEnvironment.TEST,
            "http_port": 8000,
            "database_url": "postgresql+asyncpg://test:test@localhost:5432/app_test",
            "public_api_token_hash": "1" * 64,
            "public_api_client_id": "test-only-public-client",
            "public_api_scopes": {"service:execute", "requests:read"},
            "device_api_token_hash": _device_digest_hex(raw_token),
            "field_encryption_key": "test-only-field-key",
            "delivery_transport": DeliveryTransport.FAKE,
        }
    )
    app = create_app(settings)

    async def _db_session_override() -> AsyncGenerator[AsyncSession]:
        async with postgres_session_factory() as session:
            yield session

    app.dependency_overrides[get_db_session] = _db_session_override

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        yield client, raw_token


@pytest.mark.asyncio
async def test_glasses_link_missing_auth_returns_401(
    glasses_client: tuple[AsyncClient, str],
) -> None:
    """POST /api/v1/device/glasses/link without a bearer token returns 401."""
    client, _ = glasses_client
    response = await client.post(
        "/api/v1/device/glasses/link", json={"user_id": "user-1", "device_id": "glasses-1"}
    )
    assert response.status_code == 401


@pytest.mark.asyncio
async def test_glasses_link_creates_new_pairing(
    glasses_client: tuple[AsyncClient, str],
) -> None:
    """A first link for a fresh device_id returns 200 with linked: true."""
    client, raw_token = glasses_client
    response = await client.post(
        "/api/v1/device/glasses/link",
        json={"user_id": "user-1", "device_id": "glasses-1"},
        headers={"Authorization": f"Bearer {raw_token}"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["data"] == {"device_id": "glasses-1", "linked": True}


@pytest.mark.asyncio
async def test_glasses_link_is_idempotent_for_same_owner(
    glasses_client: tuple[AsyncClient, str],
) -> None:
    """Re-linking the same device_id/user_id pair succeeds again, not an error."""
    client, raw_token = glasses_client
    headers = {"Authorization": f"Bearer {raw_token}"}
    payload = {"user_id": "user-1", "device_id": "glasses-1"}

    first = await client.post("/api/v1/device/glasses/link", json=payload, headers=headers)
    second = await client.post("/api/v1/device/glasses/link", json=payload, headers=headers)

    assert first.status_code == 200
    assert second.status_code == 200
    assert second.json()["data"]["linked"] is True


@pytest.mark.asyncio
async def test_glasses_link_conflict_for_actively_paired_device(
    glasses_client: tuple[AsyncClient, str],
) -> None:
    """Linking a device_id already active under another user_id returns 409."""
    client, raw_token = glasses_client
    headers = {"Authorization": f"Bearer {raw_token}"}
    await client.post(
        "/api/v1/device/glasses/link",
        json={"user_id": "user-1", "device_id": "glasses-shared"},
        headers=headers,
    )

    response = await client.post(
        "/api/v1/device/glasses/link",
        json={"user_id": "user-2", "device_id": "glasses-shared"},
        headers=headers,
    )
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "GLASSES_DEVICE_OWNER_CONFLICT"


@pytest.mark.asyncio
async def test_glasses_unlink_deactivates_active_pairing(
    glasses_client: tuple[AsyncClient, str],
) -> None:
    """Unlinking a user with an active pairing returns unlinked: true."""
    client, raw_token = glasses_client
    headers = {"Authorization": f"Bearer {raw_token}"}
    await client.post(
        "/api/v1/device/glasses/link",
        json={"user_id": "user-1", "device_id": "glasses-1"},
        headers=headers,
    )

    response = await client.post(
        "/api/v1/device/glasses/unlink", json={"user_id": "user-1"}, headers=headers
    )
    assert response.status_code == 200
    assert response.json()["data"] == {"unlinked": True}


@pytest.mark.asyncio
async def test_glasses_unlink_is_noop_when_nothing_linked(
    glasses_client: tuple[AsyncClient, str],
) -> None:
    """Unlinking a user with no active pairing returns unlinked: false, not an error."""
    client, raw_token = glasses_client
    response = await client.post(
        "/api/v1/device/glasses/unlink",
        json={"user_id": "user-none"},
        headers={"Authorization": f"Bearer {raw_token}"},
    )
    assert response.status_code == 200
    assert response.json()["data"] == {"unlinked": False}
