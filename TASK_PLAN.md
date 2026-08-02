# Kế hoạch triển khai App Communication Server

> Tracker bền vững qua nhiều session. Cập nhật file này sau mỗi thay đổi có ý nghĩa.
> Nguồn contract: `project_context.md`. Blueprint: `architeture.md`. Quy tắc bắt buộc: `claude.md`.

## Trạng thái hiện tại

- Ngày cập nhật: 2026-08-02
- Phase hiện tại: **P0 hoàn tất phần backend contract; chuẩn bị chuyển sang P1 — Database and API foundation** (P1 database foundation đã có sẵn nền tảng, xem mục P1 bên dưới).
- Mục tiêu phiên hiện tại (session 2): rà soát trạng thái thật của repo (khác với checklist cũ), khóa 13 điểm `CONTRACT_DECISIONS.md` theo quyết định của user, sửa gap dependency/lint/type khiến quality gates fail, xác minh Alembic migration trên PostgreSQL thật, và tạo initial Git commit.
- Môi trường đã xác nhận (session 2):
  - Python `3.13.12`, Docker `29.5.3`/Compose `v5.1.4`, `uv` vẫn hoạt động; `uv sync --frozen` pass sau khi thêm `sqlalchemy[asyncio]`, `alembic`, `asyncpg`.
  - JDK 17.0.20 và Android cmdline-tools đã có sẵn tại `D:\tmp\app-demo-android-toolchain` (bootstrap đã chạy trước đó), nhưng SDK licenses **chưa được accept** và platforms/build-tools chưa cài. User sẽ tự chạy `sdkmanager --licenses` và `.\scripts\bootstrap-android.ps1 -InstallSdkPackages`.
  - Git: initial commit đã được tạo theo yêu cầu của user (xem nhật ký session 2).

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
- [x] Khởi tạo Android project Kotlin/Compose một module tại `apps/android` (MainActivity, OverviewScreen/OverviewUiState, AppDemoTheme, unit test). Gradle `test lint assembleDebug` **chưa chạy được** vì Android SDK licenses chưa accept; user tự chạy sau, cần verify ở session sau.
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
- [ ] PostgreSQL test harness tái sử dụng được cho pytest (fixture tạo DB sạch + chạy migration mỗi test) — hiện chỉ verify thủ công qua `infra/compose.test.yaml`, chưa có fixture/test tự động (task `P1-DB-05`).
- [ ] Device repository (`P1-DB-06`), Operation insert/read (`P1-DB-07`), delivery claim (`P1-DB-08`), callback claim (`P1-DB-09`), report/timeout transitions (`P1-DB-10`) — chưa cài đặt, chưa có code trong `app/`.
- [ ] Áp dụng chính sách validation `D-07` (đã CHỐT): `extra="forbid"`, trim/reject chuỗi rỗng, timezone-aware UTC bắt buộc, giữ nguyên coordinate constraints — vào toàn bộ Public/Internal Pydantic schema hiện có, kèm test khẳng định reject extra field/chuỗi rỗng/naive datetime.
- [ ] Structured logging, redaction và request correlation.
- [ ] Bearer authentication cho Public/Device API và ownership (thiết kế đã có ở `docs/p1-api-plan.md`, chưa cài đặt code).
- [ ] Operation service với canonical fingerprint/idempotency.
- [ ] Status API.
- [ ] Vertical slice đầu tiên `music_volume`.
- [ ] PostgreSQL integration/API/contract tests cho P1 (matrix `PG-01`..`PG-25` trong `docs/p1-database-plan.md`; chỉ `PG-01`/`PG-02` đã verify thủ công, chưa có test tự động).

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

## Protocol tiếp tục ở session mới

1. Đọc `claude.md`, phần "Trạng thái hiện tại" của file này và nhật ký session mới nhất.
2. Chạy kiểm tra read-only trạng thái filesystem/Git và toolchain; không giả định kết quả session trước còn đúng.
3. Chọn checklist item chưa hoàn tất đầu tiên trong phase hiện tại.
4. Viết/cập nhật test cùng code, chạy quality gate áp dụng được.
5. Cập nhật checklist, bảng evidence, blocker và nhật ký trước khi kết thúc session.
