"""API tests for Status API GET /api/v1/requests/{request_id}."""

import hashlib
import hmac
import secrets
from collections.abc import AsyncGenerator
from datetime import UTC, datetime
from uuid import UUID, uuid4

import pytest
from fastapi import status
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.actions import Action
from app.actions import Operation as OperationName
from app.application import create_app
from app.config import AppEnvironment, DeliveryTransport, Settings
from app.database import get_db_session
from app.models.enums import CallbackState, DeliveryState, RequestState
from app.models.operation import Operation
from app.services.operation import OperationService

_PUBLIC_API_DOMAIN = b"app-demo-auth/v1/public-api"


def _digest_hex(raw_token: str) -> str:
    return hmac.new(raw_token.encode("utf-8"), _PUBLIC_API_DOMAIN, hashlib.sha256).hexdigest()


@pytest.fixture
def auth_setup() -> tuple[Settings, str]:
    """Provide settings and raw token for authentication tests."""
    raw_token = secrets.token_urlsafe(32)
    token_hash = _digest_hex(raw_token)
    settings = Settings.model_validate(
        {
            "app_env": AppEnvironment.TEST,
            "http_port": 8000,
            "database_url": "postgresql+asyncpg://test:test@localhost:5432/app_test",
            "public_api_token_hash": token_hash,
            "public_api_client_id": "test-client-1",
            "public_api_scopes": {"service:execute", "requests:read"},
            "device_api_token_hash": "2" * 64,
            "field_encryption_key": "test-only-field-key",
            "delivery_transport": DeliveryTransport.FAKE,
        }
    )
    return settings, raw_token


async def _dummy_get_db_session() -> AsyncGenerator[AsyncSession | None]:
    """Override get_db_session so it does not raise RuntimeError."""
    yield None


@pytest.mark.asyncio
async def test_status_api_missing_auth(auth_setup: tuple[Settings, str]) -> None:
    """GET status without Authorization header returns 401."""
    settings, _ = auth_setup
    app = create_app(settings)
    app.dependency_overrides[get_db_session] = _dummy_get_db_session

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        req_id = uuid4()
        response = await client.get(f"/api/v1/requests/{req_id}")

    assert response.status_code == status.HTTP_401_UNAUTHORIZED
    assert response.headers["www-authenticate"] == "Bearer"
    assert response.json() == {"detail": "UNAUTHORIZED"}


@pytest.mark.asyncio
async def test_status_api_forbidden_scope() -> None:
    """GET status with a token lacking requests:read scope returns 403."""
    raw_token = secrets.token_urlsafe(32)
    token_hash = _digest_hex(raw_token)
    settings = Settings.model_validate(
        {
            "app_env": AppEnvironment.TEST,
            "http_port": 8000,
            "database_url": "postgresql+asyncpg://test:test@localhost:5432/app_test",
            "public_api_token_hash": token_hash,
            "public_api_client_id": "test-client-1",
            "public_api_scopes": {"service:execute"},  # missing requests:read
            "device_api_token_hash": "2" * 64,
            "field_encryption_key": "test-only-field-key",
            "delivery_transport": DeliveryTransport.FAKE,
        }
    )
    app = create_app(settings)
    app.dependency_overrides[get_db_session] = _dummy_get_db_session

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        req_id = uuid4()
        response = await client.get(
            f"/api/v1/requests/{req_id}", headers={"Authorization": f"Bearer {raw_token}"}
        )

    assert response.status_code == status.HTTP_403_FORBIDDEN


@pytest.mark.asyncio
async def test_status_api_not_found_returns_404(
    auth_setup: tuple[Settings, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """GET status for non-existent or wrong-owner request returns 404."""
    settings, raw_token = auth_setup
    app = create_app(settings)
    app.dependency_overrides[get_db_session] = _dummy_get_db_session

    async def mock_get_status(
        _self: OperationService, *, client_id: str, request_id: UUID
    ) -> Operation | None:
        _ = (client_id, request_id)
        return None

    monkeypatch.setattr(OperationService, "get_status", mock_get_status)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        req_id = uuid4()
        response = await client.get(
            f"/api/v1/requests/{req_id}", headers={"Authorization": f"Bearer {raw_token}"}
        )

    assert response.status_code == status.HTTP_404_NOT_FOUND
    body = response.json()
    assert body["status"] == "error"
    assert body["error"]["code"] == "REQUEST_NOT_FOUND"


@pytest.mark.asyncio
async def test_status_api_success_returns_200_processing(
    auth_setup: tuple[Settings, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """GET status for an existing processing request returns 200 OK status envelope."""
    settings, raw_token = auth_setup
    app = create_app(settings)
    app.dependency_overrides[get_db_session] = _dummy_get_db_session

    now = datetime.now(UTC)
    req_id = uuid4()
    mock_op = Operation(
        request_id=req_id,
        client_id="test-client-1",
        user_id="user-1",
        operation=OperationName.MUSIC_VOLUME,
        action=Action.MUSIC_VOLUME,
        params={"level": 50},
        request_fingerprint="a" * 64,
        request_state=RequestState.PROCESSING,
        delivery_state=DeliveryState.RECEIVED,
        callback_state=CallbackState.NOT_REQUIRED,
        created_at=now,
        updated_at=now,
        expires_at=now,
    )

    async def mock_get_status(
        _self: OperationService, *, client_id: str, request_id: UUID
    ) -> Operation | None:
        if client_id == "test-client-1" and request_id == req_id:
            return mock_op
        return None

    monkeypatch.setattr(OperationService, "get_status", mock_get_status)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        response = await client.get(
            f"/api/v1/requests/{req_id}", headers={"Authorization": f"Bearer {raw_token}"}
        )

    assert response.status_code == status.HTTP_200_OK
    body = response.json()
    assert body["status"] == "ok"
    assert body["data"]["request_id"] == str(req_id)
    assert body["data"]["operation"] == "music_volume"
    assert body["data"]["request_state"] == "processing"
    assert body["data"]["result"] is None
    assert body["data"]["error"] is None
