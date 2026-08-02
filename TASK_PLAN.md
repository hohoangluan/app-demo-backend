# Kế hoạch triển khai App Communication Server

> Tracker bền vững qua nhiều session. Cập nhật file này sau mỗi thay đổi có ý nghĩa.
> Nguồn contract: `project_context.md`. Blueprint: `architeture.md`. Quy tắc bắt buộc: `claude.md`.

## Trạng thái hiện tại

- Ngày cập nhật: 2026-08-02
- Phase hiện tại: **P1 hoàn tất hoàn toàn (Database & API foundation fully verified); chuẩn bị chuyển sang P2 — Worker pipeline**.
- Mục tiêu phiên hiện tại (session 3): hoàn thành các mục P1 còn lại gồm structured logging, redaction & request correlation middleware, Public Status API, và vertical slice đầu tiên `music_volume`. Chạy toàn bộ quality gates, bao gồm 244 unit, contract, API, và PostgreSQL integration tests trên container thật.
- Môi trường đã xác nhận:
  - Python `3.13.12`, Docker `29.5.3`/Compose `v5.1.4`, `uv` hoạt động.
  - PostgreSQL test container `infra/compose.test.yaml` (port 57432).
  - Android test & build (Gradle wrapper) verified trước đó.

## Quy ước trạng thái

- `[ ]` chưa bắt đầu
- `[-]` đang thực hiện
- `[x]` hoàn tất và đã có bằng chứng kiểm tra
- `[!]` bị chặn; phải ghi rõ nguyên nhân trong mục "Blockers và quyết định"

## P0 — Contracts and scaffold

- [x] Đọc `claude.md`, `project_context.md` và `architeture.md`.
- [x] Scaffold Python project tại `apps/backend`.
- [x] Tạo FastAPI application factory tối thiểu và health endpoints.
- [x] Cấu hình Ruff, mypy strict và pytest.
- [x] Tạo Dockerfile và Docker Compose cho backend/PostgreSQL.
- [x] Tạo cấu trúc `contracts`, `docs` và CI nền tảng.
- [x] Cài/khả dụng hóa `uv` và sinh `uv.lock`.
- [x] Tạo initial Git commit chứa scaffold và `uv.lock` khi được yêu cầu (user đã yêu cầu ở session 2).
- [x] Khởi tạo Android project Kotlin/Compose một module tại `apps/android` (MainActivity, OverviewScreen/OverviewUiState, AppDemoTheme, unit test). User đã accept SDK licenses và cài `platforms;android-37`/`build-tools;36.0.0`; `gradlew.bat test lint assembleDebug` chạy PowerShell (không phải Git Bash — Git Bash gây lỗi path) verified: 2 unit test pass, lint 0 errors/8 warning không chặn (OldTargetApi, version bump, ModifierParameter, DataExtractionRules, MissingApplicationIcon — cosmetic, chấp nhận được ở prototype), `assembleDebug` BUILD SUCCESSFUL.
- [x] Định nghĩa đủ Pydantic schema cho 9 Public actions và 2 Device endpoints (`app/schemas/service_requests.py`, `service_results.py`, `device.py`, `common.py`).
- [x] Export riêng Public/Internal OpenAPI từ schema thực thi (`scripts/export_openapi.py`, `contracts/public-api.openapi.yaml`, `contracts/device-api.openapi.yaml`).
- [x] Thêm contract tests cho response envelope, examples và action mapping (`tests/test_openapi_contracts.py`, `test_service_requests.py`, `test_service_results.py`, `test_device_schemas.py`, `test_actions.py`).
- [x] Chạy toàn bộ quality gates P0 và lưu bằng chứng bên dưới (session 2, sau khi sửa gap dependency/lint/type).

### Tiêu chí hoàn tất P0

- Backend cài được từ lockfile trên máy sạch.
- FastAPI khởi động được và health endpoints có test.
- Ruff format/check, mypy, pytest và build đều pass.
- `docker compose config` pass; PostgreSQL và backend có healthcheck.
- Public/Internal OpenAPI được sinh từ code và không phải placeholder thủ công.
- Android scaffold build/test/lint được bằng Gradle wrapper.

## P1 — Database and API foundation

- [x] SQLAlchemy async engine/session và cấu hình fail-fast (`app/database.py`, `app/config.py`; task `P1-DB-01`).
- [x] Models `devices`, `operations` đúng blueprint (`app/models/device.py`, `operation.py`, `enums.py`; task `P1-DB-02`/`P1-DB-03`).
- [x] Alembic baseline migration; test cả database sạch và upgrade path — verified session 2 bằng `alembic upgrade head` → `downgrade base` → `upgrade head` trên PostgreSQL thật (test-compose), cột/check/index khớp chính xác `docs/p1-database-plan.md` (task `P1-DB-04`, tương ứng `PG-01`/`PG-02`).
- [x] PostgreSQL test harness tái sử dụng được cho pytest (task `P1-DB-05`, session 2 qua subagent, verified độc lập). `apps/backend/tests/integration/conftest.py`: fixture `test_database_url` (đọc `TEST_DATABASE_URL`, `pytest.skip` nếu unset), guard `ensure_test_only_database_url` (reject DB name không chứa `"test"`), `postgres_engine`/`postgres_session_factory` (session-scoped, chạy `alembic upgrade head` một lần qua subprocess — không dùng `Base.metadata.create_all`), `db_session` (function-scoped, `TRUNCATE operations, devices` sau mỗi test để giữ connection độc lập cho race test tương lai). `apps/backend/tests/integration/test_postgres_harness.py`: 4 test (guard x2, schema/constraint check qua `information_schema`/`pg_constraint`, insert round-trip tôn trọng mọi CHECK constraint). Verify độc lập: full suite 168 passed/2 skipped không có Postgres; 170 passed (bao gồm cả 4 integration test) khi có `TEST_DATABASE_URL` trỏ tới `infra/compose.test.yaml` (port 57432). `docs/postgresql-testing.md` đã cập nhật đoạn cũ nói "chưa có Alembic".
- [x] Device repository (`P1-DB-06`, session 2 qua subagent, verified độc lập). `app/repositories/device.py::DeviceRepository`: `register()` cài đúng D-05 (idempotent rotate cùng owner; cấm đổi owner → `DeviceOwnerMismatch`; revoked reject → `DeviceIsRevoked`; device mới → deactivate device active khác của user trong cùng transaction), `get_latest_active_device()` dùng shape `ix_devices_resolver`. Outcome là dataclass typed, không commit, không HTTP code. 10 test PG-03/04/05 mới, tất cả pass trên PostgreSQL thật. **Edge case đã biết, chưa xử lý** (agent tự flag, không tự đoán thêm): re-register một device đã bị deactivate bởi device khác sẽ set nó `ACTIVE` trở lại mà không tự động deactivate sibling hiện đang active — có thể tạm thời có 2 active device cho 1 user cho tới lần register kế tiếp. Cần quyết định rõ khi làm service layer.
- [x] Operation insert/read (`P1-DB-07`, session 2 qua subagent, verified độc lập). `app/repositories/operation.py::OperationRepository`: `insert_or_get()` dùng `INSERT ... ON CONFLICT (request_id) DO NOTHING RETURNING` (một `func.now()` duy nhất cho `created_at`/`updated_at`/`next_delivery_at`), trả `OperationInsertOutcome` (`INSERTED`/`IDEMPOTENT_MATCH`/`FINGERPRINT_CONFLICT`) — không tự chọn HTTP/409; `get_by_request_id(client_id, request_id)` filter ownership ngay trong `WHERE` (khớp D-06). 19 test PG-06 đến PG-12 và PG-24, gồm 2 test concurrency race (`asyncio.Barrier` + `wait_for` timeout hữu hạn, không sleep tùy ý) verify PG-11/PG-12 — chạy lại 5 lần liên tiếp trong main session, không flaky.
- [x] Delivery claim/lease (`P1-DB-08`, session 2 qua subagent, verified độc lập). `OperationRepository.claim_due_deliveries()` (due predicate + `ORDER BY next_delivery_at NULLS LAST, created_at` + `FOR UPDATE SKIP LOCKED`, resolve device qua `DeviceRepository` cùng transaction), `record_delivery_sent/transient_failure/permanent_failure()` (fenced conditional update theo `delivery_locked_until` chính xác, trả `False` nếu lease đã stale). Không tự chọn backoff duration hay error content — caller cung cấp. **Judgment call đã flag**: claim bỏ qua (không claim) operation nếu user không có active device, thay vì tạo failure transition — doc không quy định rõ case này. 13 test PG-13 đến PG-17 pass trên PostgreSQL thật, PG-13 (race) chạy lại 3 lần không flaky.
- [x] Callback claim/lease (`P1-DB-09`, session 2 qua subagent, verified). `OperationRepository.claim_due_callbacks()` (mirror delivery claim, chỉ claim terminal operation, không resolve device), `record_callback_delivered/retry/dead_letter()` (fenced theo `callback_locked_until`, không đụng `request_state`/`result`/`error`).
- [x] Report/timeout transitions (`P1-DB-10`, session 2 qua subagent, verified). `record_device_report()` (`SELECT FOR UPDATE`, 3-way outcome `APPLIED`/`IDEMPOTENT_DUPLICATE`/`CONFLICT` khớp D-09; late-report-after-timeout rơi vào `CONFLICT` vì hash không khớp — không có state riêng), `record_timeout()` (conditional update `request_state='processing' AND expires_at<=now`, race với report tự nhiên serialize qua row lock). Error content, `callback_state` sau report đều do caller cung cấp, không hardcode `REPORT_TIMEOUT` string trong repo. Test: chỉ happy-path (5 test) theo yêu cầu tăng tốc — bỏ test PG-18 đến PG-23 đầy đủ (fencing-reject, race report-vs-timeout đồng thời, constraint). **Coverage thấp hơn P1-DB-05–08, cần bổ sung nếu sau này cần chứng minh race report-vs-timeout thật.**
- [x] Áp dụng chính sách validation `D-07` (đã CHỐT) vào toàn bộ Public/Internal Pydantic schema (session 2 qua subagent, verified độc lập): `extra="forbid"` trên mọi model ở `app/schemas/*.py`; `TrimmedNonEmptyStr` (reusable, `common.py`) cho field free-text do client gửi (`user_id`, `device_id`, `push_token`, `quote_id`, `song`, `navigation_id`, `name`); `Latitude`/`Longitude` reusable dùng lại ở `Destination` và `NavigationDestination` (trước đó là `float` không giới hạn — lỗ hổng đã đóng); `AwareUtcDatetime` reusable (reject naive datetime, normalize non-UTC offset về UTC) cho mọi field datetime. OpenAPI đã regenerate qua `scripts/export_openapi.py`, `--check` pass. Không thêm giới hạn độ dài/số (đúng theo D-07: "phải ghi rõ trong contract trước khi enforce"); không đổi logic `result`/`error` optional trên `DeviceReportRequest` (nằm ngoài phạm vi, đã có `model_validator` riêng).
- [x] Structured logging, redaction và request correlation (`app/logging.py`, `StructuredJsonFormatter`, `RequestCorrelationMiddleware`, `tests/test_logging.py`).
- [x] Bearer authentication cho Public/Device API (session 2 qua subagent, verified). `app/auth.py`: `require_public_client_principal` (HMAC-SHA256 domain-separated digest, `hmac.compare_digest`, mọi lỗi trả cùng 401 để không lộ thông tin), `require_public_scope(scope)` (403 nếu thiếu scope), `require_device_bearer_token` (chỉ gate, không có principal/scope — doc không định nghĩa Device-side client identity, giữ nguyên theo yêu cầu bảo thủ). `Settings` thêm `public_api_client_id`, `public_api_scopes` (validate non-empty/known scope), enforce hash format 64-hex cho cả Public/Device.
- [x] Operation service với canonical fingerprint/idempotency (session 2 qua subagent, verified). `app/services/operation.py::OperationService.accept()`: `compute_request_fingerprint()` (SHA-256 của JSON canonical `{body, client_id, method, path}`, sort_keys, sau validation), map `OperationRepository` 3-way outcome → `AcceptOperationStatus.ACCEPTED`/`CONFLICT` (ACCEPTED dùng `operation.created_at` gốc kể cả duplicate, khớp D-10). `_initial_callback_state()`: `callback_url` unset → `NOT_REQUIRED`, set → `PENDING`.
- [x] Status API (`app/api/status.py`, `GET /api/v1/requests/{request_id}`, scope `requests:read`, D-06 404 handling, `tests/api/test_status_api.py`).
- [x] Vertical slice đầu tiên `music_volume` (`app/api/service.py`, `POST /api/v1/service/music/volume`, scope `service:execute`, D-10 original `accepted_at` idempotency, `tests/api/test_music_volume_api.py`, `tests/integration/test_music_volume_postgres.py`).
- [x] PostgreSQL integration/API/contract tests cho P1 (toàn bộ 244 tests pass trên PostgreSQL thật).


## P2 — Worker pipeline

- [ ] Delivery claim/lease/recovery với `FOR UPDATE SKIP LOCKED`.
- [ ] Fake delivery adapter và FCM adapter boundary.
- [ ] Retry classification/backoff và invalid-token handling.
- [ ] Timeout worker và report-timeout race handling.
- [ ] Callback worker, SSRF allow-list và dead-letter state.
- [ ] Concurrency, restart và idempotency tests trên PostgreSQL thật.

## P3 — Android pipeline

- [ ] Compose shell và device registration.
- [ ] FCM receiver chỉ validate/persist/enqueue.
- [ ] Room `commands` và `pending_reports`.
- [ ] WorkManager dispatcher/report retry.
- [ ] Compile-time mapping đủ 9 actions, unknown action bị từ chối.
- [ ] Một fake handler chạy E2E qua production dispatcher path.
- [ ] Test duplicate FCM, process death và pending report recovery.

## P4 — Complete feature contracts

- [ ] Thêm 8 actions còn lại.
- [ ] Deterministic fake adapters cho ride/music/navigation/emergency/contact.
- [ ] Feature screens, Command Detail và Logs.
- [ ] Hoàn tất action result/error validation.
- [ ] Hoàn tất contract/E2E matrix bắt buộc.

## P5 — Real adapters and demo hardening

- [ ] Real FCM trên thiết bị vật lý.
- [ ] Android location/volume/contact/call intent adapters.
- [ ] Navigation/MusicKit/Uber sandbox chỉ khi có credential/phê duyệt.
- [ ] Seed/reset scripts, demo script và troubleshooting guide.
- [ ] Rehearsal Doze/process restart/network failure.
- [ ] Fresh-machine setup được kiểm chứng.

## Quality-gate evidence

Ghi chính xác lệnh, ngày và kết quả. Không đánh dấu hoàn tất nếu lệnh chưa chạy.

| Ngày | Phạm vi | Lệnh | Kết quả |
|---|---|---|---|
| 2026-08-02 | Toolchain | `python --version` | Pass — Python 3.13.12 |
| 2026-08-02 | Toolchain | `docker --version` | Pass — Docker 29.5.3 |
| 2026-08-02 | Toolchain | `docker compose version` | Pass — Compose v5.1.4 |
| 2026-08-02 | Toolchain | `python -m uv --version` | Pass — uv 0.12.1 |
| 2026-08-02 | Backend deps | `python -m uv sync --frozen` | Pass — 35 packages checked |
| 2026-08-02 | Backend format | `python -m uv run ruff format --check .` | Pass — 9 files formatted |
| 2026-08-02 | Backend lint | `python -m uv run ruff check .` | Pass |
| 2026-08-02 | Backend types | `python -m uv run mypy src tests` | Pass — 9 files, no issues |
| 2026-08-02 | Backend tests | `python -m uv run pytest` | Pass — 8 tests |
| 2026-08-02 | Backend package | `python -m uv build` | Pass — sdist và wheel |
| 2026-08-02 | Compose | `docker compose -f infra/compose.yaml config --quiet` | Pass |
| 2026-08-02 | Container | `docker compose -f infra/compose.yaml build backend` | Pass |
| 2026-08-02 | Container smoke | Temporary `docker run` + health probes | Pass — live 200, ready 503, user `app` |
| 2026-08-02 | Backend deps (session 2) | `python -m uv lock` rồi `python -m uv sync --frozen` | Pass — thêm `sqlalchemy[asyncio]`, `alembic`, `asyncpg` (42 packages) |
| 2026-08-02 | Backend format (session 2) | `python -m uv run ruff format --check .` | Pass — 37 files formatted (sau khi sửa 1 file) |
| 2026-08-02 | Backend lint (session 2) | `python -m uv run ruff check .` | Pass — sau khi sửa N811/UP047/I001/INP001 ở `models/`, `alembic/` |
| 2026-08-02 | Backend types (session 2) | `python -m uv run mypy src tests scripts` | Pass — 33 source files, no issues |
| 2026-08-02 | Backend tests (session 2) | `python -m uv run pytest` | Pass — 98 tests |
| 2026-08-02 | OpenAPI drift (session 2) | `python -m uv run python scripts/export_openapi.py --check` | Pass — artifacts up to date |
| 2026-08-02 | Backend package (session 2) | `python -m uv build` | Pass — sdist và wheel |
| 2026-08-02 | Compose (session 2) | `docker compose -f infra/compose.yaml config --quiet` | Pass |
| 2026-08-02 | Container (session 2) | `docker compose -f infra/compose.yaml build backend` | Pass — build lại sau khi đổi dependency |
| 2026-08-02 | Migration trên PostgreSQL thật (session 2) | `docker compose -f infra/compose.test.yaml up -d --wait postgres-test` (port 57432, port 55432 mặc định bị Windows chặn) rồi `alembic upgrade head` → `alembic downgrade base` → `alembic upgrade head` | Pass — hai bảng, đúng cột/check/index như `docs/p1-database-plan.md`; đã xác nhận bằng `psql \d devices` và `\d operations` |
| 2026-08-02 | Android (session 2, sau khi user accept SDK licenses) | PowerShell: `$env:JAVA_HOME/$env:ANDROID_HOME` set thủ công, `.\gradlew.bat test lint assembleDebug --no-daemon` | Pass — `OverviewUiStateTest` 2/2 test pass; lint 0 errors, 8 warning cosmetic; `assembleDebug` BUILD SUCCESSFUL (51 tasks) |
| 2026-08-02 | D-07 + P1-DB-05 (session 2, qua 2 subagent song song, verify độc lập lại bởi main session) | `uv run ruff format --check .` / `ruff check .` / `mypy src tests scripts` | Pass — 40/41 file formatted, all lint checks pass, 37 source files no mypy issue |
| 2026-08-02 | D-07 + P1-DB-05 (session 2) | `uv run pytest -q` (không có `TEST_DATABASE_URL`) | Pass — 168 passed, 2 skipped (tăng từ baseline 98, không có test nào bị làm yếu/xóa) |
| 2026-08-02 | P1-DB-05 (session 2) | `docker compose -f infra/compose.test.yaml up -d --wait postgres-test` (port 57432) + `TEST_DATABASE_URL=...` `uv run pytest -q` | Pass — 170 passed (bao gồm 4 test PostgreSQL integration mới chạy thật, không skip); teardown `docker compose down --remove-orphans` sau khi verify |
| 2026-08-02 | D-07 (session 2) | `uv run python scripts/export_openapi.py` rồi `--check` | Pass — regenerate xong, "OpenAPI artifacts are up to date." |
| 2026-08-02 | D-07 + P1-DB-05 (session 2) | `uv build` | Pass — sdist và wheel |
| 2026-08-02 | P1-DB-06 + P1-DB-07 (session 2, qua 2 subagent song song, verify độc lập lại) | `ruff format --check .` / `ruff check .` / `mypy src tests scripts` | Pass — 46 file formatted, all lint pass, 42 source file no mypy issue |
| 2026-08-02 | P1-DB-06 + P1-DB-07 (session 2) | `docker compose -f infra/compose.test.yaml up` (port 57432) + `TEST_DATABASE_URL=...` `uv run pytest -q` | Pass — 199 passed (98 gốc + 4 harness + 10 device repo + 19 operation repo + test khác đã thêm trước đó) |
| 2026-08-02 | P1-DB-07 concurrency (session 2) | Chạy lại riêng test PG-11/PG-12 (`asyncio.Barrier`) 5 lần liên tiếp | Pass cả 5 lần — không flaky |
| 2026-08-02 | P1-DB-06 + P1-DB-07 (session 2) | `uv run pytest -q` (không `TEST_DATABASE_URL`) | Pass — 168 passed, 31 skipped, teardown container sau đó |
| 2026-08-02 | P1-DB-06 + P1-DB-07 (session 2) | `uv build`, `export_openapi.py --check` | Pass cả hai |
| 2026-08-02 | P1-DB-08 (session 2, qua subagent, verify độc lập) | `ruff format/check`, `mypy`, PostgreSQL thật `pytest -q` | Pass — 212 passed; PG-13 race re-run 3 lần không flaky |
| 2026-08-02 | P1-DB-08 (session 2) | `pytest -q` (không DB), `uv build`, `export_openapi.py --check` | Pass — 168 passed/44 skipped, build + openapi đều pass |
| 2026-08-02 | P1-DB-09 + P1-DB-10 (session 2, qua subagent, happy-path only) | `ruff format/check`, `mypy`, PostgreSQL thật `pytest -q` | Pass — 217 passed |
| 2026-08-02 | P1-DB-09 + P1-DB-10 (session 2) | `pytest -q` (không DB), `uv build`, `export_openapi.py --check` | Pass — 168 passed/49 skipped, build + openapi đều pass |
| 2026-08-02 | Bearer auth + Operation service (session 2, qua subagent, verify độc lập) | `ruff format/check`, `mypy`, PostgreSQL thật `pytest -q` | Pass — 231 passed |
| 2026-08-02 | Bearer auth + Operation service (session 2) | `pytest -q` (không DB), `uv build`, `export_openapi.py --check`, `docker compose -f infra/compose.yaml config --quiet` | Pass — 179 passed/52 skipped, build/openapi/compose config đều pass |
| 2026-08-02 | Structured logging, Status API, Vertical slice music_volume (session 3) | `ruff format/check`, `mypy`, `pytest` (PostgreSQL thật port 57432), `export_openapi.py --check`, `uv build`, `docker compose config` | Pass — 244 passed on PostgreSQL, 0 lint/type issues, openapi/build/compose config clean |


## Blockers và quyết định

- OpenAPI artifact chỉ được tạo từ Pydantic/FastAPI schema đã cài đặt; không viết spec placeholder rồi coi là contract hoàn tất.
- `CONTRACT_DECISIONS.md` D-01 đến D-13 đã được user CHỐT theo đúng recommended default ở session 2 (xem Decisions log trong file đó). Việc chốt là quyết định business/contract; **code chưa cài đặt hết hệ quả** — đặc biệt D-07 (validation policy) chưa áp dụng vào schema, và D-01/D-02/D-03/D-04/D-05/D-09/D-10/D-12 phụ thuộc service/repository/worker layer chưa tồn tại. Xem các mục `[ ]` mới thêm trong P1.
- Android SDK licenses chưa được user accept; Gradle `test lint assembleDebug` chưa chạy được trong môi trường này. Cần xác nhận ở session sau bằng cách hỏi user hoặc tự kiểm tra `D:\tmp\app-demo-android-toolchain\android-sdk\licenses`.
- Port `55432` (cổng mặc định cho PostgreSQL test theo `docs/postgresql-testing.md`) bị Windows chặn ("access forbidden by its access permissions") trên máy này; session 2 dùng `TEST_POSTGRES_PORT=57432` để vòng qua. Chưa rõ nguyên nhân gốc (có thể do Hyper-V dynamic port exclusion range) — nếu lặp lại, cân nhắc cập nhật `docs/postgresql-testing.md` để ghi chú port thay thế.
- Repository chưa có commit nào trước session 2; đã tạo initial commit theo yêu cầu rõ ràng của user.

## Nhật ký session

### 2026-08-02 — Session 1

- Bắt đầu P0.
- Chia công việc song song cho contract audit, backend scaffold và infra/CI scaffold.
- Xác nhận toolchain và tạo tracker này.
- Khởi tạo Git repository theo yêu cầu của người dùng; branch ban đầu là `main`.
- Hoàn tất backend scaffold, config fail-fast, async health tests và lockfile.
- Hoàn tất Compose PostgreSQL/backend, Docker image chạy non-root, CI, docs và `.env.example`.
- Backend quality gates pass; Docker image build và health smoke-test pass.
- Audit contract được lưu tại `CONTRACT_DECISIONS.md`; chưa tự quyết các điểm mâu thuẫn.
- (Không ghi log rõ ràng, nhưng phát hiện ở session 2: cùng khoảng thời gian này, code đã tiến xa hơn checklist ghi nhận — 9 Public action schema, 2 Device schema, OpenAPI export/contract test, và nền tảng P1 database (SQLAlchemy models, Alembic migration) đã được viết nhưng chưa cập nhật vào tracker này và chưa từng chạy quality gate thành công do thiếu dependency.)

### 2026-08-02 — Session 2

- Đọc lại `TASK_PLAN.md`, `CONTRACT_DECISIONS.md`, và audit thực tế `git status`/filesystem thay vì tin checklist cũ — phát hiện code đã vượt xa checklist (schema/OpenAPI/contract test P0 đã viết xong; P1 database foundation đã có draft) nhưng bị hỏng quality gate vì `pyproject.toml` thiếu `sqlalchemy`/`alembic`/`asyncpg`.
- Hỏi user 3 quyết định: (1) tạo initial commit ngay, (2) duyệt toàn bộ 13 recommended default trong `CONTRACT_DECISIONS.md`, (3) không tự động accept Android SDK licenses. User chọn cả ba theo hướng "Recommended".
- Sửa `pyproject.toml` (thêm 3 dependency còn thiếu), `uv lock`, sửa lint/format (N811 alias `PostgreSQLUUID`→`PG_UUID`, UP047 PEP 695 generics ở `models/enums.py`, import order, trailing whitespace, thêm `alembic/__init__.py` và `alembic/versions/__init__.py` cho INP001).
- Toàn bộ backend quality gates pass: ruff format/check, mypy strict (33 files), pytest (98 tests), OpenAPI `--check`, `uv build`, `docker compose build backend`.
- Khởi động PostgreSQL test tạm (`infra/compose.test.yaml`, port 57432 do port mặc định 55432 bị hệ điều hành chặn) và chạy `alembic upgrade head` → `downgrade base` → `upgrade head`; xác nhận bảng/cột/check/index khớp chính xác `docs/p1-database-plan.md`.
- Khóa 13 điểm `CONTRACT_DECISIONS.md` (D-01 đến D-13) theo recommended default, ghi đầy đủ Decisions log kèm trạng thái code (đã khớp / chưa cài đặt) cho từng điểm.
- Cập nhật `TASK_PLAN.md`: đánh dấu các mục P0 đã hoàn tất thực sự, thêm task P1 database (`P1-DB-01`–`P1-DB-04` done, `P1-DB-05`+ chưa làm), thêm việc còn thiếu (D-07 validation policy chưa cài đặt), cập nhật bảng evidence và blockers.
- Đưa hướng dẫn accept Android SDK license cho user tự chạy (không tự động pipe "y").
- Tạo initial Git commit theo yêu cầu của user (xem commit message).
- User accept Android SDK licenses xong, báo lại. Verify Android build qua PowerShell (Git Bash gây lỗi path khi gọi `gradlew.bat`, chuyển sang PowerShell native): `test lint assembleDebug` pass.
- User yêu cầu chạy subagent hoàn thành task. Dispatch 2 subagent song song (background), phạm vi độc lập nhau, không cho commit hoặc sửa `TASK_PLAN.md`/`CONTRACT_DECISIONS.md`:
  1. `P1-DB-05` PostgreSQL test harness (pytest fixture reusable, migration-once-per-session + truncate-per-test, guard chặn URL không phải test-db).
  2. Áp dụng `D-07` validation policy vào toàn bộ Public/Internal Pydantic schema.
- Cả hai subagent hoàn thành, tự báo cáo đầy đủ file thay đổi/judgment call/lệnh verify. Main session verify độc lập lại toàn bộ (không tin báo cáo suông): đọc diff thật, tự chạy `ruff format/check`, `mypy`, `pytest` không DB (168 passed/2 skipped), tự spin PostgreSQL thật và chạy lại (170 passed, 4 integration test chạy thật không skip), tự chạy `export_openapi.py --check` và `uv build`. Tất cả pass, không phát hiện sai lệch so với báo cáo subagent.
- Cập nhật `TASK_PLAN.md`: đánh dấu `P1-DB-05` và D-07 validation policy hoàn tất với bằng chứng; ghi rõ `P1-DB-06` đến `P1-DB-10` (repository layer) vẫn chưa làm, giờ đã có harness sẵn sàng để bắt đầu.
- Commit `P1-DB-05` + D-07 (`c3b2530`). User báo tiếp tục, nhắc để context tự compact giữa các task — dùng `TASK_PLAN.md` làm bằng chứng bền vững qua compaction.
- Dispatch tiếp 2 subagent song song: `P1-DB-06` (Device repository, D-05) và `P1-DB-07` (Operation insert/read + idempotency, PG-06–12/24, gồm 2 test concurrency race dùng `asyncio.Barrier`). Cả hai hoàn thành, tự báo cáo đầy đủ. Main session verify độc lập lại toàn bộ: đọc code thật, tự chạy `ruff`/`mypy`, tự spin PostgreSQL thật chạy 199 test pass, tự chạy lại riêng 2 test concurrency 5 lần để loại flaky, tự chạy offline suite/build/openapi-check. Không phát hiện sai lệch so với báo cáo subagent. Device repo agent tự flag một edge case chưa xử lý (re-register device đã bị deactivate bởi sibling khác) — ghi lại trong checklist, chưa cần quyết định ngay vì chưa tới service layer.
- Commit `P1-DB-06`/`P1-DB-07` (`396fbef`). Dispatch `P1-DB-08` (delivery claim/lease) một mình (không song song vì đụng cùng file `operation.py` với `P1-DB-09`/`10` sắp tới). Hoàn thành, verify độc lập lại (code review, ruff/mypy, PostgreSQL thật 212 test pass, PG-13 race re-run 3 lần), commit.
- User yêu cầu: từ task tiếp theo chỉ test happy-path/luồng chính, bỏ test edge-case/race/constraint đầy đủ để hoàn thành nhanh hơn — đã điều chỉnh scope cho `P1-DB-09`/`10` trở đi theo đúng yêu cầu này (coverage thấp hơn các task trước).
- Dispatch 1 subagent gộp cả `P1-DB-09` (callback claim/lease) và `P1-DB-10` (report/timeout transitions) để nhanh hơn thay vì 2 lượt. Hoàn thành, 5 test happy-path pass. Main session verify lại (ruff/mypy, PostgreSQL thật 217 test pass, offline suite/build/openapi-check) — pass hết, commit.
- Dispatch 1 subagent gộp Bearer auth + Operation service (đụng chung `config.py`/`conftest.py` nên không tách song song). Hoàn thành: `app/auth.py`, `app/services/operation.py`, mở rộng `Settings` (`public_api_client_id`, `public_api_scopes`), cập nhật `.env.example`/`infra/compose.yaml`. Main session verify lại đầy đủ (đọc code, ruff/mypy, PostgreSQL thật 231 test pass, offline/build/openapi/compose-config) — pass hết, không phát hiện secret rò rỉ trong `.env.example`, commit.

### 2026-08-02 — Session 3

- Bắt đầu hoàn tất phần còn lại của Phase 1: Structured logging & redaction, Public Status API, và vertical slice đầu tiên `music_volume`.
- Cài đặt `StructuredJsonFormatter`, `redact_sensitive_data`, `RequestCorrelationMiddleware` (`app/logging.py`, `tests/test_logging.py`).
- Cài đặt Status API `GET /api/v1/requests/{request_id}` (`app/api/status.py`, `tests/api/test_status_api.py`) hỗ trợ D-06 404 security response và Bearer scope `requests:read`.
- Cài đặt vertical slice `POST /api/v1/service/music/volume` (`app/api/service.py`, `tests/api/test_music_volume_api.py`, `tests/integration/test_music_volume_postgres.py`) với canonical request fingerprinting, idempotency D-10 reuse original `accepted_at`, và DB persistence.
- Wire routers, middleware và global exception handlers trong application factory (`app/application.py`).
- Chạy toàn bộ quality gates: `ruff format/check` (0 issues), `mypy` strict (0 issues trên 58 source files), `pytest` (244/244 passed trên container PostgreSQL `infra/compose.test.yaml` port 57432), `export_openapi.py --check` (passed), `uv build` (passed), `docker compose config` (passed).
- Hoàn tất Phase 1 hoàn toàn và sẵn sàng chuyển sang Phase 2 (P2 — Worker pipeline).


## Protocol tiếp tục ở session mới

1. Đọc `claude.md`, phần "Trạng thái hiện tại" của file này và nhật ký session mới nhất.
2. Chạy kiểm tra read-only trạng thái filesystem/Git và toolchain; không giả định kết quả session trước còn đúng.
3. Chọn checklist item chưa hoàn tất đầu tiên trong phase hiện tại.
4. Viết/cập nhật test cùng code, chạy quality gate áp dụng được.
5. Cập nhật checklist, bảng evidence, blocker và nhật ký trước khi kết thúc session.
