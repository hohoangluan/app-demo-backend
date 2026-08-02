# Troubleshooting

## Compose cannot interpolate configuration

Run Compose from the repository root and create `.env` from `.env.example`.
The environment file is local-only and must not be committed.

## PostgreSQL is unhealthy

Inspect the service without printing application secrets:

```powershell
docker compose -f infra/compose.yaml ps postgres
docker compose -f infra/compose.yaml logs postgres
```

Confirm that `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, and the
credentials embedded in `DATABASE_URL` agree. Existing volumes retain the
credentials from their first initialization.

To deliberately reset only this Compose project's local database, stop the
stack and remove its named volumes. This permanently deletes local prototype
data:

```powershell
docker compose -f infra/compose.yaml down --volumes
```

## Backend is running but not ready

`/health/live` only confirms the process is alive. `/health/ready` also depends
on PostgreSQL and worker bootstrap; it intentionally returns `503` during the
P0 scaffold. Check both service states and then inspect backend logs:

```powershell
docker compose -f infra/compose.yaml ps
docker compose -f infra/compose.yaml logs backend
```

Configuration validation is fail-fast. Replace all placeholder values in
`.env`; use `DELIVERY_TRANSPORT=fake` until Firebase credentials are available.

## Port 5432 or 8000 is already in use

Change `POSTGRES_PORT` or `HTTP_PORT` in `.env`. The containers continue to use
their internal ports (`5432` and `8000`).

## Backend dependencies appear stale

The backend source is bind-mounted and dependencies are resolved from the
committed lock file. Recreate the backend container after dependency changes:

```powershell
docker compose -f infra/compose.yaml up -d --force-recreate backend
```

Do not work around a frozen-lock error by removing `--frozen`; regenerate and
review the lock file through the backend dependency workflow instead.
