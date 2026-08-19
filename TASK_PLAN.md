# Káº¿ hoáº¡ch triá»ƒn khai App Communication Server

> Tracker bá» n vá»¯ng qua nhiá» u session. Cáº­p nháº­t file nÃ y sau má»—i thay Ä‘á»•i cÃ³ Ã½ nghÄ©a.
> Nguá»“n contract: `project_context.md`. Blueprint: `architeture.md`. Quy tá## Trạng thái hiện tại

- Ngày cập nhật: 2026-08-19
- Phase hiện tại: **P0-P5 đã hoàn tất (FCM thực tế)**; bắt đầu **P6 — mở rộng màn hình Android theo cấu trúc backend hiện có** (task giao bởi hohoangluan, nhánh `ui/new`, xem chi tiết ở mục P6 bên dưới).
- Thành tựu chính:
  - Backend: 9/9 Public APIs + 2 Internal Device APIs + Workers (Delivery, Timeout, Callback) + FCM Real Transport adapter (`firebase-admin`). Pass 197 unit/API tests + E2E simulation script trên PostgreSQL container.
  - Android: Package đổi sang `com.youreyes.app` cho khớp Firebase. Tích hợp FCM Push Receiver (`FcmPushReceiver`) tự động re-register FCM token. Cài đặt đầy đủ 9 Native Action Handlers (YouTube Music cho `media_play`, Google Maps cho `navigation_start`, Native Call/Camera/Volume/Settings/Overlay). `gradlew.bat assembleDebug` BUILD SUCCESSFUL.
- Môi trường đã xác nhận:
  - Python `3.13.12`, Docker `29.5.3`/Compose `v5.1.4`, `uv` hoáº¡t Ä‘á»™ng.
  - PostgreSQL test container `infra/compose.test.yaml` (port 57432).
- `[ ]` chÆ°a báº¯t Äáº§u
- `[-]` Äang thá»±c hiá»n
- `[x]` hoÃ n táº¥t vÃ  ÄÃ£ cÃ³ báº±ng chá»©ng kiá»m tra
- `[!]` bá» cháº·n; pháº£i ghi rÃµ nguyÃªn nhÃ¢n trong má»¥c "Blockers vÃ  quyáº¿t Äá»nh"

## P0 â Contracts and scaffold

- [x] Äá»c `claude.md`, `project_context.md` vÃ  `architeture.md`.
- [x] Scaffold Python project táº¡i `apps/backend`.
- [x] Táº¡o FastAPI application factory tá»i thiá»u vÃ  health endpoints.
- [x] Cáº¥u hÃ¬nh Ruff, mypy strict vÃ  pytest.
- [x] Táº¡o Dockerfile vÃ  Docker Compose cho backend/PostgreSQL.
- [x] Táº¡o cáº¥u trÃºc `contracts`, `docs` vÃ  CI ná»n táº£ng.
- [x] CÃ i/kháº£ dá»¥ng hÃ³a `uv` vÃ  sinh `uv.lock`.
- [x] Táº¡o initial Git commit chá»©a scaffold vÃ  `uv.lock` khi ÄÆ°á»£c yÃªu cáº§u (user ÄÃ£ yÃªu cáº§u á» session 2).
- [x] Khá»i táº¡o Android project Kotlin/Compose má»t module táº¡i `apps/android` (MainActivity, OverviewScreen/OverviewUiState, AppDemoTheme, unit test). User ÄÃ£ accept SDK licenses vÃ  cÃ i `platforms;android-37`/`build-tools;36.0.0`; `gradlew.bat test lint assembleDebug` cháº¡y PowerShell (khÃ´ng pháº£i Git Bash â Git Bash gÃ¢y lá»i path) verified: 2 unit test pass, lint 0 errors/8 warning khÃ´ng cháº·n (OldTargetApi, version bump, ModifierParameter, DataExtractionRules, MissingApplicationIcon â cosmetic, cháº¥p nháº­n ÄÆ°á»£c á» prototype), `assembleDebug` BUILD SUCCESSFUL.
- [x] Äá»nh nghÄ©a Äá»§ Pydantic schema cho 9 Public actions vÃ  2 Device endpoints (`app/schemas/service_requests.py`, `service_results.py`, `device.py`, `common.py`).
- [x] Export riÃªng Public/Internal OpenAPI tá»« schema thá»±c thi (`scripts/export_openapi.py`, `contracts/public-api.openapi.yaml`, `contracts/device-api.openapi.yaml`).
- [x] ThÃªm contract tests cho response envelope, examples vÃ  action mapping (`tests/test_openapi_contracts.py`, `test_service_requests.py`, `test_service_results.py`, `test_device_schemas.py`, `test_actions.py`).
- [x] Cháº¡y toÃ n bá» quality gates P0 vÃ  lÆ°u báº±ng chá»©ng bÃªn dÆ°á»i (session 2, sau khi sá»­a gap dependency/lint/type).

### TiÃªu chÃ­ hoÃ n táº¥t P0

- Backend cÃ i ÄÆ°á»£c tá»« lockfile trÃªn mÃ¡y sáº¡ch.
- FastAPI khá»i Äá»ng ÄÆ°á»£c vÃ  health endpoints cÃ³ test.
- Ruff format/check, mypy, pytest vÃ  build Äá»u pass.
- `docker compose config` pass; PostgreSQL vÃ  backend cÃ³ healthcheck.
- Public/Internal OpenAPI ÄÆ°á»£c sinh tá»« code vÃ  khÃ´ng pháº£i placeholder thá»§ cÃ´ng.
- Android scaffold build/test/lint ÄÆ°á»£c báº±ng Gradle wrapper.

## P1 â Database and API foundation

- [x] SQLAlchemy async engine/session vÃ  cáº¥u hÃ¬nh fail-fast (`app/database.py`, `app/config.py`; task `P1-DB-01`).
- [x] Models `devices`, `operations` ÄÃºng blueprint (`app/models/device.py`, `operation.py`, `enums.py`; task `P1-DB-02`/`P1-DB-03`).
- [x] Alembic baseline migration; test cáº£ database sáº¡ch vÃ  upgrade path â verified session 2 báº±ng `alembic upgrade head` â `downgrade base` â `upgrade head` trÃªn PostgreSQL tháº­t (test-compose), cá»t/check/index khá»p chÃ­nh xÃ¡c `docs/p1-database-plan.md` (task `P1-DB-04`, tÆ°Æ¡ng á»©ng `PG-01`/`PG-02`).
- [x] PostgreSQL test harness tÃ¡i sá»­ dá»¥ng ÄÆ°á»£c cho pytest (task `P1-DB-05`, session 2 qua subagent, verified Äá»c láº­p). `apps/backend/tests/integration/conftest.py`: fixture `test_database_url` (Äá»c `TEST_DATABASE_URL`, `pytest.skip` náº¿u unset), guard `ensure_test_only_database_url` (reject DB name khÃ´ng chá»©a `"test"`), `postgres_engine`/`postgres_session_factory` (session-scoped, cháº¡y `alembic upgrade head` má»t láº§n qua subprocess â khÃ´ng dÃ¹ng `Base.metadata.create_all`), `db_session` (function-scoped, `TRUNCATE operations, devices` sau má»i test Äá» giá»¯ connection Äá»c láº­p cho race test tÆ°Æ¡ng lai). `apps/backend/tests/integration/test_postgres_harness.py`: 4 test (guard x2, schema/constraint check qua `information_schema`/`pg_constraint`, insert round-trip tÃ´n trá»ng má»i CHECK constraint). Verify Äá»c láº­p: full suite 168 passed/2 skipped khÃ´ng cÃ³ Postgres; 170 passed (bao gá»m cáº£ 4 integration test) khi cÃ³ `TEST_DATABASE_URL` trá» tá»i `infra/compose.test.yaml` (port 57432). `docs/postgresql-testing.md` ÄÃ£ cáº­p nháº­t Äoáº¡n cÅ© nÃ³i "chÆ°a cÃ³ Alembic".
- [x] Device repository (`P1-DB-06`, session 2 qua subagent, verified Äá»c láº­p). `app/repositories/device.py::DeviceRepository`: `register()` cÃ i ÄÃºng D-05 (idempotent rotate cÃ¹ng owner; cáº¥m Äá»i owner â `DeviceOwnerMismatch`; revoked reject â `DeviceIsRevoked`; device má»i â deactivate device active khÃ¡c cá»§a user trong cÃ¹ng transaction), `get_latest_active_device()` dÃ¹ng shape `ix_devices_resolver`. Outcome lÃ  dataclass typed, khÃ´ng commit, khÃ´ng HTTP code. 10 test PG-03/04/05 má»i, táº¥t cáº£ pass trÃªn PostgreSQL tháº­t. **Edge case ÄÃ£ biáº¿t, chÆ°a xá»­ lÃ½** (agent tá»± flag, khÃ´ng tá»± ÄoÃ¡n thÃªm): re-register má»t device ÄÃ£ bá» deactivate bá»i device khÃ¡c sáº½ set nÃ³ `ACTIVE` trá» láº¡i mÃ  khÃ´ng tá»± Äá»ng deactivate sibling hiá»n Äang active â cÃ³ thá» táº¡m thá»i cÃ³ 2 active device cho 1 user cho tá»i láº§n register káº¿ tiáº¿p. Cáº§n quyáº¿t Äá»nh rÃµ khi lÃ m service layer.
- [x] Operation insert/read (`P1-DB-07`, session 2 qua subagent, verified Äá»c láº­p). `app/repositories/operation.py::OperationRepository`: `insert_or_get()` dÃ¹ng `INSERT ... ON CONFLICT (request_id) DO NOTHING RETURNING` (má»t `func.now()` duy nháº¥t cho `created_at`/`updated_at`/`next_delivery_at`), tráº£ `OperationInsertOutcome` (`INSERTED`/`IDEMPOTENT_MATCH`/`FINGERPRINT_CONFLICT`) â khÃ´ng tá»± chá»n HTTP/409; `get_by_request_id(client_id, request_id)` filter ownership ngay trong `WHERE` (khá»p D-06). 19 test PG-06 Äáº¿n PG-12 vÃ  PG-24, gá»m 2 test concurrency race (`asyncio.Barrier` + `wait_for` timeout há»¯u háº¡n, khÃ´ng sleep tÃ¹y Ã½) verify PG-11/PG-12 â cháº¡y láº¡i 5 láº§n liÃªn tiáº¿p trong main session, khÃ´ng flaky.
- [x] Delivery claim/lease (`P1-DB-08`, session 2 qua subagent, verified Äá»c láº­p). `OperationRepository.claim_due_deliveries()` (due predicate + `ORDER BY next_delivery_at NULLS LAST, created_at` + `FOR UPDATE SKIP LOCKED`, resolve device qua `DeviceRepository` cÃ¹ng transaction), `record_delivery_sent/transient_failure/permanent_failure()` (fenced conditional update theo `delivery_locked_until` chÃ­nh xÃ¡c, tráº£ `False` náº¿u lease ÄÃ£ stale). KhÃ´ng tá»± chá»n backoff duration hay error content â caller cung cáº¥p. **Judgment call ÄÃ£ flag**: claim bá» qua (khÃ´ng claim) operation náº¿u user khÃ´ng cÃ³ active device, thay vÃ¬ táº¡o failure transition â doc khÃ´ng quy Äá»nh rÃµ case nÃ y. 13 test PG-13 Äáº¿n PG-17 pass trÃªn PostgreSQL tháº­t, PG-13 (race) cháº¡y láº¡i 3 láº§n khÃ´ng flaky.
- [x] Callback claim/lease (`P1-DB-09`, session 2 qua subagent, verified). `OperationRepository.claim_due_callbacks()` (mirror delivery claim, chá» claim terminal operation, khÃ´ng resolve device), `record_callback_delivered/retry/dead_letter()` (fenced theo `callback_locked_until`, khÃ´ng Äá»¥ng `request_state`/`result`/`error`).
- [x] Report/timeout transitions (`P1-DB-10`, session 2 qua subagent, verified). `record_device_report()` (`SELECT FOR UPDATE`, 3-way outcome `APPLIED`/`IDEMPOTENT_DUPLICATE`/`CONFLICT` khá»p D-09; late-report-after-timeout rÆ¡i vÃ o `CONFLICT` vÃ¬ hash khÃ´ng khá»p â khÃ´ng cÃ³ state riÃªng), `record_timeout()` (conditional update `request_state='processing' AND expires_at<=now`, race vá»i report tá»± nhiÃªn serialize qua row lock). Error content, `callback_state` sau report Äá»u do caller cung cáº¥p, khÃ´ng hardcode `REPORT_TIMEOUT` string trong repo. Test: chá» happy-path (5 test) theo yÃªu cáº§u tÄng tá»c â bá» test PG-18 Äáº¿n PG-23 Äáº§y Äá»§ (fencing-reject, race report-vs-timeout Äá»ng thá»i, constraint). **Coverage tháº¥p hÆ¡n P1-DB-05â08, cáº§n bá» sung náº¿u sau nÃ y cáº§n chá»©ng minh race report-vs-timeout tháº­t.**
- [x] Ãp dá»¥ng chÃ­nh sÃ¡ch validation `D-07` (ÄÃ£ CHá»T) vÃ o toÃ n bá» Public/Internal Pydantic schema (session 2 qua subagent, verified Äá»c láº­p): `extra="forbid"` trÃªn má»i model á» `app/schemas/*.py`; `TrimmedNonEmptyStr` (reusable, `common.py`) cho field free-text do client gá»­i (`user_id`, `device_id`, `push_token`, `quote_id`, `song`, `navigation_id`, `name`); `Latitude`/`Longitude` reusable dÃ¹ng láº¡i á» `Destination` vÃ  `NavigationDestination` (trÆ°á»c ÄÃ³ lÃ  `float` khÃ´ng giá»i háº¡n â lá» há»ng ÄÃ£ ÄÃ³ng); `AwareUtcDatetime` reusable (reject naive datetime, normalize non-UTC offset vá» UTC) cho má»i field datetime. OpenAPI ÄÃ£ regenerate qua `scripts/export_openapi.py`, `--check` pass. KhÃ´ng thÃªm giá»i háº¡n Äá» dÃ i/sá» (ÄÃºng theo D-07: "pháº£i ghi rÃµ trong contract trÆ°á»c khi enforce"); khÃ´ng Äá»i logic `result`/`error` optional trÃªn `DeviceReportRequest` (náº±m ngoÃ i pháº¡m vi, ÄÃ£ cÃ³ `model_validator` riÃªng).
- [x] Structured logging, redaction vÃ  request correlation (`app/logging.py`, `StructuredJsonFormatter`, `RequestCorrelationMiddleware`, `tests/test_logging.py`).
- [x] Bearer authentication cho Public/Device API (session 2 qua subagent, verified). `app/auth.py`: `require_public_client_principal` (HMAC-SHA256 domain-separated digest, `hmac.compare_digest`, má»i lá»i tráº£ cÃ¹ng 401 Äá» khÃ´ng lá» thÃ´ng tin), `require_public_scope(scope)` (403 náº¿u thiáº¿u scope), `require_device_bearer_token` (chá» gate, khÃ´ng cÃ³ principal/scope â doc khÃ´ng Äá»nh nghÄ©a Device-side client identity, giá»¯ nguyÃªn theo yÃªu cáº§u báº£o thá»§). `Settings` thÃªm `public_api_client_id`, `public_api_scopes` (validate non-empty/known scope), enforce hash format 64-hex cho cáº£ Public/Device.
- [x] Operation service vá»i canonical fingerprint/idempotency (session 2 qua subagent, verified). `app/services/operation.py::OperationService.accept()`: `compute_request_fingerprint()` (SHA-256 cá»§a JSON canonical `{body, client_id, method, path}`, sort_keys, sau validation), map `OperationRepository` 3-way outcome â `AcceptOperationStatus.ACCEPTED`/`CONFLICT` (ACCEPTED dÃ¹ng `operation.created_at` gá»c ká» cáº£ duplicate, khá»p D-10). `_initial_callback_state()`: `callback_url` unset â `NOT_REQUIRED`, set â `PENDING`.
- [x] Status API (`app/api/status.py`, `GET /api/v1/requests/{request_id}`, scope `requests:read`, D-06 404 handling, `tests/api/test_status_api.py`).
- [x] Vertical slice Äáº§u tiÃªn `music_volume` (`app/api/service.py`, `POST /api/v1/service/music/volume`, scope `service:execute`, D-10 original `accepted_at` idempotency, `tests/api/test_music_volume_api.py`, `tests/integration/test_music_volume_postgres.py`).
- [x] PostgreSQL integration/API/contract tests cho P1 (toÃ n bá» 244 tests pass trÃªn PostgreSQL tháº­t).


## P2 â Worker pipeline

- [x] Delivery claim/lease/recovery vá»i `FOR UPDATE SKIP LOCKED` (`app/workers/delivery.py`).
- [x] Fake delivery adapter vÃ  FCM adapter boundary (`app/adapters/delivery.py`).
- [x] Retry classification/backoff vÃ  invalid-token handling (`DeliveryWorker`, `CallbackWorker`).
- [x] Timeout worker vÃ  report-timeout race handling (`app/workers/timeout.py`).
- [x] Callback worker, SSRF allow-list vÃ  dead-letter state (`app/adapters/callback.py`, `app/workers/callback.py`).
- [x] Concurrency, restart vÃ  idempotency tests trÃªn PostgreSQL tháº­t (252 tests pass).
 API (`app/api/status.py`, `GET /api/v1/requests/{request_id}`, scope `requests:read`, D-06 404 handling, `tests/api/test_status_api.py`).
- [x] Vertical slice Äáº§u tiÃªn `music_volume` (`app/api/service.py`, `POST /api/v1/service/music/volume`, scope `service:execute`, D-10 original `accepted_at` idempotency, `tests/api/test_music_volume_api.py`, `tests/integration/test_music_volume_postgres.py`).
- [x] PostgreSQL integration/API/contract tests cho P1 (toÃ n bá» 244 tests pass trÃªn PostgreSQL tháº­t).


## P2 â Worker pipeline

- [x] Delivery claim/lease/recovery vá»i `FOR UPDATE SKIP LOCKED`.
- [x] Fake delivery adapter vÃ  FCM adapter boundary.
- [x] Retry classification/backoff vÃ  invalid-token handling.
- [x] Timeout worker vÃ  report-timeout race handling.
- [x] Callback worker, SSRF allow-list vÃ  dead-letter state.
- [x] Concurrency, restart vÃ  idempotency tests trÃªn PostgreSQL tháº­t.

## P3 â Android pipeline

- [x] Compose shell vÃ  device registration.
- [x] FCM receiver chá» validate/persist/enqueue.
- [x] Room `commands` vÃ  `pending_reports`.
- [x] WorkManager dispatcher/report retry.
- [x] Compile-time mapping Äá»§ 9 actions, unknown action bá» tá»« chá»i.
- [x] Má»t fake handler cháº¡y E2E qua production dispatcher path.
- [x] Test duplicate FCM, process death vÃ  pending report recovery.

## P4 â Complete feature contracts

- [x] ThÃªm 8 actions cÃ²n láº¡i.
- [x] Deterministic fake adapters cho ride/music/navigation/emergency/contact.
- [x] Feature screens, Command Detail vÃ  Logs.
- [x] HoÃ n táº¥t action result/error validation.
- [x] HoÃ n táº¥t contract/E2E matrix báº¯t buá»™c.

## P5 — Real adapters and demo hardening

- [x] Real FCM trên thiết bị thật (google-services.json & service account credentials integrated, FcmPushReceiver enabled).
- [x] Android location/volume/contact/call intent adapters (Full Intent-based implementation in ActionRegistry.kt).
- [x] Google Maps & YouTube Music native integration via Android intents (Zero API Key needed).
- [x] Seed/reset scripts, demo script và troubleshooting guide (demo_e2e_simulation.py verified end-to-end).
- [x] Quality gates and build integrity verified.


## P6 — Android UI: mở rộng màn hình theo cấu trúc backend

Task giao bởi hohoangluan qua kênh khác (không phải qua TASK_PLAN gốc): "làm phần giao diện
cho app android ... để cho app hoàn chỉnh hơn". Đối chiếu 5 API group đã có ở
`apps/backend/src/app/api` (`auth`, `preferences`, `support`, `glasses`, `device`) với
UI hiện có trong `apps/android`, các màn hình còn thiếu được xếp việc theo thứ tự:

- [x] **Activity Log** — màn hình lịch sử lệnh kính đã gửi tới điện thoại (đặt xe, nhạc,
  điều hướng, khẩn cấp, gọi liên hệ...), đọc trực tiếp bảng `commands` có sẵn qua
  `AppDatabaseHelper.getAllCommands()` — không cần đổi backend. File mới:
  `ui/activitylog/ActivityLogUiState.kt` (pure mapping `CommandRecord -> ActivityLogRow`,
  unit-testable), `ActivityLogViewModel.kt`, `ActivityLogScreen.kt`; test mới
  `ActivityLogUiStateTest.kt` (9 test: label theo action, fallback action lạ, 3 trạng thái
  status kể cả status lạ, JSON lỗi định dạng không crash, `isEmpty`). Nối điều hướng: thêm
  `RowCard` mới trên `OverviewScreen`, thêm tab ẩn (`selectedTab = 6`) trong
  `MainActivity.AppRoot`. Không đổi `CommunityScreen`/`FeaturesScreen` (không có model
  backend hậu thuẫn "cộng đồng"; `FeaturesScreen`'s `onClick = null` là chủ đích — tính
  năng do kính tự kích hoạt qua giọng nói, không phải bấm từ điện thoại).
- [x] **Auth thật** (đăng ký/OTP/đăng nhập/đăng xuất, `POST /auth/register`,
  `/auth/otp/verify`, `/auth/login`, `/auth/logout`) + nối identity thật vào
  Profile/GlassesLink theo quyết định người dùng (phương án B: "Auth + nối luôn
  identity thật vào Profile/GlassesLink", chọn qua AskUserQuestion sau khi phát hiện
  mâu thuẫn tài liệu — `docs/superpowers/specs/2026-08-03-glasses-pairing-design.md`
  §10 đã chấp nhận rủi ro "chưa có login thật" cho `/device/glasses/link`, nhưng
  `/auth/*` + `/device/link` (session-gated) đã được thêm sau đó mà Android chưa từng
  gọi tới). **Không đổi** cơ chế xác thực của `/device/register`/`/device/glasses/link`
  (vẫn Device Bearer token dùng chung, đúng theo đánh đổi đã duyệt) — chỉ đổi *giá trị*
  `user_id` truyền vào các lệnh gọi đó, lấy từ tài khoản thật thay vì ô tự gõ.
  - File mới: `ui/auth/AuthUiState.kt` (validate số điện thoại ≥8 chữ số/mật khẩu
    ≥6 ký tự khớp `app/schemas/auth.py`, unit-testable), `AuthViewModel.kt` (lưu
    session vào `SharedPreferences` key riêng `auth_*`, đồng thời ghi đè
    `FcmPushReceiver.KEY_USER_ID` bằng `public_user_id` thật), `AuthScreen.kt`
    (toggle Đăng nhập/Đăng ký → bước OTP → trạng thái đã đăng nhập + nút Đăng xuất).
  - `DeviceApiClient.kt`: 4 method mới (`registerAccount`, `verifyOtp`, `login`,
    `logout`) qua 2 helper private mới (`postJson`/`readBody`), không đụng 3 method
    cũ (`registerDevice`/`sendReport`/`linkGlassesDevice`).
  - **Vấn đề "ViewModel đọc SharedPreferences 1 lần lúc khởi tạo, không tự cập nhật
    khi đổi tab" đã xử lý**: thêm `refreshUserId()` vào `OverviewViewModel` và
    `GlassesLinkViewModel`, gọi qua `LaunchedEffect(Unit)` mỗi khi `OverviewRoute`/
    `ProfileRoute`/`GlassesLinkRoute` được vào lại — nếu không có bước này, đăng nhập
    xong quay lại tab Trang chủ/Hồ sơ/Pairing kính vẫn hiện `user_id` cũ do
    `AndroidViewModel` được Compose cache theo vòng đời Activity, không phải theo tab.
  - Test mới `AuthUiStateTest.kt` (7 test: boundary số điện thoại/mật khẩu, số điện
    thoại có dấu gạch/khoảng trắng vẫn đếm đúng chữ số, OTP rỗng, đang loading,
    `isLoggedIn`). Không unit-test `AuthViewModel`/`DeviceApiClient` trực tiếp — nhất
    quán với toàn bộ `AndroidViewModel`/network method khác trong repo (không có
    Robolectric/MockWebServer, chỉ UiState thuần được test).
  - Nối navigation: `ProfileScreen` thêm `RowCard` "Tài Khoản Đăng Nhập" đầu trang
    (trước mục "Cấu Hình Số Khẩn Cấp"), tab mới `selectedTab = 7` trong
    `MainActivity.AppRoot`.
- [x] **Nối `GET/PUT /preferences` thật** cho khối "Cài Đặt Trợ Năng" ở Profile, thay
  hoàn toàn `remember { mutableStateOf(...) }` cục bộ trước đó. **Tìm thấy và sửa 1 lỗi
  contract có sẵn khi wiring**: UI cũ dùng chip "Lớn" cho cỡ chữ, nhưng
  `app/schemas/preferences.py`'s `FontSizeOption = Literal["Nhỏ","Vừa","To"]` — gửi
  "Lớn" sẽ bị server từ chối 400. Đã sửa về đúng `"To"`.
  - File mới: `ui/preferences/PreferencesUiState.kt` (`FONT_SIZE_OPTIONS`/
    `VOICE_OPTIONS` là nguồn duy nhất cho domain hợp lệ, `canEdit` chỉ true khi đã đăng
    nhập và không đang loading), `PreferencesViewModel.kt` (load khi có session, mỗi
    thay đổi field lưu ngay lập tức — 4 field nhỏ, không cần nút Lưu riêng).
  - `DeviceApiClient.kt`: generalize `postJson` private cũ (viết ở task Auth) thành
    `requestJson` hỗ trợ GET/PUT, thêm `getPreferences`/`updatePreferences`. 3 method
    Device Bearer token gốc và 4 method Auth không đổi hành vi.
  - `ProfileScreen`: bỏ toàn bộ `remember` cục bộ của khối trợ năng, dùng
    `PreferencesViewModel` qua `viewModel()` (giống pattern `OverviewViewModel`);
    `ChipButton` private thêm tham số `enabled` (chưa có trước đó) để khoá UI khi chưa
    đăng nhập; hiện thông báo "Đăng nhập để lưu cài đặt" khi `!isLoggedIn`.
  - Test mới `PreferencesUiStateTest.kt` (5 test: `canEdit` theo 3 tổ hợp
    logged-in/loading, đúng danh sách `FONT_SIZE_OPTIONS`/`VOICE_OPTIONS` — có test
    regression xác nhận không còn "Lớn" sai).
- [ ] Màn hình gửi yêu cầu hỗ trợ (`POST /support/tickets`) — chưa có UI nào.
- [ ] Hủy liên kết kính (`POST /device/glasses/unlink`) — `GlassesLinkScreen` mới chỉ có link.
- [ ] (Thấp ưu tiên) Widget trạng thái nhạc/điều hướng đang chạy trên Overview.

Ghi chú kiến trúc phát hiện được: hệ thống này ("App Communication Server") là
**trung gian** giữa Server Kính (external, ngoài phạm vi — xem
`docs/glasses-server-client-api.md` §"Ngoài phạm vi") và app Android, KHÔNG PHẢI bản thân
Server Kính. `base URL` mặc định (`OverviewViewModel.DEFAULT_SERVER_URL`) và package
Android (`com.youreyes.app`) xác nhận đây cùng hệ sinh thái "Your Eyes" với repo
`your-eyes-project/backend`, nhưng là 2 backend riêng — cần xác nhận với hohoangluan xem
tính năng `dispatch_kinh_action` bên `backend/` (billing) có bị trùng vai trò với hệ thống
này hay không trước khi phát triển thêm cả hai song song.

Nhánh làm việc: `ui/new` (tạo local, chưa push — chờ hoàn tất từng mục rồi push theo yêu
cầu gốc của hohoangluan).

### P6 quality-gate evidence (bảng riêng — bảng evidence gốc bên dưới có lỗi encoding cũ, không sửa để tránh hỏng thêm)

| Ngày | Phạm vi | Lệnh | Kết quả |
|---|---|---|---|
| 2026-08-19 | P6 Activity Log (session 6) | PowerShell: `$env:ANDROID_HOME`/`$env:JAVA_HOME` set thủ công (SDK tại `C:\Users\Bong\AppData\Local\Android\Sdk`, JDK Temurin 25), `.\gradlew.bat test --no-daemon` | Pass — `ActivityLogUiStateTest` 9/9 mới pass, toàn bộ `testDebugUnitTest` BUILD SUCCESSFUL, không test cũ nào hỏng |
| 2026-08-19 | P6 Activity Log (session 6) | `.\gradlew.bat lint assembleDebug --no-daemon` | Pass — 0 lỗi lint (73 warning, toàn bộ thuộc các category cosmetic có sẵn từ trước; `ModifierParameter` xuất hiện thêm 2 lần đúng theo pattern `modifier` cuối cùng mà mọi Screen khác trong repo đã dùng); `assembleDebug` BUILD SUCCESSFUL |
| 2026-08-19 | P6 Auth (session 6) | `.\gradlew.bat test --no-daemon` | Pass — `AuthUiStateTest` 7/7 mới pass, toàn bộ suite BUILD SUCCESSFUL |
| 2026-08-19 | P6 Auth (session 6) | `.\gradlew.bat lint assembleDebug --no-daemon` | Pass — 0 lỗi lint (`ModifierParameter` 4→5, `Use KTX extension function` 13→15, cùng category cosmetic có sẵn, không category mới); `assembleDebug` BUILD SUCCESSFUL |
| 2026-08-19 | P6 Preferences (session 6) | `.\gradlew.bat test --no-daemon` | Pass — `PreferencesUiStateTest` 5/5 mới pass, toàn bộ suite BUILD SUCCESSFUL |
| 2026-08-19 | P6 Preferences (session 6) | `.\gradlew.bat lint assembleDebug --no-daemon` | Pass — 0 lỗi lint, số lượng warning mỗi category không đổi so với lần chạy trước (không category mới); `assembleDebug` BUILD SUCCESSFUL |

Ghi chú môi trường: `apps/android/app/google-services.json` không có trong repo (đã bị
`.gitignore` loại từ trước) nên phải tạo file placeholder cục bộ (không phải credential
thật, chỉ đủ để Google Services Gradle plugin không crash khi build/test) — không commit,
đã xác nhận vẫn nằm trong `.gitignore`.

## Quality-gate evidence

Ghi chÃ­nh xÃ¡c lá»‡nh, ngÃ y vÃ  káº¿t quáº£. KhÃ´ng Ä‘Ã¡nh dáº¥u hoÃ n táº¥t náº¿u lá»‡nh chÆ°a cháº¡y.

| NgÃ y | Pháº¡m vi | Lá»‡nh | Káº¿t quáº£ |
|---|---|---|---|
| 2026-08-02 | Toolchain | `python --version` | Pass â Python 3.13.12 |
| 2026-08-02 | Toolchain | `docker --version` | Pass â Docker 29.5.3 |
| 2026-08-02 | Toolchain | `docker compose version` | Pass â Compose v5.1.4 |
| 2026-08-02 | Toolchain | `python -m uv --version` | Pass â uv 0.12.1 |
| 2026-08-02 | Backend deps | `python -m uv sync --frozen` | Pass â 35 packages checked |
| 2026-08-02 | Backend format | `python -m uv run ruff format --check .` | Pass â 9 files formatted |
| 2026-08-02 | Backend lint | `python -m uv run ruff check .` | Pass |
| 2026-08-02 | Backend types | `python -m uv run mypy src tests` | Pass â 9 files, no issues |
| 2026-08-02 | Backend tests | `python -m uv run pytest` | Pass â 8 tests |
| 2026-08-02 | Backend package | `python -m uv build` | Pass â sdist vÃ  wheel |
| 2026-08-02 | Compose | `docker compose -f infra/compose.yaml config --quiet` | Pass |
| 2026-08-02 | Container | `docker compose -f infra/compose.yaml build backend` | Pass |
| 2026-08-02 | Container smoke | Temporary `docker run` + health probes | Pass â live 200, ready 503, user `app` |
| 2026-08-02 | Backend deps (session 2) | `python -m uv lock` rá»i `python -m uv sync --frozen` | Pass â thÃªm `sqlalchemy[asyncio]`, `alembic`, `asyncpg` (42 packages) |
| 2026-08-02 | Backend format (session 2) | `python -m uv run ruff format --check .` | Pass â 37 files formatted (sau khi sá»­a 1 file) |
| 2026-08-02 | Backend lint (session 2) | `python -m uv run ruff check .` | Pass â sau khi sá»­a N811/UP047/I001/INP001 á» `models/`, `alembic/` |
| 2026-08-02 | Backend types (session 2) | `python -m uv run mypy src tests scripts` | Pass â 33 source files, no issues |
| 2026-08-02 | Backend tests (session 2) | `python -m uv run pytest` | Pass â 98 tests |
| 2026-08-02 | OpenAPI drift (session 2) | `python -m uv run python scripts/export_openapi.py --check` | Pass â artifacts up to date |
| 2026-08-02 | Backend package (session 2) | `python -m uv build` | Pass â sdist vÃ  wheel |
| 2026-08-02 | Compose (session 2) | `docker compose -f infra/compose.yaml config --quiet` | Pass |
| 2026-08-02 | Container (session 2) | `docker compose -f infra/compose.yaml build backend` | Pass â build láº¡i sau khi Äá»i dependency |
| 2026-08-02 | Migration trÃªn PostgreSQL tháº­t (session 2) | `docker compose -f infra/compose.test.yaml up -d --wait postgres-test` (port 57432, port 55432 máº·c Äá»nh bá» Windows cháº·n) rá»i `alembic upgrade head` â `alembic downgrade base` â `alembic upgrade head` | Pass â hai báº£ng, ÄÃºng cá»t/check/index nhÆ° `docs/p1-database-plan.md`; ÄÃ£ xÃ¡c nháº­n báº±ng `psql \d devices` vÃ  `\d operations` |
| 2026-08-02 | Android (session 2, sau khi user accept SDK licenses) | PowerShell: `$env:JAVA_HOME/$env:ANDROID_HOME` set thá»§ cÃ´ng, `.\gradlew.bat test lint assembleDebug --no-daemon` | Pass â `OverviewUiStateTest` 2/2 test pass; lint 0 errors, 8 warning cosmetic; `assembleDebug` BUILD SUCCESSFUL (51 tasks) |
| 2026-08-02 | D-07 + P1-DB-05 (session 2, qua 2 subagent song song, verify Äá»c láº­p láº¡i bá»i main session) | `uv run ruff format --check .` / `ruff check .` / `mypy src tests scripts` | Pass â 40/41 file formatted, all lint checks pass, 37 source files no mypy issue |
| 2026-08-02 | D-07 + P1-DB-05 (session 2) | `uv run pytest -q` (khÃ´ng cÃ³ `TEST_DATABASE_URL`) | Pass â 168 passed, 2 skipped (tÄng tá»« baseline 98, khÃ´ng cÃ³ test nÃ o bá» lÃ m yáº¿u/xÃ³a) |
| 2026-08-02 | P1-DB-05 (session 2) | `docker compose -f infra/compose.test.yaml up -d --wait postgres-test` (port 57432) + `TEST_DATABASE_URL=...` `uv run pytest -q` | Pass â 170 passed (bao gá»m 4 test PostgreSQL integration má»i cháº¡y tháº­t, khÃ´ng skip); teardown `docker compose down --remove-orphans` sau khi verify |
| 2026-08-02 | D-07 (session 2) | `uv run python scripts/export_openapi.py` rá»i `--check` | Pass â regenerate xong, "OpenAPI artifacts are up to date." |
| 2026-08-02 | D-07 + P1-DB-05 (session 2) | `uv build` | Pass â sdist vÃ  wheel |
| 2026-08-02 | P1-DB-06 + P1-DB-07 (session 2, qua 2 subagent song song, verify Äá»c láº­p láº¡i) | `ruff format --check .` / `ruff check .` / `mypy src tests scripts` | Pass â 46 file formatted, all lint pass, 42 source file no mypy issue |
| 2026-08-02 | P1-DB-06 + P1-DB-07 (session 2) | `docker compose -f infra/compose.test.yaml up` (port 57432) + `TEST_DATABASE_URL=...` `uv run pytest -q` | Pass â 199 passed (98 gá»c + 4 harness + 10 device repo + 19 operation repo + test khÃ¡c ÄÃ£ thÃªm trÆ°á»c ÄÃ³) |
| 2026-08-02 | P1-DB-07 concurrency (session 2) | Cháº¡y láº¡i riÃªng test PG-11/PG-12 (`asyncio.Barrier`) 5 láº§n liÃªn tiáº¿p | Pass cáº£ 5 láº§n â khÃ´ng flaky |
| 2026-08-02 | P1-DB-06 + P1-DB-07 (session 2) | `uv run pytest -q` (khÃ´ng `TEST_DATABASE_URL`) | Pass â 168 passed, 31 skipped, teardown container sau ÄÃ³ |
| 2026-08-02 | P1-DB-06 + P1-DB-07 (session 2) | `uv build`, `export_openapi.py --check` | Pass cáº£ hai |
| 2026-08-02 | P1-DB-08 (session 2, qua subagent, verify Äá»c láº­p) | `ruff format/check`, `mypy`, PostgreSQL tháº­t `pytest -q` | Pass â 212 passed; PG-13 race re-run 3 láº§n khÃ´ng flaky |
| 2026-08-02 | P1-DB-08 (session 2) | `pytest -q` (khÃ´ng DB), `uv build`, `export_openapi.py --check` | Pass â 168 passed/44 skipped, build + openapi Äá»u pass |
| 2026-08-02 | P1-DB-09 + P1-DB-10 (session 2, qua subagent, happy-path only) | `ruff format/check`, `mypy`, PostgreSQL tháº­t `pytest -q` | Pass â 217 passed |
| 2026-08-02 | P1-DB-09 + P1-DB-10 (session 2) | `pytest -q` (khÃ´ng DB), `uv build`, `export_openapi.py --check` | Pass â 168 passed/49 skipped, build + openapi Äá»u pass |
| 2026-08-02 | Bearer auth + Operation service (session 2, qua subagent, verify Äá»c láº­p) | `ruff format/check`, `mypy`, PostgreSQL tháº­t `pytest -q` | Pass â 231 passed |
| 2026-08-02 | Bearer auth + Operation service (session 2) | `pytest -q` (khÃ´ng DB), `uv build`, `export_openapi.py --check`, `docker compose -f infra/compose.yaml config --quiet` | Pass â 179 passed/52 skipped, build/openapi/compose config Äá»u pass |
| 2026-08-02 | Structured logging, Status API, Vertical slice music_volume (session 3) | `ruff format/check`, `mypy`, `pytest` (PostgreSQL tháº­t port 57432), `export_openapi.py --check`, `uv build`, `docker compose config` | Pass â 244 passed on PostgreSQL, 0 lint/type issues, openapi/build/compose config clean |


## Blockers vÃ  quyáº¿t Äá»nh

- OpenAPI artifact chá» ÄÆ°á»£c táº¡o tá»« Pydantic/FastAPI schema ÄÃ£ cÃ i Äáº·t; khÃ´ng viáº¿t spec placeholder rá»i coi lÃ  contract hoÃ n táº¥t.
- `CONTRACT_DECISIONS.md` D-01 Äáº¿n D-13 ÄÃ£ ÄÆ°á»£c user CHá»T theo ÄÃºng recommended default á» session 2 (xem Decisions log trong file ÄÃ³). Viá»c chá»t lÃ  quyáº¿t Äá»nh business/contract; **code chÆ°a cÃ i Äáº·t háº¿t há» quáº£** â Äáº·c biá»t D-07 (validation policy) chÆ°a Ã¡p dá»¥ng vÃ o schema, vÃ  D-01/D-02/D-03/D-04/D-05/D-09/D-10/D-12 phá»¥ thuá»c service/repository/worker layer chÆ°a tá»n táº¡i. Xem cÃ¡c má»¥c `[ ]` má»i thÃªm trong P1.
- Android SDK licenses chÆ°a ÄÆ°á»£c user accept; Gradle `test lint assembleDebug` chÆ°a cháº¡y ÄÆ°á»£c trong mÃ´i trÆ°á»ng nÃ y. Cáº§n xÃ¡c nháº­n á» session sau báº±ng cÃ¡ch há»i user hoáº·c tá»± kiá»m tra `D:\tmp\app-demo-android-toolchain\android-sdk\licenses`.
- Port `55432` (cá»ng máº·c Äá»nh cho PostgreSQL test theo `docs/postgresql-testing.md`) bá» Windows cháº·n ("access forbidden by its access permissions") trÃªn mÃ¡y nÃ y; session 2 dÃ¹ng `TEST_POSTGRES_PORT=57432` Äá» vÃ²ng qua. ChÆ°a rÃµ nguyÃªn nhÃ¢n gá»c (cÃ³ thá» do Hyper-V dynamic port exclusion range) â náº¿u láº·p láº¡i, cÃ¢n nháº¯c cáº­p nháº­t `docs/postgresql-testing.md` Äá» ghi chÃº port thay tháº¿.
- Repository chÆ°a cÃ³ commit nÃ o trÆ°á»c session 2; ÄÃ£ táº¡o initial commit theo yÃªu cáº§u rÃµ rÃ ng cá»§a user.

## Nháº­t kÃ½ session

### 2026-08-02 â Session 1

- Báº¯t Äáº§u P0.
- Chia cÃ´ng viá»c song song cho contract audit, backend scaffold vÃ  infra/CI scaffold.
- XÃ¡c nháº­n toolchain vÃ  táº¡o tracker nÃ y.
- Khá»i táº¡o Git repository theo yÃªu cáº§u cá»§a ngÆ°á»i dÃ¹ng; branch ban Äáº§u lÃ  `main`.
- HoÃ n táº¥t backend scaffold, config fail-fast, async health tests vÃ  lockfile.
- HoÃ n táº¥t Compose PostgreSQL/backend, Docker image cháº¡y non-root, CI, docs vÃ  `.env.example`.
- Backend quality gates pass; Docker image build vÃ  health smoke-test pass.
- Audit contract ÄÆ°á»£c lÆ°u táº¡i `CONTRACT_DECISIONS.md`; chÆ°a tá»± quyáº¿t cÃ¡c Äiá»m mÃ¢u thuáº«n.
- (KhÃ´ng ghi log rÃµ rÃ ng, nhÆ°ng phÃ¡t hiá»n á» session 2: cÃ¹ng khoáº£ng thá»i gian nÃ y, code ÄÃ£ tiáº¿n xa hÆ¡n checklist ghi nháº­n â 9 Public action schema, 2 Device schema, OpenAPI export/contract test, vÃ  ná»n táº£ng P1 database (SQLAlchemy models, Alembic migration) ÄÃ£ ÄÆ°á»£c viáº¿t nhÆ°ng chÆ°a cáº­p nháº­t vÃ o tracker nÃ y vÃ  chÆ°a tá»«ng cháº¡y quality gate thÃ nh cÃ´ng do thiáº¿u dependency.)

### 2026-08-02 â Session 2

- Äá»c láº¡i `TASK_PLAN.md`, `CONTRACT_DECISIONS.md`, vÃ  audit thá»±c táº¿ `git status`/filesystem thay vÃ¬ tin checklist cÅ© â phÃ¡t hiá»n code ÄÃ£ vÆ°á»£t xa checklist (schema/OpenAPI/contract test P0 ÄÃ£ viáº¿t xong; P1 database foundation ÄÃ£ cÃ³ draft) nhÆ°ng bá» há»ng quality gate vÃ¬ `pyproject.toml` thiáº¿u `sqlalchemy`/`alembic`/`asyncpg`.
- Há»i user 3 quyáº¿t Äá»nh: (1) táº¡o initial commit ngay, (2) duyá»t toÃ n bá» 13 recommended default trong `CONTRACT_DECISIONS.md`, (3) khÃ´ng tá»± Äá»ng accept Android SDK licenses. User chá»n cáº£ ba theo hÆ°á»ng "Recommended".
- Sá»­a `pyproject.toml` (thÃªm 3 dependency cÃ²n thiáº¿u), `uv lock`, sá»­a lint/format (N811 alias `PostgreSQLUUID`â`PG_UUID`, UP047 PEP 695 generics á» `models/enums.py`, import order, trailing whitespace, thÃªm `alembic/__init__.py` vÃ  `alembic/versions/__init__.py` cho INP001).
- ToÃ n bá» backend quality gates pass: ruff format/check, mypy strict (33 files), pytest (98 tests), OpenAPI `--check`, `uv build`, `docker compose build backend`.
- Khá»i Äá»ng PostgreSQL test táº¡m (`infra/compose.test.yaml`, port 57432 do port máº·c Äá»nh 55432 bá» há» Äiá»u hÃ nh cháº·n) vÃ  cháº¡y `alembic upgrade head` â `downgrade base` â `upgrade head`; xÃ¡c nháº­n báº£ng/cá»t/check/index khá»p chÃ­nh xÃ¡c `docs/p1-database-plan.md`.
- KhÃ³a 13 Äiá»m `CONTRACT_DECISIONS.md` (D-01 Äáº¿n D-13) theo recommended default, ghi Äáº§y Äá»§ Decisions log kÃ¨m tráº¡ng thÃ¡i code (ÄÃ£ khá»p / chÆ°a cÃ i Äáº·t) cho tá»«ng Äiá»m.
- Cáº­p nháº­t `TASK_PLAN.md`: ÄÃ¡nh dáº¥u cÃ¡c má»¥c P0 ÄÃ£ hoÃ n táº¥t thá»±c sá»±, thÃªm task P1 database (`P1-DB-01`â`P1-DB-04` done, `P1-DB-05`+ chÆ°a lÃ m), thÃªm viá»c cÃ²n thiáº¿u (D-07 validation policy chÆ°a cÃ i Äáº·t), cáº­p nháº­t báº£ng evidence vÃ  blockers.
- ÄÆ°a hÆ°á»ng dáº«n accept Android SDK license cho user tá»± cháº¡y (khÃ´ng tá»± Äá»ng pipe "y").
- Táº¡o initial Git commit theo yÃªu cáº§u cá»§a user (xem commit message).
- User accept Android SDK licenses xong, bÃ¡o láº¡i. Verify Android build qua PowerShell (Git Bash gÃ¢y lá»i path khi gá»i `gradlew.bat`, chuyá»n sang PowerShell native): `test lint assembleDebug` pass.
- User yÃªu cáº§u cháº¡y subagent hoÃ n thÃ nh task. Dispatch 2 subagent song song (background), pháº¡m vi Äá»c láº­p nhau, khÃ´ng cho commit hoáº·c sá»­a `TASK_PLAN.md`/`CONTRACT_DECISIONS.md`:
  1. `P1-DB-05` PostgreSQL test harness (pytest fixture reusable, migration-once-per-session + truncate-per-test, guard cháº·n URL khÃ´ng pháº£i test-db).
  2. Ãp dá»¥ng `D-07` validation policy vÃ o toÃ n bá» Public/Internal Pydantic schema.
- Cáº£ hai subagent hoÃ n thÃ nh, tá»± bÃ¡o cÃ¡o Äáº§y Äá»§ file thay Äá»i/judgment call/lá»nh verify. Main session verify Äá»c láº­p láº¡i toÃ n bá» (khÃ´ng tin bÃ¡o cÃ¡o suÃ´ng): Äá»c diff tháº­t, tá»± cháº¡y `ruff format/check`, `mypy`, `pytest` khÃ´ng DB (168 passed/2 skipped), tá»± spin PostgreSQL tháº­t vÃ  cháº¡y láº¡i (170 passed, 4 integration test cháº¡y tháº­t khÃ´ng skip), tá»± cháº¡y `export_openapi.py --check` vÃ  `uv build`. Táº¥t cáº£ pass, khÃ´ng phÃ¡t hiá»n sai lá»ch so vá»i bÃ¡o cÃ¡o subagent.
- Cáº­p nháº­t `TASK_PLAN.md`: ÄÃ¡nh dáº¥u `P1-DB-05` vÃ  D-07 validation policy hoÃ n táº¥t vá»i báº±ng chá»©ng; ghi rÃµ `P1-DB-06` Äáº¿n `P1-DB-10` (repository layer) váº«n chÆ°a lÃ m, giá» ÄÃ£ cÃ³ harness sáºµn sÃ ng Äá» báº¯t Äáº§u.
- Commit `P1-DB-05` + D-07 (`c3b2530`). User bÃ¡o tiáº¿p tá»¥c, nháº¯c Äá» context tá»± compact giá»¯a cÃ¡c task â dÃ¹ng `TASK_PLAN.md` lÃ m báº±ng chá»©ng bá»n vá»¯ng qua compaction.
- Dispatch tiáº¿p 2 subagent song song: `P1-DB-06` (Device repository, D-05) vÃ  `P1-DB-07` (Operation insert/read + idempotency, PG-06â12/24, gá»m 2 test concurrency race dÃ¹ng `asyncio.Barrier`). Cáº£ hai hoÃ n thÃ nh, tá»± bÃ¡o cÃ¡o Äáº§y Äá»§. Main session verify Äá»c láº­p láº¡i toÃ n bá»: Äá»c code tháº­t, tá»± cháº¡y `ruff`/`mypy`, tá»± spin PostgreSQL tháº­t cháº¡y 199 test pass, tá»± cháº¡y láº¡i riÃªng 2 test concurrency 5 láº§n Äá» loáº¡i flaky, tá»± cháº¡y offline suite/build/openapi-check. KhÃ´ng phÃ¡t hiá»n sai lá»ch so vá»i bÃ¡o cÃ¡o subagent. Device repo agent tá»± flag má»t edge case chÆ°a xá»­ lÃ½ (re-register device ÄÃ£ bá» deactivate bá»i sibling khÃ¡c) â ghi láº¡i trong checklist, chÆ°a cáº§n quyáº¿t Äá»nh ngay vÃ¬ chÆ°a tá»i service layer.
- Commit `P1-DB-06`/`P1-DB-07` (`396fbef`). Dispatch `P1-DB-08` (delivery claim/lease) má»t mÃ¬nh (khÃ´ng song song vÃ¬ Äá»¥ng cÃ¹ng file `operation.py` vá»i `P1-DB-09`/`10` sáº¯p tá»i). HoÃ n thÃ nh, verify Äá»c láº­p láº¡i (code review, ruff/mypy, PostgreSQL tháº­t 212 test pass, PG-13 race re-run 3 láº§n), commit.
- User yÃªu cáº§u: tá»« task tiáº¿p theo chá» test happy-path/luá»ng chÃ­nh, bá» test edge-case/race/constraint Äáº§y Äá»§ Äá» hoÃ n thÃ nh nhanh hÆ¡n â ÄÃ£ Äiá»u chá»nh scope cho `P1-DB-09`/`10` trá» Äi theo ÄÃºng yÃªu cáº§u nÃ y (coverage tháº¥p hÆ¡n cÃ¡c task trÆ°á»c).
- Dispatch 1 subagent gá»p cáº£ `P1-DB-09` (callback claim/lease) vÃ  `P1-DB-10` (report/timeout transitions) Äá» nhanh hÆ¡n thay vÃ¬ 2 lÆ°á»£t. HoÃ n thÃ nh, 5 test happy-path pass. Main session verify láº¡i (ruff/mypy, PostgreSQL tháº­t 217 test pass, offline suite/build/openapi-check) â pass háº¿t, commit.
- Dispatch 1 subagent gá»p Bearer auth + Operation service (Äá»¥ng chung `config.py`/`conftest.py` nÃªn khÃ´ng tÃ¡ch song song). HoÃ n thÃ nh: `app/auth.py`, `app/services/operation.py`, má» rá»ng `Settings` (`public_api_client_id`, `public_api_scopes`), cáº­p nháº­t `.env.example`/`infra/compose.yaml`. Main session verify láº¡i Äáº§y Äá»§ (Äá»c code, ruff/mypy, PostgreSQL tháº­t 231 test pass, offline/build/openapi/compose-config) â pass háº¿t, khÃ´ng phÃ¡t hiá»n secret rÃ² rá» trong `.env.example`, commit.
  1. `P1-DB-05` PostgreSQL test harness.
  2. Ã p dá»¥ng `D-07` validation policy vÃ o toÃ n bá»™ Public/Internal Pydantic schema.
- Main session verify láº¡i: ruff/mypy, pytest (168 passed/2 skipped), PostgreSQL tháº­t (170 passed), `export_openapi.py --check`, `uv build`.
- Dispatch tiáº¿p subagent: `P1-DB-06` (Device repository), `P1-DB-07` (Operation insert/read + idempotency, PG-06â€“12/24). Main session verify láº¡i: 199 test pass, test concurrency ok.
- Commit `P1-DB-06`/`P1-DB-07`. Dispatch `P1-DB-08` (delivery claim/lease). HoÃ n thÃ nh, verify (212 test pass), commit.
- Dispatch 1 subagent gá»™p `P1-DB-09`/`P1-DB-10` (callback claim/lease, report/timeout transitions). Main session verify (217 test pass), commit.
- Dispatch 1 subagent gá»™p Bearer auth + Operation service. Main session verify (231 test pass), commit.

### 2026-08-02 â€” Session 3

- Báº¯t Ä‘áº¡u hoÃ n táº¥t pháº§n cÃ²n láº¡i cá»§a Phase 1: Structured logging & redaction, Public Status API, vÃ  vertical slice Ä‘áº§u tiÃªn `music_volume`.
- CÃ i Ä‘áº·t `StructuredJsonFormatter`, `redact_sensitive_data`, `RequestCorrelationMiddleware` (`app/logging.py`, `tests/test_logging.py`).
- CÃ i Ä‘áº·t Status API `GET /api/v1/requests/{request_id}` (`app/api/status.py`, `tests/api/test_status_api.py`) há»— trá»£ D-06 404 security response vÃ  Bearer scope `requests:read`.
- CÃ i Ä‘áº·t vertical slice `POST /api/v1/service/music/volume` (`app/api/service.py`, `tests/api/test_music_volume_api.py`, `tests/integration/test_music_volume_postgres.py`) vá»›i canonical request fingerprinting, idempotency D-10 reuse original `accepted_at`, vÃ  DB persistence.
- Wire routers, middleware vÃ  global exception handlers trong application factory (`app/application.py`).
- Cháº¡y toÃ n bá»™ quality gates: `ruff format/check` (0 issues), `mypy` strict (0 issues trÃªn 58 source files), `pytest` (244/244 passed trÃªn container PostgreSQL `infra/compose.test.yaml` port 57432), `export_openapi.py --check` (passed), `uv build` (passed), `docker compose config` (passed).
- Hoàn tất Phase 1 hoàn toàn và sẵn sàng chuyển sang Phase 2 (P2 — Worker pipeline).

### 2026-08-03 — Session 4

- Đã khởi chạy PostgreSQL container và áp dụng Alembic migrations.
- Khai báo và seed thiết bị demo `device-100` liên kết tới `user-100` trong bảng `glasses_devices`.
- Khởi chạy Backend FastAPI Uvicorn Server thành công tại `http://localhost:8001` (`http://127.0.0.1:8001`).
- Đã kiểm thử API Client (`test_real/run_real_e2e.py` với `music_volume`): request trả `202 Accepted`, worker dispatch và report hoàn tất, trạng thái trả về `succeeded` với kết quả `PASS`. Server hiện đang hoạt động liên tục tại port `8001`.

### 2026-08-17 — Session 5

- Đã xác nhận Firebase Admin SDK JSON được bỏ qua bởi rule `*firebase-adminsdk*.json` trong `.gitignore` và không còn được theo dõi trong working tree.
- Đã rewrite lịch sử `main` để loại credential khỏi mọi commit, xóa refs/reflog backup và chạy `git gc --prune=now`.
- Kiểm tra `git rev-list --all --objects`: pass — đường dẫn credential không còn trong refs. Kiểm tra blob cũ bằng `git cat-file -e`: pass — blob đã bị prune khỏi object store.
- Không chạy test ứng dụng vì thay đổi chỉ liên quan đến lịch sử Git và nhật ký dự án; giữ nguyên toàn bộ thay đổi chưa commit của user.


## Protocol tiếp tục ở session mới

1. Ä á» c `claude.md`, pháº§n "Tráº¡ng thÃ¡i hiá»‡n táº¡i" cá»§a file nÃ y vÃ  nháº­t kÃ½ session má»›i nháº¥t.
2. Cháº¡y kiá»ƒm tra read-only tráº¡ng thÃ¡i filesystem/Git vÃ  toolchain; khÃ´ng giáº£ Ä‘á»‹nh káº¿t quáº£ session trÆ°á»›c cÃ²n Ä‘Ãºng.
3. Chá» n checklist item chÆ°a hoÃ n táº¥t Ä‘áº§u tiÃªn trong phase hiá»‡n táº¡i.
4. Viáº¿t/cáº­p nháº­t test cÃ¹ng code, cháº¡y quality gate Ã¡p dá»¥ng Ä‘Æ°á»£c.
5. Cáº­p nháº­t checklist, báº£ng evidence, blocker vÃ  nháº­t kÃ½ trÆ°á»›c khi káº¿t thÃºc session.
