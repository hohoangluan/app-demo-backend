"""End-to-end PostgreSQL-backed tests for the demo phone-app auth + device-link API.

Every raw token used below (device Bearer, session Bearer) is generated or
captured at test-run time -- none is a fixed literal credential, matching
`tests/test_auth.py`'s convention. The OTP code is never returned by the API
(only logged server-side, since there is no SMS provider); tests recover it
from `caplog`, exercising the exact channel a real operator would read it
from during a demo.
"""

from __future__ import annotations

import hashlib
import hmac
import logging
import re
import secrets
from typing import TYPE_CHECKING, Any

import pytest
from httpx import ASGITransport, AsyncClient

from app.application import create_app
from app.config import AppEnvironment, DeliveryTransport, Settings
from app.database import build_session_factory

if TYPE_CHECKING:
    from collections.abc import AsyncIterator

    from sqlalchemy.ext.asyncio import AsyncEngine

_DEVICE_API_DOMAIN = b"app-demo-auth/v1/device-api"


def _digest_hex(raw_token: str, domain: bytes) -> str:
    return hmac.new(raw_token.encode("utf-8"), domain, hashlib.sha256).hexdigest()


def _build_settings(device_raw_token: str) -> Settings:
    """Build valid `Settings` whose device hash matches `device_raw_token`."""
    return Settings.model_validate(
        {
            "app_env": AppEnvironment.TEST,
            "http_port": 8000,
            "database_url": "postgresql+asyncpg://test:test@localhost:5432/app_test",
            "public_api_token_hash": "1" * 64,
            "public_api_client_id": "auth-api-test-client",
            "public_api_scopes": {"service:execute", "requests:read"},
            "device_api_token_hash": _digest_hex(device_raw_token, _DEVICE_API_DOMAIN),
            "field_encryption_key": "test-only-field-key",
            "delivery_transport": DeliveryTransport.FAKE,
        }
    )


@pytest.fixture
def device_raw_token() -> str:
    """Generate a fresh raw Device Bearer token at test-run time."""
    return secrets.token_urlsafe(24)


@pytest.fixture
async def auth_client(
    db_engine: AsyncEngine,
    clean_auth_tables: None,  # noqa: ARG001
    device_raw_token: str,
) -> AsyncIterator[AsyncClient]:
    """Yield an HTTP client for a real-PostgreSQL-backed app with a known device token."""
    application = create_app(_build_settings(device_raw_token))
    application.state.session_factory = build_session_factory(db_engine)
    transport = ASGITransport(app=application)
    async with AsyncClient(transport=transport, base_url="http://testserver") as test_client:
        yield test_client


def _extract_otp(log_text: str, phone_number: str) -> str:
    """Recover the OTP code logged for `phone_number` by `app.api.auth.register`."""
    match = re.search(rf"Demo OTP for {re.escape(phone_number)}: (\d{{6}})", log_text)
    assert match is not None, f"no logged OTP found for {phone_number} in: {log_text!r}"
    return match.group(1)


async def _register(
    client: AsyncClient, caplog: pytest.LogCaptureFixture, *, phone_number: str, password: str
) -> tuple[dict[str, Any], str]:
    caplog.clear()
    with caplog.at_level(logging.INFO, logger="app.api.auth"):
        response = await client.post(
            "/api/v1/auth/register",
            json={"phone_number": phone_number, "password": password},
        )
    assert response.status_code == 200, response.text
    body = response.json()
    otp_code = _extract_otp(caplog.text, body["data"]["phone_number"])
    return body, otp_code


async def _create_verified_session(
    client: AsyncClient, caplog: pytest.LogCaptureFixture, *, phone_number: str, password: str
) -> tuple[str, str]:
    """Register, verify OTP, and return `(access_token, public_user_id)`."""
    register_body, otp_code = await _register(
        client, caplog, phone_number=phone_number, password=password
    )
    verify_response = await client.post(
        "/api/v1/auth/otp/verify",
        json={"phone_number": phone_number, "otp_code": otp_code},
    )
    assert verify_response.status_code == 200, verify_response.text
    data = verify_response.json()["data"]
    return data["access_token"], register_body["data"]["public_user_id"]


async def _register_device(
    client: AsyncClient, device_raw_token: str, *, user_id: str, device_id: str
) -> None:
    response = await client.post(
        "/api/v1/device/register",
        headers={"Authorization": f"Bearer {device_raw_token}"},
        json={
            "user_id": user_id,
            "device_id": device_id,
            "platform": "android",
            "push_token": "fcm-demo-token",
        },
    )
    assert response.status_code == 200, response.text


async def test_register_returns_pending_otp_account(
    auth_client: AsyncClient, caplog: pytest.LogCaptureFixture
) -> None:
    """Return a pending-verification account and log a 6-digit OTP."""
    body, otp_code = await _register(
        auth_client, caplog, phone_number="0901111111", password="correct-horse"
    )
    assert body["status"] == "ok"
    assert body["data"]["otp_required"] is True
    assert body["data"]["phone_number"] == "0901111111"
    assert len(body["data"]["public_user_id"]) > 0
    assert len(otp_code) == 6


async def test_register_duplicate_verified_phone_returns_409(
    auth_client: AsyncClient, caplog: pytest.LogCaptureFixture
) -> None:
    """Reject re-registering a phone number that already completed OTP verification."""
    await _create_verified_session(
        auth_client, caplog, phone_number="0902222222", password="correct-horse"
    )
    response = await auth_client.post(
        "/api/v1/auth/register",
        json={"phone_number": "0902222222", "password": "another-password"},
    )
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "PHONE_ALREADY_REGISTERED"


async def test_register_unverified_phone_allows_resend(
    auth_client: AsyncClient, caplog: pytest.LogCaptureFixture
) -> None:
    """Allow re-registering an unverified phone number and issue a new OTP."""
    _, first_otp = await _register(
        auth_client, caplog, phone_number="0903333333", password="correct-horse"
    )
    _, second_otp = await _register(
        auth_client, caplog, phone_number="0903333333", password="correct-horse"
    )
    assert first_otp != second_otp

    verify_response = await auth_client.post(
        "/api/v1/auth/otp/verify",
        json={"phone_number": "0903333333", "otp_code": second_otp},
    )
    assert verify_response.status_code == 200


async def test_verify_otp_wrong_code_returns_400(
    auth_client: AsyncClient, caplog: pytest.LogCaptureFixture
) -> None:
    """Reject an OTP code that does not match the stored hash."""
    await _register(auth_client, caplog, phone_number="0904444444", password="correct-horse")
    response = await auth_client.post(
        "/api/v1/auth/otp/verify",
        json={"phone_number": "0904444444", "otp_code": "000000"},
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "OTP_INVALID"


async def test_verify_otp_unknown_phone_returns_expired(auth_client: AsyncClient) -> None:
    """Report `OTP_EXPIRED` when no pending OTP exists for the phone number."""
    response = await auth_client.post(
        "/api/v1/auth/otp/verify",
        json={"phone_number": "0909999999", "otp_code": "123456"},
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "OTP_EXPIRED"


async def test_login_wrong_password_returns_401(
    auth_client: AsyncClient, caplog: pytest.LogCaptureFixture
) -> None:
    """Reject login with a wrong password for a verified account."""
    await _create_verified_session(
        auth_client, caplog, phone_number="0905555555", password="correct-horse"
    )
    response = await auth_client.post(
        "/api/v1/auth/login",
        json={"phone_number": "0905555555", "password": "wrong-password"},
    )
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "INVALID_CREDENTIALS"


async def test_login_unverified_phone_returns_403(
    auth_client: AsyncClient, caplog: pytest.LogCaptureFixture
) -> None:
    """Reject login for an account that never completed OTP verification."""
    await _register(auth_client, caplog, phone_number="0906666666", password="correct-horse")
    response = await auth_client.post(
        "/api/v1/auth/login",
        json={"phone_number": "0906666666", "password": "correct-horse"},
    )
    assert response.status_code == 403
    assert response.json()["error"]["code"] == "PHONE_NOT_VERIFIED"


async def test_login_success_issues_new_session(
    auth_client: AsyncClient, caplog: pytest.LogCaptureFixture
) -> None:
    """Issue a fresh session on successful login."""
    await _create_verified_session(
        auth_client, caplog, phone_number="0907777777", password="correct-horse"
    )
    response = await auth_client.post(
        "/api/v1/auth/login",
        json={"phone_number": "0907777777", "password": "correct-horse"},
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["phone_number"] == "0907777777"
    assert len(data["access_token"]) > 0


async def test_logout_revokes_the_session(
    auth_client: AsyncClient, caplog: pytest.LogCaptureFixture
) -> None:
    """Reject the old token for a protected call after logout."""
    access_token, _ = await _create_verified_session(
        auth_client, caplog, phone_number="0908888888", password="correct-horse"
    )
    logout_response = await auth_client.post(
        "/api/v1/auth/logout", headers={"Authorization": f"Bearer {access_token}"}
    )
    assert logout_response.status_code == 200

    reused_response = await auth_client.post(
        "/api/v1/device/link",
        headers={"Authorization": f"Bearer {access_token}"},
        json={"device_id": "device-does-not-matter"},
    )
    assert reused_response.status_code == 401


async def test_device_link_requires_session_bearer(auth_client: AsyncClient) -> None:
    """Reject a device-link request with no session Bearer token."""
    response = await auth_client.post("/api/v1/device/link", json={"device_id": "device-100"})
    assert response.status_code == 401


async def test_device_link_not_found_for_unregistered_device(
    auth_client: AsyncClient, caplog: pytest.LogCaptureFixture
) -> None:
    """Report `DEVICE_NOT_FOUND` for a device_id with no active registration."""
    access_token, _ = await _create_verified_session(
        auth_client, caplog, phone_number="0911111111", password="correct-horse"
    )
    response = await auth_client.post(
        "/api/v1/device/link",
        headers={"Authorization": f"Bearer {access_token}"},
        json={"device_id": "device-never-registered"},
    )
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "DEVICE_NOT_FOUND"


async def test_device_link_succeeds_for_matching_public_user_id(
    auth_client: AsyncClient, caplog: pytest.LogCaptureFixture, device_raw_token: str
) -> None:
    """Confirm the link when the Android device is registered under the caller's account."""
    access_token, public_user_id = await _create_verified_session(
        auth_client, caplog, phone_number="0912222222", password="correct-horse"
    )
    await _register_device(
        auth_client, device_raw_token, user_id=public_user_id, device_id="device-abc"
    )

    response = await auth_client.post(
        "/api/v1/device/link",
        headers={"Authorization": f"Bearer {access_token}"},
        json={"device_id": "device-abc"},
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data == {"device_id": "device-abc", "platform": "android", "linked": True}


async def test_device_link_conflict_for_another_accounts_device(
    auth_client: AsyncClient, caplog: pytest.LogCaptureFixture, device_raw_token: str
) -> None:
    """Reject linking a device that is already active under a different account."""
    _, owner_public_user_id = await _create_verified_session(
        auth_client, caplog, phone_number="0913333333", password="correct-horse"
    )
    await _register_device(
        auth_client, device_raw_token, user_id=owner_public_user_id, device_id="device-xyz"
    )

    other_access_token, _ = await _create_verified_session(
        auth_client, caplog, phone_number="0914444444", password="correct-horse"
    )
    response = await auth_client.post(
        "/api/v1/device/link",
        headers={"Authorization": f"Bearer {other_access_token}"},
        json={"device_id": "device-xyz"},
    )
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "DEVICE_OWNER_CONFLICT"
