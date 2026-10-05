# Repository instructions

Read `claude.md` completely before changing code; its development and test rules
are mandatory. Then read:

- `docs/project-context.md`: the Public/Internal API contract.
- `docs/architecture.md`: modules, dependency direction and request flow.
- `server/README.md` or `mobile/README.md` for the part you touch.

Keep dependencies in this direction:

```text
api -> service -> repository -> PostgreSQL
               -> adapter -> FCM / callback / Spotify
worker -> service / repository / adapter
```

Every behavior change needs tests and the quality gates from `claude.md`.
PostgreSQL locking, JSONB, migration, lease and race tests must run on
PostgreSQL, never SQLite.
