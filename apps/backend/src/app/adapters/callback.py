"""HTTP Callback adapter with SSRF protection and retry classification.

Per ``architeture.md`` section 8.5, 9.4, 13.3 and ``CONTRACT_DECISIONS.md`` ``D-02``/``D-12``:
- Posts full terminal status envelope to configured callback URL.
- Enforces SSRF allow-list validation on callback host.
- Adds ``Authorization: Bearer <token>`` and ``Idempotency-Key: {request_id}``.
- Classifies HTTP 2xx as success, 408/429/5xx as retryable, and other 4xx as dead letter.
"""

from __future__ import annotations

import ipaddress
import socket
from dataclasses import dataclass
from enum import StrEnum
from typing import TYPE_CHECKING
from urllib.parse import urlparse

import httpx

if TYPE_CHECKING:
    from app.config import Settings
    from app.models.operation import Operation

_HTTP_OK_MIN = 200
_HTTP_OK_MAX = 300
_HTTP_SERVER_ERROR_MIN = 500


class CallbackOutcomeStatus(StrEnum):
    """Possible outcomes of an HTTP callback attempt."""

    DELIVERED = "delivered"
    RETRY = "retry"
    DEAD_LETTER = "dead_letter"


@dataclass(frozen=True, slots=True)
class CallbackOutcome:
    """Result of attempting an HTTP webhook callback."""

    status: CallbackOutcomeStatus
    http_status_code: int | None = None
    error_reason: str | None = None


class SsfValidationError(ValueError):
    """Raised when a callback URL fails SSRF security checks."""


def validate_callback_url(url: str, allowed_hosts: list[str]) -> None:
    """Validate callback URL against SSRF protection rules.

    Per ``architeture.md`` section 13.3:
    - Host must be explicitly present in ``allowed_hosts``.
    - Loopback, private, and link-local IP addresses are rejected unless in ``allowed_hosts``.
    """
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        msg = f"Invalid URL scheme: {parsed.scheme}"
        raise SsfValidationError(msg)

    hostname = parsed.hostname
    if not hostname:
        msg = "Callback URL missing hostname"
        raise SsfValidationError(msg)

    if allowed_hosts and hostname not in allowed_hosts:
        msg = f"Host '{hostname}' is not in CALLBACK_ALLOWED_HOSTS"
        raise SsfValidationError(msg)

    try:
        ip = ipaddress.ip_address(hostname)
        if (ip.is_private or ip.is_loopback or ip.is_link_local) and (
            not allowed_hosts or hostname not in allowed_hosts
        ):
            msg = f"Forbidden IP target: {hostname}"
            raise SsfValidationError(msg)
    except ValueError:
        pass


class CallbackAdapter:
    """HTTP webhook callback delivery adapter."""

    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        """Initialize CallbackAdapter with settings and optional HTTPX client."""
        self._settings = settings
        self._client = client

    async def send_callback(self, operation: Operation) -> CallbackOutcome:
        """Post terminal status representation to callback URL."""
        if not self._settings.callback_url:
            return CallbackOutcome(
                status=CallbackOutcomeStatus.DEAD_LETTER,
                error_reason="CALLBACK_URL is not configured",
            )

        url_str = str(self._settings.callback_url)
        allowed_hosts = (
            list(self._settings.callback_allowed_hosts)
            if self._settings.callback_allowed_hosts
            else []
        )

        try:
            validate_callback_url(url_str, allowed_hosts)
        except SsfValidationError as exc:
            return CallbackOutcome(
                status=CallbackOutcomeStatus.DEAD_LETTER,
                error_reason=f"SSRF validation failed: {exc}",
            )

        payload = {
            "status": "ok",
            "data": {
                "request_id": str(operation.request_id),
                "operation": operation.operation.value,
                "request_state": operation.request_state.value,
                "result": operation.result,
                "error": operation.error,
                "created_at": operation.created_at.isoformat(),
                "updated_at": operation.updated_at.isoformat(),
            },
        }

        headers = {
            "Content-Type": "application/json",
            "Idempotency-Key": str(operation.request_id),
        }
        if self._settings.callback_token:
            headers["Authorization"] = f"Bearer {self._settings.callback_token.get_secret_value()}"

        client = self._client or httpx.AsyncClient(timeout=10.0)
        close_client = self._client is None

        try:
            response = await client.post(url_str, json=payload, headers=headers)
            status_code = response.status_code

            if _HTTP_OK_MIN <= status_code < _HTTP_OK_MAX:
                return CallbackOutcome(
                    status=CallbackOutcomeStatus.DELIVERED,
                    http_status_code=status_code,
                )
            if status_code in {408, 429} or status_code >= _HTTP_SERVER_ERROR_MIN:
                return CallbackOutcome(
                    status=CallbackOutcomeStatus.RETRY,
                    http_status_code=status_code,
                    error_reason=f"HTTP {status_code}",
                )
            return CallbackOutcome(
                status=CallbackOutcomeStatus.DEAD_LETTER,
                http_status_code=status_code,
                error_reason=f"HTTP {status_code} permanent error",
            )
        except (OSError, httpx.HTTPError) as exc:
            return CallbackOutcome(
                status=CallbackOutcomeStatus.RETRY,
                error_reason=f"Network error: {exc}",
            )
        finally:
            if close_client:
                await client.aclose()
