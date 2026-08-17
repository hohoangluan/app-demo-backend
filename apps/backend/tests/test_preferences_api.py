"""End-to-end PostgreSQL-backed tests for the accessibility preferences API."""

from __future__ import annotations

import logging
import re
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import pytest
    from httpx import AsyncClient


def _extract_otp(log_text: str, phone_number: str) -> str:
    match = re.search(rf"Demo OTP for {re.escape(phone_number)}: (\d{{6}})", log_text)
    assert match is not None, f"no logged OTP found for {phone_number} in: {log_text!r}"
    return match.group(1)


async def _create_verified_session(
    client: AsyncClient, caplog: pytest.LogCaptureFixture, phone_number: str
) -> str:
    """Register, verify OTP, and return an access token for `phone_number`."""
    caplog.clear()
    with caplog.at_level(logging.INFO, logger="app.api.auth"):
        register_response = await client.post(
            "/api/v1/auth/register",
            json={"phone_number": phone_number, "password": "correct-horse"},
        )
    assert register_response.status_code == 200, register_response.text
    otp_code = _extract_otp(caplog.text, phone_number)

    verify_response = await client.post(
        "/api/v1/auth/otp/verify",
        json={"phone_number": phone_number, "otp_code": otp_code},
    )
    assert verify_response.status_code == 200, verify_response.text
    return str(verify_response.json()["data"]["access_token"])


async def test_get_preferences_returns_documented_defaults(
    db_client: AsyncClient, caplog: pytest.LogCaptureFixture
) -> None:
    """Return the documented default preferences for a freshly registered account."""
    token = await _create_verified_session(db_client, caplog, "0931111111")
    response = await db_client.get(
        "/api/v1/preferences", headers={"Authorization": f"Bearer {token}"}
    )
    assert response.status_code == 200
    assert response.json()["data"] == {
        "font_size_option": "Vừa",
        "voice_option": "Giọng Nữ",
        "high_contrast": False,
        "haptics_enabled": True,
    }


async def test_get_preferences_requires_session_bearer(db_client: AsyncClient) -> None:
    """Reject a preferences read with no session Bearer token."""
    response = await db_client.get("/api/v1/preferences")
    assert response.status_code == 401


async def test_update_preferences_persists_and_returns_new_values(
    db_client: AsyncClient, caplog: pytest.LogCaptureFixture
) -> None:
    """Persist an update and return it, then read it back on a fresh request."""
    token = await _create_verified_session(db_client, caplog, "0932222222")
    headers = {"Authorization": f"Bearer {token}"}
    body = {
        "font_size_option": "To",
        "voice_option": "Giọng Nam",
        "high_contrast": True,
        "haptics_enabled": False,
    }

    update_response = await db_client.put("/api/v1/preferences", json=body, headers=headers)
    assert update_response.status_code == 200
    assert update_response.json()["data"] == body

    read_response = await db_client.get("/api/v1/preferences", headers=headers)
    assert read_response.json()["data"] == body


async def test_update_preferences_rejects_unknown_font_size_option(
    db_client: AsyncClient, caplog: pytest.LogCaptureFixture
) -> None:
    """Reject an update with a font_size_option outside the documented enum."""
    token = await _create_verified_session(db_client, caplog, "0933333333")
    response = await db_client.put(
        "/api/v1/preferences",
        json={
            "font_size_option": "Khổng lồ",
            "voice_option": "Giọng Nữ",
            "high_contrast": False,
            "haptics_enabled": True,
        },
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_REQUEST"
