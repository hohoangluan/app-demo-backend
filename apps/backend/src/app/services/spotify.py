"""Spotify Catalog Adapter service for resolving song titles to Spotify URIs."""

from __future__ import annotations

import logging
import urllib.parse
from functools import lru_cache
from typing import Final

import httpx

logger = logging.getLogger("app.services.spotify")

_SPOTIFY_TOKEN_URL: Final = "https://accounts.spotify.com/api/token"
_SPOTIFY_SEARCH_URL: Final = "https://api.spotify.com/v1/search"


class SpotifyCatalogAdapter:
    """Resolves arbitrary song strings to exact Spotify track URIs or search URIs."""

    def __init__(
        self,
        client_id: str | None = None,
        client_secret: str | None = None,
    ) -> None:
        import os
        from app.config import get_settings
        cfg_id, cfg_sec = "", ""
        try:
            settings = get_settings()
            cfg_id = settings.spotify_client_id or ""
            cfg_sec = settings.spotify_client_secret.get_secret_value() if settings.spotify_client_secret else ""
        except Exception:
            pass

        self.client_id = (client_id or cfg_id or os.getenv("SPOTIFY_CLIENT_ID", "")).strip()
        self.client_secret = (client_secret or cfg_sec or os.getenv("SPOTIFY_CLIENT_SECRET", "")).strip()
        self._access_token: str | None = None

    async def _get_access_token(self) -> str | None:
        if not self.client_id or not self.client_secret:
            return None
        if self._access_token:
            return self._access_token

        try:
            async with httpx.AsyncClient(timeout=3.0) as client:
                resp = await client.post(
                    _SPOTIFY_TOKEN_URL,
                    data={"grant_type": "client_credentials"},
                    auth=(self.client_id, self.client_secret),
                )
                if resp.status_code == 200:
                    token_data = resp.json()
                    self._access_token = token_data.get("access_token")
                    return self._access_token
                logger.warning(
                    "Spotify token request failed: HTTP %s %s",
                    resp.status_code, resp.text[:200],
                )
        except Exception as exc:
            logger.warning("Failed to authenticate with Spotify API: %s", exc)

        return None

    async def resolve_track_uri(self, song: str) -> str:
        """Resolves `song` string into `spotify:track:...` URI or `spotify:search:...` fallback."""
        cleaned = song.strip()
        if not cleaned:
            return "spotify:search:unknown"

        # Check cache
        cached = _get_cached_uri(cleaned)
        if cached:
            return cached

        token = await self._get_access_token()
        if token:
            try:
                async with httpx.AsyncClient(timeout=3.0) as client:
                    resp = await client.get(
                        _SPOTIFY_SEARCH_URL,
                        params={"q": cleaned, "type": "track", "limit": 1},
                        headers={"Authorization": f"Bearer {token}"},
                    )
                    if resp.status_code == 200:
                        data = resp.json()
                        tracks = data.get("tracks", {}).get("items", [])
                        if tracks and "uri" in tracks[0]:
                            resolved_uri = str(tracks[0]["uri"])
                            _cache_uri(cleaned, resolved_uri)
                            logger.info("Resolved '%s' to Spotify URI: %s", cleaned, resolved_uri)
                            return resolved_uri
                        logger.warning("Spotify search for '%s' returned no tracks", cleaned)
                    else:
                        # httpx does not raise on 4xx/5xx and nobody calls
                        # raise_for_status(), so without this line an API that
                        # answers 403 for every request degrades to the search
                        # fallback in total silence. That is how the credentials
                        # sat broken with nothing in the log to say so.
                        logger.warning(
                            "Spotify search for '%s' failed: HTTP %s %s",
                            cleaned, resp.status_code, resp.text[:200],
                        )
            except Exception as exc:
                logger.warning("Spotify Search API call failed: %s", exc)

        # Fallback to search URI format when credentials missing or API fails
        fallback_uri = f"spotify:search:{urllib.parse.quote(cleaned)}"
        _cache_uri(cleaned, fallback_uri)
        return fallback_uri


# There used to be a hardcoded `_STATIC_CATALOG` of seven "known" songs here.
# Every id in it was fabricated. Checked against the Spotify catalog:
#
#   "chạy ngay đi"  3g20o3f7XyT1Xz2a6cQx8x -> 404, real id 2iXFI7VBq8BWHtYjS2nzJe
#   "lạc trôi"      49XWd7jXN7v6y7N0Z60Y1a -> 404, real id 2eVPT00C8Z7jTRgU4XHpGt
#   "shape of you"  7qiZf249yMsMCwbStD3Vws -> 404, real id 7qiZfU4dY1lWllzX7mPBI3
#
# It was also consulted BEFORE the live search and matched on substrings, so
# "Nơi này có anh - Sơn Tùng M-TP" collapsed to the same dead id as the bare
# title and the artist the user was asked for was thrown away. That is why
# playback failed for exactly the songs someone had bothered to hardcode: the
# phone was handed a track uri that does not exist.
#
# Anything worth caching is now cached from a real answer, below.

_URI_CACHE: dict[str, str] = {}


def _get_cached_uri(song: str) -> str | None:
    return _URI_CACHE.get(song.lower().strip())


def _cache_uri(song: str, uri: str) -> None:
    if len(_URI_CACHE) > 500:
        _URI_CACHE.clear()
    _URI_CACHE[song.lower().strip()] = uri
