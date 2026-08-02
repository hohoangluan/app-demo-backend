"""Shared pytest fixtures."""

from collections.abc import AsyncIterator

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from app.application import create_app
from app.config import AppEnvironment, DeliveryTransport, Settings


@pytest.fixture
def settings() -> Settings:
    """Return complete non-secret test configuration."""
    return Settings.model_validate(
        {
            "app_env": AppEnvironment.TEST,
            "http_port": 8000,
            "database_url": "postgresql+asyncpg://test:test@localhost:5432/app_test",
            "public_api_token_hash": "test-only-public-hash",
            "device_api_token_hash": "test-only-device-hash",
            "field_encryption_key": "test-only-field-key",
            "delivery_transport": DeliveryTransport.FAKE,
        }
    )


@pytest.fixture
def test_app(settings: Settings) -> FastAPI:
    """Create an isolated application for each test."""
    return create_app(settings)


@pytest.fixture
async def client(test_app: FastAPI) -> AsyncIterator[AsyncClient]:
    """Yield an async HTTP client bound to the test application."""
    transport = ASGITransport(app=test_app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as test_client:
        yield test_client
