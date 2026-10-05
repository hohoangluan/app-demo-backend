"""Tests for logging formatting, sensitive data redaction, and request correlation middleware."""

import json
import logging
from io import StringIO
from typing import Any, cast

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from app.logging import (
    REDACTED_SUBSTITUTE,
    RequestCorrelationMiddleware,
    StructuredJsonFormatter,
    redact_sensitive_data,
    request_id_ctx,
)


def test_redact_sensitive_data_masks_tokens_and_secrets() -> None:
    """Keys matching secret terms (token, secret, password, authorization) are replaced."""
    payload = {
        "user_id": "user-123",
        "authorization": "Bearer secret_token_xyz",
        "push_token": "fcm_token_12345",
        "nested": {
            "password": "my_password",
            "safe_key": "safe_value",
        },
    }
    redacted = cast("dict[str, Any]", redact_sensitive_data(payload))
    assert redacted["user_id"] == "user-123"
    assert redacted["authorization"] == REDACTED_SUBSTITUTE
    assert redacted["push_token"] == REDACTED_SUBSTITUTE
    assert redacted["nested"]["password"] == REDACTED_SUBSTITUTE
    assert redacted["nested"]["safe_key"] == "safe_value"


def test_structured_json_formatter_includes_correlation_id() -> None:
    """StructuredJsonFormatter attaches request_id from contextvar to the JSON output."""
    buffer = StringIO()
    handler = logging.StreamHandler(buffer)
    handler.setFormatter(StructuredJsonFormatter())

    test_logger = logging.getLogger("test_logger")
    test_logger.addHandler(handler)
    test_logger.setLevel(logging.INFO)

    token = request_id_ctx.set("corr-12345")
    try:
        test_logger.info("Test log message", extra={"user_id": "u1", "push_token": "secret_tok"})
    finally:
        request_id_ctx.reset(token)

    output = buffer.getvalue().strip()
    data = json.loads(output)

    assert data["message"] == "Test log message"
    assert data["request_id"] == "corr-12345"
    assert data["user_id"] == "u1"
    assert data["push_token"] == REDACTED_SUBSTITUTE


@pytest.mark.asyncio
async def test_request_correlation_middleware_propagates_header() -> None:
    """RequestCorrelationMiddleware propagates existing X-Request-ID or generates one."""
    app = FastAPI()
    app.add_middleware(RequestCorrelationMiddleware)

    @app.get("/test-endpoint")
    async def endpoint() -> dict[str, str | None]:
        return {"ctx_request_id": request_id_ctx.get()}

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        # Case 1: Custom header passed
        resp1 = await client.get("/test-endpoint", headers={"X-Request-ID": "custom-id-999"})
        assert resp1.status_code == 200
        assert resp1.headers["x-request-id"] == "custom-id-999"
        assert resp1.json()["ctx_request_id"] == "custom-id-999"

        # Case 2: No header passed -> generated UUID
        resp2 = await client.get("/test-endpoint")
        assert resp2.status_code == 200
        generated_id = resp2.headers["x-request-id"]
        assert len(generated_id) > 10
        assert resp2.json()["ctx_request_id"] == generated_id
