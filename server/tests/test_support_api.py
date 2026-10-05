"""End-to-end PostgreSQL-backed tests for the support ticket API."""

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


async def test_submit_feedback_ticket_is_acknowledged(
    db_client: AsyncClient, caplog: pytest.LogCaptureFixture
) -> None:
    """Accept a feedback ticket and echo back its category."""
    token = await _create_verified_session(db_client, caplog, "0941111111")
    response = await db_client.post(
        "/api/v1/support/tickets",
        json={"category": "feedback", "message": "Ứng dụng bị treo khi mở màn Hỗ trợ."},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["category"] == "feedback"
    assert "id" in data
    assert "created_at" in data


async def test_submit_support_request_ticket_is_acknowledged(
    db_client: AsyncClient, caplog: pytest.LogCaptureFixture
) -> None:
    """Accept a support-request ticket."""
    token = await _create_verified_session(db_client, caplog, "0942222222")
    response = await db_client.post(
        "/api/v1/support/tickets",
        json={"category": "support_request", "message": "Cần hỗ trợ liên kết lại kính."},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200
    assert response.json()["data"]["category"] == "support_request"


async def test_submit_ticket_rejects_empty_message(
    db_client: AsyncClient, caplog: pytest.LogCaptureFixture
) -> None:
    """Reject a ticket whose message is empty after trimming."""
    token = await _create_verified_session(db_client, caplog, "0943333333")
    response = await db_client.post(
        "/api/v1/support/tickets",
        json={"category": "feedback", "message": "   "},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_REQUEST"


async def test_submit_ticket_requires_session_bearer(db_client: AsyncClient) -> None:
    """Reject a ticket submission with no session Bearer token."""
    response = await db_client.post(
        "/api/v1/support/tickets", json={"category": "feedback", "message": "test"}
    )
    assert response.status_code == 401
