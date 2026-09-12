import pytest
from app.services.spotify import SpotifyCatalogAdapter


@pytest.mark.anyio
async def test_spotify_catalog_adapter_fallback_uri():
    adapter = SpotifyCatalogAdapter()
    uri = await adapter.resolve_track_uri("Unknown Song Name 123456")
    assert uri.startswith("spotify:search:")
    assert "Unknown" in uri or "123456" in uri


@pytest.mark.anyio
async def test_spotify_catalog_adapter_caching():
    adapter = SpotifyCatalogAdapter()
    uri1 = await adapter.resolve_track_uri("Shape of You")
    uri2 = await adapter.resolve_track_uri("Shape of You")
    assert uri1 == uri2


@pytest.mark.anyio
async def test_spotify_catalog_adapter_static_catalog():
    adapter = SpotifyCatalogAdapter()
    uri1 = await adapter.resolve_track_uri("Nơi này có anh")
    uri2 = await adapter.resolve_track_uri("Nơi này có anh - Sơn Tùng M-TP")
    assert uri1 == "spotify:track:40riOyWknjF61xSPIxT9yB"
    assert uri2 == "spotify:track:40riOyWknjF61xSPIxT9yB"
