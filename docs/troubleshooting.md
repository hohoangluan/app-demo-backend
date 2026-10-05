# Troubleshooting

## Compose cannot interpolate configuration

Create `server/.env` from `server/.env.example`; Compose reads it from `server/`.
The environment file is local-only and must not be committed.

## PostgreSQL is unhealthy

Inspect the service without printing application secrets:

```powershell
docker compose -f server/compose.yaml ps postgres
docker compose -f server/compose.yaml logs postgres
```

Confirm that `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, and the
credentials embedded in `DATABASE_URL` agree. Existing volumes retain the
credentials from their first initialization.

To deliberately reset only this Compose project's local database, stop the
stack and remove its named volumes. This permanently deletes local prototype
data:

```powershell
docker compose -f server/compose.yaml down --volumes
```

## Backend is running but not ready

`/health/live` only confirms the process is alive. `/health/ready` returns `503`
until PostgreSQL is reachable and the workers have started. Check the service
states (the `migrate` service must have exited successfully) and the logs:

```powershell
docker compose -f server/compose.yaml ps
docker compose -f server/compose.yaml logs backend
```

Configuration validation is fail-fast. Replace all placeholder values in
`.env`; use `DELIVERY_TRANSPORT=fake` until Firebase credentials are available.

## Port 5432 or 8000 is already in use

Change `POSTGRES_PORT` or `HTTP_PORT` in `.env`. The containers continue to use
their internal ports (`5432` and `8000`).

## Backend dependencies appear stale

Dependencies are resolved from the committed lock file at image build time.
Rebuild the image after dependency changes:

```powershell
docker compose -f server/compose.yaml up -d --build backend
```

Do not work around a frozen-lock error by removing `--frozen`; regenerate and
review the lock file through the backend dependency workflow instead.

## The phone never receives commands

- `DELIVERY_TRANSPORT` must be `fcm`, with `FCM_PROJECT_ID` and
  `GOOGLE_APPLICATION_CREDENTIALS` pointing at the service-account file.
- The phone must have registered with a real FCM token (the app refuses to
  register without one) and must not be force-stopped; Android does not deliver
  FCM to a force-stopped app.
- `GET /api/v1/requests/{id}` stays `processing` and then `timed_out` with
  `REPORT_TIMEOUT` when the command or the report never arrived. Reports that
  failed to send are retried before the next command runs.
- Requests for an unpaired glasses `device_id` fail immediately with
  `404 GLASSES_DEVICE_NOT_LINKED`.
