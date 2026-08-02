"""Structured logging, sensitive data redaction, and request correlation middleware."""

from __future__ import annotations

import contextvars
import json
import logging
import uuid
from typing import TYPE_CHECKING, Final

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint

if TYPE_CHECKING:
    from fastapi import Request, Response
    from starlette.types import ASGIApp

    from app.config import LogLevel

# Context variable tracking the current request correlation ID across async execution.
request_id_ctx: contextvars.ContextVar[str | None] = contextvars.ContextVar(
    "request_id_ctx", default=None
)

REDACTED_SUBSTITUTE: Final[str] = "[REDACTED]"
REDACTED_KEYS: Final[set[str]] = {
    "authorization",
    "token",
    "bearer",
    "secret",
    "password",
    "push_token",
    "field_encryption_key",
    "public_api_token_hash",
    "device_api_token_hash",
}


def redact_sensitive_data(data: object) -> object:
    """Recursively redact sensitive key values from dicts/lists for safe logging."""
    if isinstance(data, dict):
        redacted: dict[str, object] = {}
        for key, value in data.items():
            if str(key).lower() in REDACTED_KEYS or any(
                secret_word in str(key).lower() for secret_word in ("token", "secret", "password")
            ):
                redacted[key] = REDACTED_SUBSTITUTE
            else:
                redacted[key] = redact_sensitive_data(value)
        return redacted
    if isinstance(data, list | tuple):
        return [redact_sensitive_data(item) for item in data]
    return data


class StructuredJsonFormatter(logging.Formatter):
    """JSON log formatter attaching request correlation context and redacting sensitive data."""

    def format(self, record: logging.LogRecord) -> str:
        """Format the log record as a structured JSON string."""
        log_payload: dict[str, object] = {
            "timestamp": self.formatTime(record, self.datefmt),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }

        req_id = request_id_ctx.get()
        if req_id:
            log_payload["request_id"] = req_id

        if record.exc_info:
            log_payload["exception"] = self.formatException(record.exc_info)

        # Attach extra fields passed to logger if present
        extra_keys = set(record.__dict__.keys()) - {
            "name",
            "msg",
            "args",
            "levelname",
            "levelno",
            "pathname",
            "filename",
            "module",
            "exc_info",
            "exc_text",
            "stack_info",
            "lineno",
            "funcName",
            "created",
            "msecs",
            "relativeCreated",
            "thread",
            "threadName",
            "processName",
            "process",
            "message",
        }
        for key in extra_keys:
            val = getattr(record, key)
            if str(key).lower() in REDACTED_KEYS or any(
                secret_word in str(key).lower() for secret_word in ("token", "secret", "password")
            ):
                log_payload[key] = REDACTED_SUBSTITUTE
            else:
                log_payload[key] = redact_sensitive_data(val)

        return json.dumps(log_payload, ensure_ascii=False)


class RequestCorrelationMiddleware(BaseHTTPMiddleware):
    """Middleware attaching/propagating X-Request-ID headers and tracking request context."""

    def __init__(self, app: ASGIApp) -> None:
        """Initialize the request correlation middleware."""
        super().__init__(app)

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        """Process request, extract or generate request_id, set contextvar, and attach header."""
        header_req_id = request.headers.get("x-request-id")
        correlation_id = header_req_id or str(uuid.uuid4())

        token = request_id_ctx.set(correlation_id)
        try:
            response = await call_next(request)
            response.headers["x-request-id"] = correlation_id
            return response
        finally:
            request_id_ctx.reset(token)


def setup_logging(log_level: LogLevel | str = "INFO") -> None:
    """Configure system-wide structured logging handler and format."""
    level_str = log_level.value if hasattr(log_level, "value") else str(log_level)
    numeric_level = getattr(logging, level_str.upper(), logging.INFO)

    root_logger = logging.getLogger()
    root_logger.setLevel(numeric_level)

    # Avoid duplicate handlers if setup_logging is called repeatedly
    root_logger.handlers.clear()

    console_handler = logging.StreamHandler()
    console_handler.setFormatter(StructuredJsonFormatter())
    root_logger.addHandler(console_handler)
