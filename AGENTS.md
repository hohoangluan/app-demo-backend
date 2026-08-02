# Repository instructions

Before changing code:

1. Read `claude.md` completely; its development and test rules are mandatory.
2. Read only the current-state, active-phase, blockers, and latest session-log
   sections of `TASK_PLAN.md` first. Load older plan sections only when relevant.
3. Use `project_context.md` as the contract source and `architeture.md` as the
   implementation blueprint. Do not guess where they conflict.
4. Check `CONTRACT_DECISIONS.md` before changing public/internal API behavior.
   Entries marked `CHƯA CHỐT` are not approved decisions.

Keep dependencies in this direction:

```text
api -> service -> repository -> PostgreSQL
               -> adapter -> FCM / callback
worker -> service / repository / adapter
```

For every behavior change, add or update tests and run the applicable quality
gates from `claude.md`. PostgreSQL locking, JSONB, migration, lease, and race
tests must use PostgreSQL rather than SQLite.

Before ending a work session, update `TASK_PLAN.md` with checklist state,
exact commands/results, blockers, and a concise session-log entry. Never mark a
gate complete unless it actually ran successfully.
