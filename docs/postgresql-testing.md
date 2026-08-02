# Disposable PostgreSQL integration-test database

Repository, migration, JSONB, locking, lease, and race-condition tests must use
PostgreSQL rather than SQLite. The test stack is intentionally separate from
`infra/compose.yaml` and never mounts the development database volume.

`infra/compose.test.yaml` defines a dedicated Compose project named
`app-demo-test`. PostgreSQL data lives in a container `tmpfs`, so stopping and
removing the test container discards only test data. The database listens on
loopback port `55432` by default instead of the normal development port `5432`.

## Start and configure the test database

From the repository root:

```powershell
.\scripts\test-postgres.ps1 start
$env:TEST_DATABASE_URL = 'postgresql+asyncpg://app_demo_test:app_demo_test@127.0.0.1:55432/app_demo_test'
```

The script waits for the PostgreSQL healthcheck and sets the same
`TEST_DATABASE_URL` in the current PowerShell process. The explicit assignment
above documents the exact default value and is useful when commands run in a
new shell.

To use another host port, set it before starting and update the URL to match:

```powershell
$env:TEST_POSTGRES_PORT = '55433'
.\scripts\test-postgres.ps1 start
$env:TEST_DATABASE_URL = 'postgresql+asyncpg://app_demo_test:app_demo_test@127.0.0.1:55433/app_demo_test'
```

Equivalent raw Compose command:

```powershell
docker compose -f infra/compose.test.yaml up -d --wait postgres-test
```

Do not combine the test and development Compose files in one command.

## Migration and test commands

Run backend commands from the backend project and keep the test URL explicit:

```powershell
Set-Location apps/backend
$env:TEST_DATABASE_URL = 'postgresql+asyncpg://app_demo_test:app_demo_test@127.0.0.1:55432/app_demo_test'
uv sync --frozen
uv run pytest
```

Do not substitute `Base.metadata.create_all` for a migration. Tests under
`apps/backend/tests/integration/` apply the checked-in Alembic migration for
you: `tests/integration/conftest.py` runs `alembic upgrade head` (via the
`alembic` console entry point next to the active interpreter, using the
existing `alembic/env.py` mechanism driven by the `DATABASE_URL` environment
variable) once per test session, against a clean schema, before any test in
that directory runs.

Tests and migration tooling must refuse a URL whose database name is not clearly
test-only. Never point `TEST_DATABASE_URL` at the development or production
database. `tests/integration/conftest.py` enforces this with
`ensure_test_only_database_url`, which raises `NonTestDatabaseUrlError` unless
the database name contains `test` (case-insensitive).

When `TEST_DATABASE_URL` is unset, every test under `tests/integration/` skips
cleanly with an explanatory reason instead of failing — PostgreSQL integration
tests are opt-in, and `uv run pytest` with no Postgres running still passes.
When it is set, `tests/integration/conftest.py` provides:

- `test_database_url` — the validated URL (session-scoped).
- `postgres_engine` — one migrated `AsyncEngine` shared for the whole test
  session (session-scoped, built with `NullPool` so it stays safe to use
  across the separate event loop pytest-asyncio creates for each test).
- `postgres_session_factory` — the session factory bound to that engine.
- `db_session` — a per-test `AsyncSession`; after the test, the fixture
  truncates the `operations` and `devices` tables so the next test starts
  from a clean, empty state. The migration itself is applied only once per
  session (not per test), so future tests that need two independent
  concurrent connections (for example to exercise `FOR UPDATE SKIP LOCKED`
  claim races) can still open their own connections against
  `postgres_engine` and observe each other's committed writes.

Write new PostgreSQL integration tests under `apps/backend/tests/integration/`
so they automatically pick up these fixtures; tests elsewhere are unaffected
and keep running without Docker.

## Status and cleanup

```powershell
.\scripts\test-postgres.ps1 status
.\scripts\test-postgres.ps1 stop
```

The stop action runs `docker compose -f infra/compose.test.yaml down
--remove-orphans` for the fixed `app-demo-test` project. It does not enumerate
or delete unrelated containers, filesystem paths, or Docker volumes.
