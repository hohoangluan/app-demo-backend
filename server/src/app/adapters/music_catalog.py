"""Spotify catalog lookup: turns a free-text song into a ``spotify:`` URI for the phone.

Without credentials, or when Spotify is unreachable, it falls back to a
``spotify:search:<query>`` URI so the phone can still search on its own.
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Final
from urllib.parse import quote

import httpx

if TYPE_CHECKING:
    from app.config import Settings

logger = logging.getLogger(__name__)

_TOKEN_URL: Final = "https://accounts.spotify.com/api/token"  # noqa: S105
_SEARCH_URL: Final = "https://api.spotify.com/v1/search"
_TIMEOUT_SECONDS: Final = 3.0
_CACHE_LIMIT: Final = 500

# Process-wide cache of successful lookups, keyed by the normalized song text.
_uri_cache: dict[str, str] = {}


def search_uri(song: str) -> str:
    """Return the fallback URI that asks the Spotify app to search for ``song``."""
    return f"spotify:search:{quote(song.strip())}"


class SpotifyCatalog:
    """Resolve songs through the Spotify Web API using client credentials."""

    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        """Bind credentials from settings and an optional reusable HTTP client."""
        self._client_id = (settings.spotify_client_id or "").strip()
        secret = settings.spotify_client_secret
        self._client_secret = secret.get_secret_value().strip() if secret else ""
        self._client = client

    async def resolve_track_uri(self, song: str) -> str:
        """Return ``spotify:track:<id>`` for the best match, or the search fallback."""
        key = song.strip().lower()
        if not key:
            return "spotify:search:unknown"
        cached = _uri_cache.get(key)
        if cached is not None:
            return cached
        if not (self._client_id and self._client_secret):
            return search_uri(song)

        try:
            uri = await self._search(song.strip())
        except httpx.HTTPError as exc:
            logger.warning("Spotify lookup failed: %s", exc)
            return search_uri(song)
        if uri is None:
            return search_uri(song)

        if len(_uri_cache) >= _CACHE_LIMIT:
            _uri_cache.clear()
        _uri_cache[key] = uri
        return uri

    async def _search(self, query: str) -> str | None:
        client = self._client or httpx.AsyncClient(timeout=_TIMEOUT_SECONDS)
        try:
            token_response = await client.post(
                _TOKEN_URL,
                data={"grant_type": "client_credentials"},
                auth=(self._client_id, self._client_secret),
            )
            token_response.raise_for_status()
            token = token_response.json().get("access_token")
            if not token:
                return None

            search_response = await client.get(
                _SEARCH_URL,
                params={"q": query, "type": "track", "limit": 1},
                headers={"Authorization": f"Bearer {token}"},
            )
            search_response.raise_for_status()
            tracks = search_response.json().get("tracks", {}).get("items", [])
        finally:
            if self._client is None:
                await client.aclose()

        if tracks and isinstance(tracks[0].get("uri"), str):
            return str(tracks[0]["uri"])
        logger.info("Spotify returned no track for the requested song")
        return None
