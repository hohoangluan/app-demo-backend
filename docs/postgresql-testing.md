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

At the time this test infrastructure was scaffolded, the repository did not yet
contain `alembic.ini`, an Alembic environment, or a migration dependency. Do not
substitute `Base.metadata.create_all` or invent a migration command. Once the
Alembic scaffold exists, the database workflow must run its checked-in upgrade
command against a clean database before `pytest`; the expected project command
is `uv run alembic upgrade head`, provided that it is then defined by the actual
backend configuration.

Tests and migration tooling must refuse a URL whose database name is not clearly
test-only. Never point `TEST_DATABASE_URL` at the development or production
database.

## Status and cleanup

```powershell
.\scripts\test-postgres.ps1 status
.\scripts\test-postgres.ps1 stop
```

The stop action runs `docker compose -f infra/compose.test.yaml down
--remove-orphans` for the fixed `app-demo-test` project. It does not enumerate
or delete unrelated containers, filesystem paths, or Docker volumes.
