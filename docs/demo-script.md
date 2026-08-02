# Prototype demo script

This is the Phase P0 bootstrap flow. The end-to-end Android/FCM demonstration
will be added when the device pipeline is implemented.

## Prerequisites

- Docker with Docker Compose v2.
- `uv` for running backend quality checks outside containers.
- PowerShell for the commands below.

## Bootstrap

1. Create the local environment file:

   ```powershell
   Copy-Item .env.example .env
   ```

2. Replace every `replace-with-...` value in `.env`. Keep raw bearer tokens and
   Firebase credentials outside the repository. A field-encryption key can be
   generated with Python's standard library:

   ```powershell
   python -c "import base64,secrets; print(base64.urlsafe_b64encode(secrets.token_bytes(32)).decode())"
   ```

3. Validate the resolved Compose model:

   ```powershell
   docker compose -f infra/compose.yaml config
   ```

4. Start PostgreSQL and the backend:

   ```powershell
   docker compose -f infra/compose.yaml up -d
   docker compose -f infra/compose.yaml ps
   ```

5. Check the process health endpoint. During P0, readiness intentionally returns
   HTTP `503` until PostgreSQL and managed-worker bootstrap are implemented in
   P1/P2:

   ```powershell
   Invoke-RestMethod http://localhost:8000/health/live
   Invoke-WebRequest http://localhost:8000/health/ready -SkipHttpErrorCheck
   ```

6. Stop the local stack without deleting database data:

   ```powershell
   docker compose -f infra/compose.yaml down
   ```

The first backend start may download Python dependencies into the named `uv`
cache. Delivery remains in deterministic `fake` mode unless explicitly changed.
