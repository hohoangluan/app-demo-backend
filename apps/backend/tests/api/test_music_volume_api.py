"""API tests for Music Volume service endpoint POST /api/v1/service/music/volume."""

import hashlib
import hmac
import secrets
from collections.abc import AsyncGenerator
from datetime import UTC, datetime
from uuid import uuid4

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
from app.schemas.service_requests import ServiceRequest
from app.services.operation import AcceptOperationResult, AcceptOperationStatus, OperationService

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
async def test_music_volume_missing_auth(auth_setup: tuple[Settings, str]) -> None:
    """POST /api/v1/service/music/volume without auth returns 401."""
    settings, _ = auth_setup
    app = create_app(settings)
    app.dependency_overrides[get_db_session] = _dummy_get_db_session

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        payload = {"device_id": "glasses-1", "request_id": str(uuid4()), "level": 50}
        response = await client.post("/api/v1/service/music/volume", json=payload)

    assert response.status_code == status.HTTP_401_UNAUTHORIZED
    assert response.json() == {"detail": "UNAUTHORIZED"}


@pytest.mark.asyncio
async def test_music_volume_validation_failure_both_direction_and_level(
    auth_setup: tuple[Settings, str],
) -> None:
    """POST volume with both direction and level returns 400 INVALID_REQUEST, not 422."""
    settings, raw_token = auth_setup
    app = create_app(settings)
    app.dependency_overrides[get_db_session] = _dummy_get_db_session

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        payload = {
            "device_id": "glasses-1",
            "request_id": str(uuid4()),
            "direction": "up",
            "level": 50,
        }
        response = await client.post(
            "/api/v1/service/music/volume",
            json=payload,
            headers={"Authorization": f"Bearer {raw_token}"},
        )

    assert response.status_code == status.HTTP_400_BAD_REQUEST
    body = response.json()
    assert body["status"] == "error"
    assert body["error"]["code"] == "INVALID_REQUEST"


@pytest.mark.asyncio
async def test_music_volume_validation_failure_invalid_level(
    auth_setup: tuple[Settings, str],
) -> None:
    """POST volume with level out of bounds returns 400 INVALID_REQUEST."""
    settings, raw_token = auth_setup
    app = create_app(settings)
    app.dependency_overrides[get_db_session] = _dummy_get_db_session

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        payload = {
            "device_id": "glasses-1",
            "request_id": str(uuid4()),
            "level": 150,  # invalid level > 100
        }
        response = await client.post(
            "/api/v1/service/music/volume",
            json=payload,
            headers={"Authorization": f"Bearer {raw_token}"},
        )

    assert response.status_code == status.HTTP_400_BAD_REQUEST
    assert response.json()["error"]["code"] == "INVALID_REQUEST"


@pytest.mark.asyncio
async def test_music_volume_success_202(
    auth_setup: tuple[Settings, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """POST volume with valid payload returns 202 accepted envelope."""
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
        params={"level": 70},
        request_fingerprint="b" * 64,
        request_state=RequestState.PROCESSING,
        delivery_state=DeliveryState.RECEIVED,
        callback_state=CallbackState.NOT_REQUIRED,
        created_at=now,
        updated_at=now,
        expires_at=now,
    )

    async def mock_accept(
        _self: OperationService,
        *,
        client_id: str,
        user_id: str,
        route: object,
        request: ServiceRequest,
    ) -> AcceptOperationResult:
        _ = (client_id, user_id, route, request)
        return AcceptOperationResult(
            status=AcceptOperationStatus.ACCEPTED, operation=mock_op, inserted=True
        )

    async def mock_resolve(_repository: object, *, device_id: str) -> str:
        _ = device_id
        return "user-1"

    monkeypatch.setattr(OperationService, "accept", mock_accept)
    monkeypatch.setattr("app.api.service.resolve_glasses_device_owner", mock_resolve)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        payload = {"device_id": "glasses-1", "request_id": str(req_id), "level": 70}
        response = await client.post(
            "/api/v1/service/music/volume",
            json=payload,
            headers={"Authorization": f"Bearer {raw_token}"},
        )

    assert response.status_code == status.HTTP_202_ACCEPTED
    body = response.json()
    assert body["status"] == "ok"
    assert body["data"]["request_id"] == str(req_id)
    assert body["data"]["operation"] == "music_volume"
    assert body["data"]["request_state"] == "processing"
    assert body["data"]["status_url"] == f"/api/v1/requests/{req_id}"


@pytest.mark.asyncio
async def test_music_volume_conflict_409(
    auth_setup: tuple[Settings, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """POST volume with conflicting fingerprint returns 409 REQUEST_ID_CONFLICT."""
    settings, raw_token = auth_setup
    app = create_app(settings)
    app.dependency_overrides[get_db_session] = _dummy_get_db_session

    now = datetime.now(UTC)
    req_id = uuid4()
    existing_op = Operation(
        request_id=req_id,
        client_id="test-client-1",
        user_id="user-1",
        operation=OperationName.MUSIC_VOLUME,
        action=Action.MUSIC_VOLUME,
        params={"direction": "up"},
        request_fingerprint="c" * 64,
        request_state=RequestState.PROCESSING,
        delivery_state=DeliveryState.RECEIVED,
        callback_state=CallbackState.NOT_REQUIRED,
        created_at=now,
        updated_at=now,
        expires_at=now,
    )

    async def mock_accept_conflict(
        _self: OperationService,
        *,
        client_id: str,
        user_id: str,
        route: object,
        request: ServiceRequest,
    ) -> AcceptOperationResult:
        _ = (client_id, user_id, route, request)
        return AcceptOperationResult(
            status=AcceptOperationStatus.CONFLICT, operation=existing_op, inserted=False
        )

    async def mock_resolve(_repository: object, *, device_id: str) -> str:
        _ = device_id
        return "user-1"

    monkeypatch.setattr(OperationService, "accept", mock_accept_conflict)
    monkeypatch.setattr("app.api.service.resolve_glasses_device_owner", mock_resolve)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        payload = {"device_id": "glasses-1", "request_id": str(req_id), "level": 70}
        response = await client.post(
            "/api/v1/service/music/volume",
            json=payload,
            headers={"Authorization": f"Bearer {raw_token}"},
        )

    assert response.status_code == status.HTTP_409_CONFLICT
    body = response.json()
    assert body["status"] == "error"
    assert body["error"]["code"] == "REQUEST_ID_CONFLICT"
