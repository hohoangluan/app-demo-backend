"""Reusable fixtures for PostgreSQL integration tests.

Scope note: fixtures defined here only apply to tests collected under
``tests/integration/``. Tests outside this directory never depend on
PostgreSQL and keep running without Docker, exactly as before.

Opt-in workflow: ``TEST_DATABASE_URL`` controls the target database. When it
is unset, ``test_database_url`` calls ``pytest.skip`` so every test that
depends on it (directly or transitively) is skipped cleanly instead of
failing or erroring. When it is set, ``ensure_test_only_database_url`` is a
hard guard that raises ``NonTestDatabaseUrlError`` unless the database name
clearly looks test-only (contains ``"test"``), so this harness can never be
pointed at a development or production database by mistake.

Migration and cleanup strategy: the checked-in Alembic migration is applied
once per test session (``alembic upgrade head`` through the existing
``alembic/env.py`` mechanism, driven by the ``DATABASE_URL`` environment
variable it already reads) against a clean schema. Per-test isolation is
then provided by truncating the mutable ``operations``/``devices`` tables
after each test that uses ``db_session``, rather than wrapping every test in
one outer transaction that is rolled back at teardown. A single enclosing
transaction per test would make each test's writes invisible to any other
connection, which breaks the two-independent-connection concurrency tests
planned for later tasks (``PG-11``, ``PG-13``, ``FOR UPDATE SKIP LOCKED``
races, etc.) that must observe each other's committed rows within the same
test. Applying the migration once and truncating between tests keeps
sessions/connections fully independent while still giving every test a
clean slate.

Event-loop note: pytest-asyncio (mode=auto) gives each test function its own
event loop by default. A pooled asyncpg connection created on one test's
loop cannot be reused on a later test's loop. The shared engine therefore
uses ``NullPool`` so every checkout opens a fresh asyncpg connection bound
to whichever event loop is currently running, which makes it safe to share
one ``AsyncEngine`` object at session scope.
"""

from __future__ import annotations

import asyncio
import os
import subprocess
import sys
from pathlib import Path
from typing import TYPE_CHECKING
from urllib.parse import urlsplit

import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy.pool import NullPool

from app.database import build_session_factory

if TYPE_CHECKING:
    from collections.abc import AsyncIterator, Iterator

    from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession

    from app.database import AsyncSessionFactory

TEST_DATABASE_URL_ENV = "TEST_DATABASE_URL"
_BACKEND_ROOT = Path(__file__).resolve().parents[2]


class NonTestDatabaseUrlError(ValueError):
    """Raised when a database URL does not look safe for integration tests."""


def ensure_test_only_database_url(database_url: str) -> None:
    """Reject a URL whose database name does not clearly look test-only.

    The guard rule is deliberately simple and conservative: the database
    name (the URL path component) must contain the substring ``"test"``
    (case-insensitive). This accepts names such as ``app_demo_test`` or
    ``myapp_test_db`` and rejects development/production-shaped names such
    as ``app_demo`` or ``postgres``.
    """
    database_name = urlsplit(database_url).path.lstrip("/")
    if "test" not in database_name.lower():
        message = (
            f"Refusing to run PostgreSQL integration tests against database "
            f"{database_name!r}: the name does not contain 'test'. Point "
            f"{TEST_DATABASE_URL_ENV} at a disposable test-only database "
            "(see docs/postgresql-testing.md)."
        )
        raise NonTestDatabaseUrlError(message)


def _read_test_database_url() -> str:
    """Read ``TEST_DATABASE_URL``, skip when unset, and validate when set."""
    database_url = os.environ.get(TEST_DATABASE_URL_ENV)
    if not database_url:
        pytest.skip(
            f"{TEST_DATABASE_URL_ENV} is not set; PostgreSQL integration tests "
            "are opt-in. See docs/postgresql-testing.md to start the disposable "
            f"test database and set {TEST_DATABASE_URL_ENV} to run this test."
        )
    ensure_test_only_database_url(database_url)
    return database_url


@pytest.fixture(scope="session")
def test_database_url() -> str:
    """Return the validated, opt-in PostgreSQL integration-test database URL."""
    return _read_test_database_url()


def _alembic_executable() -> Path:
    """Return the `alembic` console-script entry point next to this interpreter.

    The checked-in migrations directory (`apps/backend/alembic/`) is itself a
    Python package literally named `alembic`. Once pytest's `pythonpath`
    setting puts the backend root ahead of site-packages on `sys.path`,
    `import alembic` inside the pytest process resolves to that local
    migrations package instead of the installed library. Running the
    already-verified `alembic upgrade head` console entry point as a
    subprocess (matching the documented workflow in
    docs/postgresql-testing.md) sidesteps that shadowing entirely, because a
    script entry point does not prepend the current working directory to its
    own `sys.path` the way pytest's in-process import does.
    """
    suffix = ".exe" if sys.platform == "win32" else ""
    return Path(sys.executable).with_name(f"alembic{suffix}")


def _run_migrations(database_url: str) -> None:
    """Apply the checked-in migration via the existing alembic/env.py mechanism."""
    env = {**os.environ, "DATABASE_URL": database_url}
    result = subprocess.run(  # noqa: S603
        [str(_alembic_executable()), "upgrade", "head"],
        cwd=_BACKEND_ROOT,
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        message = (
            "alembic upgrade head failed for the PostgreSQL integration-test "
            f"database:\n{result.stdout}\n{result.stderr}"
        )
        raise RuntimeError(message)


@pytest.fixture(scope="session")
def postgres_engine(test_database_url: str) -> Iterator[AsyncEngine]:
    """Provide one migrated, session-scoped engine shared by every test.

    See the module docstring for why migrations run once per session and
    why the engine is built with ``NullPool``.
    """
    _run_migrations(test_database_url)
    engine = create_async_engine(test_database_url, poolclass=NullPool)
    try:
        yield engine
    finally:
        asyncio.run(engine.dispose())


@pytest.fixture(scope="session")
def postgres_session_factory(postgres_engine: AsyncEngine) -> AsyncSessionFactory:
    """Build the session factory bound to the shared integration-test engine."""
    return build_session_factory(postgres_engine)


async def _truncate_mutable_tables(engine: AsyncEngine) -> None:
    """Clear rows written by a test without dropping or re-migrating the schema."""
    async with engine.begin() as connection:
        await connection.execute(text("TRUNCATE TABLE operations, devices, glasses_devices"))


@pytest.fixture
async def db_session(
    postgres_engine: AsyncEngine,
    postgres_session_factory: AsyncSessionFactory,
) -> AsyncIterator[AsyncSession]:
    """Yield a session for one test and truncate mutable tables afterward."""
    async with postgres_session_factory() as session:
        yield session
    await _truncate_mutable_tables(postgres_engine)
