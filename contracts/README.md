# API contract artifacts

This directory holds the two generated OpenAPI artifacts defined by the
architecture:

- `public-api.openapi.yaml` for the external client API only.
- `device-api.openapi.yaml` for the internal Android device API only.

The artifacts are generated from two isolated FastAPI contract applications in
`server/src/app/contract_api/`, separate from the runtime app.

From `server/`, export both artifacts with:

```powershell
uv run python scripts/export_openapi.py
```

Verify committed artifacts without writing them:

```powershell
uv run python scripts/export_openapi.py --check
```

CI runs the same drift check. Do not edit generated YAML by hand, expose Device
registration/report routes in the Public artifact, or treat either artifact as
authoritative when it disagrees with `docs/project-context.md`.
