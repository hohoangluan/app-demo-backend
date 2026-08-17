"""Shared pytest fixtures."""

import os
from collections.abc import AsyncIterator

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession

from app.application import create_app
from app.config import AppEnvironment, DeliveryTransport, Settings
from app.database import build_async_engine, build_session_factory

# Matches `scripts/test-postgres.ps1`'s default -- a disposable, tmpfs-backed
# PostgreSQL container started via `infra/compose.test.yaml`. Overridable so
# CI (or a differently-ported local run) can point at a different instance.
TEST_DATABASE_URL = os.environ.get(
    "TEST_DATABASE_URL",
    "postgresql+asyncpg://app_demo_test:app_demo_test@127.0.0.1:55432/app_demo_test",
)


@pytest.fixture
def settings() -> Settings:
    """Return complete non-secret test configuration."""
    return Settings.model_validate(
        {
            "app_env": AppEnvironment.TEST,
            "http_port": 8000,
            "database_url": "postgresql+asyncpg://test:test@localhost:5432/app_test",
            "public_api_token_hash": "1" * 64,
            "public_api_client_id": "test-only-public-client",
            "public_api_scopes": {"service:execute", "requests:read"},
            "device_api_token_hash": "2" * 64,
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


@pytest.fixture
async def db_engine() -> AsyncIterator[AsyncEngine]:
    """Yield a short-lived async engine bound to the disposable test PostgreSQL database.

    Requires `scripts/test-postgres.ps1 start` (or equivalent) to already be
    running -- per `CLAUDE.md`, repository/migration/concurrency behavior
    must be exercised against real PostgreSQL, never SQLite.
    """
    engine = build_async_engine(TEST_DATABASE_URL)
    yield engine
    await engine.dispose()


@pytest.fixture
async def clean_auth_tables(db_engine: AsyncEngine) -> AsyncIterator[None]:
    """Truncate auth/device tables so each test starts from an empty slate."""
    yield
    async with db_engine.begin() as connection:
        await connection.execute(
            text("TRUNCATE TABLE sessions, users, devices RESTART IDENTITY CASCADE")
        )


@pytest.fixture
async def db_session(
    db_engine: AsyncEngine,
    clean_auth_tables: None,  # noqa: ARG001
) -> AsyncIterator[AsyncSession]:
    """Yield a real PostgreSQL-backed session for repository-level tests."""
    factory = build_session_factory(db_engine)
    async with factory() as session:
        yield session


@pytest.fixture
async def db_client(
    settings: Settings,
    db_engine: AsyncEngine,
    clean_auth_tables: None,  # noqa: ARG001
) -> AsyncIterator[AsyncClient]:
    """Yield an HTTP client for a full application wired to real PostgreSQL."""
    application = create_app(settings)
    application.state.session_factory = build_session_factory(db_engine)
    transport = ASGITransport(app=application)
    async with AsyncClient(transport=transport, base_url="http://testserver") as test_client:
        yield test_client
