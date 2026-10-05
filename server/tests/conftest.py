"""Shared pytest fixtures."""

import os
from collections.abc import AsyncIterator

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession

from app.application import create_app
from app.config import Settings
from app.database import build_async_engine, build_session_factory
from tests.helpers import make_settings

# Tests never read a developer's local .env (it may enable FCM or callbacks).
Settings.model_config["env_file"] = None

# Disposable PostgreSQL started by `scripts/test-postgres.ps1 start` (compose.test.yaml).
TEST_DATABASE_URL = os.environ.get(
    "TEST_DATABASE_URL",
    "postgresql+asyncpg://app_demo_test:app_demo_test@127.0.0.1:55432/app_demo_test",
)


@pytest.fixture
def settings() -> Settings:
    """Return complete non-secret test configuration."""
    return make_settings()


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
    """Yield an engine bound to the disposable test PostgreSQL database."""
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
