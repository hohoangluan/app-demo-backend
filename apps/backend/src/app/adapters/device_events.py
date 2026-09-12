"""Forward spontaneous Android events to the glasses server."""

from __future__ import annotations

from typing import TYPE_CHECKING
from urllib.parse import urlsplit, urlunsplit

import httpx

from app.adapters.callback import validate_callback_url

if TYPE_CHECKING:
    from app.config import Settings


class DeviceEventAdapter:
    """Use the configured callback origin/token but a request-id-free endpoint."""

    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        """Bind validated settings and an optional reusable HTTP client."""
        self._settings = settings
        self._client = client

    async def forward(self, payload: dict[str, object]) -> None:
        """POST one privacy-filtered event to the glasses server."""
        if self._settings.callback_url is None or self._settings.callback_token is None:
            message = "callback endpoint is not configured"
            raise RuntimeError(message)

        parsed = urlsplit(str(self._settings.callback_url))
        url = urlunsplit((parsed.scheme, parsed.netloc, "/internal/device-events", "", ""))
        allowed_hosts = [
            host.strip()
            for host in (self._settings.callback_allowed_hosts or "").split(",")
            if host.strip()
        ]
        validate_callback_url(url, allowed_hosts)
        headers = {
            "Authorization": f"Bearer {self._settings.callback_token.get_secret_value()}",
            "Content-Type": "application/json",
        }
        client = self._client or httpx.AsyncClient(timeout=5.0)
        close_client = self._client is None
        try:
            response = await client.post(url, json=payload, headers=headers)
            response.raise_for_status()
        finally:
            if close_client:
                await client.aclose()
