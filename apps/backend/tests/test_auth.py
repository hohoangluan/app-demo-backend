"""Bearer authentication dependency tests.

Per docs/p1-api-plan.md: "Unit test dùng token giả sinh runtime, không đưa
credential cố định vào fixture/snapshot." Every raw token used below is
generated at test-run time with :func:`secrets.token_urlsafe`; none is a
fixed literal credential.

These dependencies have no router wired to them yet (that is a later
task), so each test mounts a minimal throwaway FastAPI app exercising the
dependency directly through HTTPX + ASGI transport, matching the project's
existing API-test transport convention.
"""

import hashlib
import hmac
import secrets
from typing import Annotated

from fastapi import Depends, FastAPI, status
from httpx import ASGITransport, AsyncClient

from app.auth import (
    ClientPrincipal,
    require_device_bearer_token,
    require_public_client_principal,
    require_public_scope,
)
from app.config import AppEnvironment, DeliveryTransport, PublicApiScope, Settings

# Domain-separation messages exactly as documented (Public) and as chosen by
# app/auth.py's judgment call (Device) -- see that module's docstring. These
# are public protocol constants, not secrets, so hardcoding them here to
# compute an expected/matching digest is a black-box test of the documented
# algorithm, not a leak of implementation detail.
_PUBLIC_API_DOMAIN = b"app-demo-auth/v1/public-api"
_DEVICE_API_DOMAIN = b"app-demo-auth/v1/device-api"


def _digest_hex(raw_token: str, domain: bytes) -> str:
    """Compute the lowercase-hex HMAC-SHA256 digest for one raw token."""
    return hmac.new(raw_token.encode("utf-8"), domain, hashlib.sha256).hexdigest()


def _build_settings(
    *, public_api_token_hash: str, device_api_token_hash: str, public_api_scopes: frozenset[str]
) -> Settings:
    """Build a complete, valid ``Settings`` for one test's generated tokens."""
    return Settings.model_validate(
        {
            "app_env": AppEnvironment.TEST,
            "http_port": 8000,
            "database_url": "postgresql+asyncpg://test:test@localhost:5432/app_test",
            "public_api_token_hash": public_api_token_hash,
            "public_api_client_id": "auth-test-client",
            "public_api_scopes": public_api_scopes,
            "device_api_token_hash": device_api_token_hash,
            "field_encryption_key": "test-only-field-key",
            "delivery_transport": DeliveryTransport.FAKE,
        }
    )


def _build_probe_app(settings: Settings) -> FastAPI:
    """Mount throwaway routes exercising each auth dependency directly."""
    app = FastAPI()
    app.state.settings = settings

    @app.get("/probe/principal")
    async def probe_principal(
        principal: Annotated[ClientPrincipal, Depends(require_public_client_principal)],
    ) -> dict[str, object]:
        return {"client_id": principal.client_id, "scopes": sorted(principal.scopes)}

    @app.get(
        "/probe/scoped",
        dependencies=[Depends(require_public_scope(PublicApiScope.SERVICE_EXECUTE))],
    )
    async def probe_scoped() -> dict[str, bool]:
        return {"ok": True}

    @app.get("/probe/device", dependencies=[Depends(require_device_bearer_token)])
    async def probe_device() -> dict[str, bool]:
        return {"ok": True}

    return app


async def test_valid_public_token_produces_principal_with_configured_scopes() -> None:
    """A correctly-digested Bearer token authenticates with the configured scopes."""
    raw_token = secrets.token_urlsafe(32)
    settings = _build_settings(
        public_api_token_hash=_digest_hex(raw_token, _PUBLIC_API_DOMAIN),
        device_api_token_hash="2" * 64,
        public_api_scopes=frozenset({"service:execute", "requests:read"}),
    )
    transport = ASGITransport(app=_build_probe_app(settings))

    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        response = await client.get(
            "/probe/principal", headers={"Authorization": f"Bearer {raw_token}"}
        )

    assert response.status_code == status.HTTP_200_OK
    assert response.json() == {
        "client_id": "auth-test-client",
        "scopes": ["requests:read", "service:execute"],
    }


async def test_missing_authorization_header_returns_401() -> None:
    """A request with no Authorization header is rejected with a Bearer challenge."""
    settings = _build_settings(
        public_api_token_hash="1" * 64,
        device_api_token_hash="2" * 64,
        public_api_scopes=frozenset({"service:execute"}),
    )
    transport = ASGITransport(app=_build_probe_app(settings))

    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        response = await client.get("/probe/principal")

    assert response.status_code == status.HTTP_401_UNAUTHORIZED
    assert response.headers["www-authenticate"] == "Bearer"


async def test_wrong_public_token_returns_401() -> None:
    """A well-formed but non-matching Bearer token is rejected identically to a missing one."""
    raw_token = secrets.token_urlsafe(32)
    wrong_token = secrets.token_urlsafe(32)
    settings = _build_settings(
        public_api_token_hash=_digest_hex(raw_token, _PUBLIC_API_DOMAIN),
        device_api_token_hash="2" * 64,
        public_api_scopes=frozenset({"service:execute"}),
    )
    transport = ASGITransport(app=_build_probe_app(settings))

    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        response = await client.get(
            "/probe/principal", headers={"Authorization": f"Bearer {wrong_token}"}
        )

    assert response.status_code == status.HTTP_401_UNAUTHORIZED


async def test_authenticated_principal_missing_required_scope_returns_403() -> None:
    """A valid principal lacking the required scope is forbidden, not unauthorized."""
    raw_token = secrets.token_urlsafe(32)
    settings = _build_settings(
        public_api_token_hash=_digest_hex(raw_token, _PUBLIC_API_DOMAIN),
        device_api_token_hash="2" * 64,
        public_api_scopes=frozenset({"requests:read"}),
    )
    transport = ASGITransport(app=_build_probe_app(settings))

    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        response = await client.get(
            "/probe/scoped", headers={"Authorization": f"Bearer {raw_token}"}
        )

    assert response.status_code == status.HTTP_403_FORBIDDEN


async def test_valid_device_token_authenticates() -> None:
    """A correctly-digested Device Bearer token is accepted."""
    raw_token = secrets.token_urlsafe(32)
    settings = _build_settings(
        public_api_token_hash="1" * 64,
        device_api_token_hash=_digest_hex(raw_token, _DEVICE_API_DOMAIN),
        public_api_scopes=frozenset({"service:execute"}),
    )
    transport = ASGITransport(app=_build_probe_app(settings))

    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        response = await client.get(
            "/probe/device", headers={"Authorization": f"Bearer {raw_token}"}
        )

    assert response.status_code == status.HTTP_200_OK
    assert response.json() == {"ok": True}


async def test_wrong_device_token_returns_401() -> None:
    """A well-formed but non-matching Device Bearer token is rejected."""
    raw_token = secrets.token_urlsafe(32)
    wrong_token = secrets.token_urlsafe(32)
    settings = _build_settings(
        public_api_token_hash="1" * 64,
        device_api_token_hash=_digest_hex(raw_token, _DEVICE_API_DOMAIN),
        public_api_scopes=frozenset({"service:execute"}),
    )
    transport = ASGITransport(app=_build_probe_app(settings))

    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        response = await client.get(
            "/probe/device", headers={"Authorization": f"Bearer {wrong_token}"}
        )

    assert response.status_code == status.HTTP_401_UNAUTHORIZED
