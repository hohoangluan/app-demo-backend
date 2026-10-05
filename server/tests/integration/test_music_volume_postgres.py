"""PostgreSQL integration tests for POST /api/v1/service/music/volume and GET status."""

from __future__ import annotations

import hashlib
import hmac
import secrets
from datetime import UTC, datetime
from typing import TYPE_CHECKING
from uuid import uuid4

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text

from app.application import create_app
from app.config import AppEnvironment, DeliveryTransport, Settings
from app.database import get_db_session
from app.models.enums import GlassesLinkStatus
from app.models.glasses_device import GlassesDevice

if TYPE_CHECKING:
    from collections.abc import AsyncGenerator

    from sqlalchemy.ext.asyncio import AsyncSession

    from app.database import AsyncSessionFactory

_PUBLIC_API_DOMAIN = b"app-demo-auth/v1/public-api"


def _digest_hex(raw_token: str) -> str:
    return hmac.new(raw_token.encode("utf-8"), _PUBLIC_API_DOMAIN, hashlib.sha256).hexdigest()


@pytest.mark.asyncio
async def test_music_volume_vertical_slice_real_postgres(
    test_database_url: str,
    postgres_session_factory: AsyncSessionFactory,
) -> None:
    """Validate full HTTP path against real PostgreSQL database."""
    raw_token = secrets.token_urlsafe(32)
    token_hash = _digest_hex(raw_token)
    client_id = "real-postgres-client"

    settings = Settings.model_validate(
        {
            "app_env": AppEnvironment.TEST,
            "http_port": 8000,
            "database_url": test_database_url,
            "public_api_token_hash": token_hash,
            "public_api_client_id": client_id,
            "public_api_scopes": {"service:execute", "requests:read"},
            "device_api_token_hash": "2" * 64,
            "field_encryption_key": "test-only-field-key",
            "delivery_transport": DeliveryTransport.FAKE,
        }
    )

    app = create_app(settings)
    app.state.session_factory = postgres_session_factory

    async def _db_session_override() -> AsyncGenerator[AsyncSession]:
        async with postgres_session_factory() as session:
            yield session

    app.dependency_overrides[get_db_session] = _db_session_override

    async with postgres_session_factory() as seed_session:
        seed_session.add(
            GlassesDevice(
                user_id="user-100",
                device_id="glasses-100",
                status=GlassesLinkStatus.ACTIVE,
                linked_at=datetime.now(UTC),
            )
        )
        await seed_session.commit()

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        req_id = uuid4()
        post_payload = {"device_id": "glasses-100", "request_id": str(req_id), "level": 65}
        auth_headers = {"Authorization": f"Bearer {raw_token}"}

        # 1. POST valid music volume request -> 202
        post_resp = await client.post(
            "/api/v1/service/music/volume", json=post_payload, headers=auth_headers
        )
        assert post_resp.status_code == 202
        post_data = post_resp.json()
        assert post_data["status"] == "ok"
        assert post_data["data"]["request_id"] == str(req_id)
        assert post_data["data"]["operation"] == "music_volume"
        original_accepted_at = post_data["data"]["accepted_at"]

        # 2. Verify row exists in PostgreSQL operations table
        async with postgres_session_factory() as verify_session:
            row_count = (
                await verify_session.execute(
                    text("SELECT count(*) FROM operations WHERE request_id = :req_id"),
                    {"req_id": req_id},
                )
            ).scalar()
            assert row_count == 1

        # 3. GET status for accepted request -> 200 processing
        status_resp = await client.get(f"/api/v1/requests/{req_id}", headers=auth_headers)
        assert status_resp.status_code == 200
        status_data = status_resp.json()
        assert status_data["status"] == "ok"
        assert status_data["data"]["request_id"] == str(req_id)
        assert status_data["data"]["request_state"] == "processing"

        # 4. Sequential identical POST -> 202 with SAME accepted_at and NO second row
        dup_post_resp = await client.post(
            "/api/v1/service/music/volume", json=post_payload, headers=auth_headers
        )
        assert dup_post_resp.status_code == 202
        assert dup_post_resp.json()["data"]["accepted_at"] == original_accepted_at

        async with postgres_session_factory() as verify_session:
            row_count = (
                await verify_session.execute(
                    text("SELECT count(*) FROM operations WHERE request_id = :req_id"),
                    {"req_id": req_id},
                )
            ).scalar()
            assert row_count == 1

        # 5. Sequential conflicting POST -> 409
        conflict_payload = {"device_id": "glasses-100", "request_id": str(req_id), "level": 10}
        conflict_resp = await client.post(
            "/api/v1/service/music/volume", json=conflict_payload, headers=auth_headers
        )
        assert conflict_resp.status_code == 409
        assert conflict_resp.json()["error"]["code"] == "REQUEST_ID_CONFLICT"


@pytest.mark.asyncio
async def test_music_volume_unpaired_device_id_returns_404(
    test_database_url: str,
    postgres_session_factory: AsyncSessionFactory,
) -> None:
    """A device_id with no active glasses pairing is rejected before any operation exists."""
    raw_token = secrets.token_urlsafe(32)
    token_hash = _digest_hex(raw_token)

    settings = Settings.model_validate(
        {
            "app_env": AppEnvironment.TEST,
            "http_port": 8000,
            "database_url": test_database_url,
            "public_api_token_hash": token_hash,
            "public_api_client_id": "real-postgres-client",
            "public_api_scopes": {"service:execute", "requests:read"},
            "device_api_token_hash": "2" * 64,
            "field_encryption_key": "test-only-field-key",
            "delivery_transport": DeliveryTransport.FAKE,
        }
    )

    app = create_app(settings)
    app.state.session_factory = postgres_session_factory

    async def _db_session_override() -> AsyncGenerator[AsyncSession]:
        async with postgres_session_factory() as session:
            yield session

    app.dependency_overrides[get_db_session] = _db_session_override

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        payload = {"device_id": "glasses-never-linked", "request_id": str(uuid4()), "level": 50}
        response = await client.post(
            "/api/v1/service/music/volume",
            json=payload,
            headers={"Authorization": f"Bearer {raw_token}"},
        )

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "GLASSES_DEVICE_NOT_LINKED"
