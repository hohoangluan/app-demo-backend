# App Communication Server

FastAPI server nhận lệnh từ External API Client (server kính), đẩy lệnh tới điện thoại qua FCM,
nhận kết quả và công bố qua status API / callback. Luồng chi tiết: [`../docs/architecture.md`](../docs/architecture.md).

## Cấu trúc

```text
src/app/
  actions.py        13 endpoint -> action -> timeout
  api/              router HTTP (service, status, device, glasses, auth, preferences, support, health)
  services/         nghiệp vụ: operation, device, glasses, auth, device_events
  repositories/     truy vấn PostgreSQL, khóa, cập nhật có điều kiện
  adapters/         FCM/fake delivery, callback, device events, Spotify catalog
  workers/          delivery, timeout, callback + runner
  contract_api/     app riêng để sinh OpenAPI vào ../contracts/
alembic/            migration (không bao giờ tạo schema lúc startup)
scripts/            export_openapi.py, real_client_e2e.py, test-postgres.ps1
tests/              unit, API (HTTPX), integration (PostgreSQL thật)
```

## Cấu hình

Copy `.env.example` thành `.env` (không commit). Biến chính:

| Biến | Ý nghĩa |
|---|---|
| `DATABASE_URL` | `postgresql+asyncpg://...` |
| `PUBLIC_API_TOKEN_HASH`, `PUBLIC_API_CLIENT_ID`, `PUBLIC_API_SCOPES` | Token của server kính (lưu hash, không lưu token thô) |
| `DEVICE_API_TOKEN_HASH` | Token chung của app điện thoại |
| `DELIVERY_TRANSPORT` | `fake` (dev) hoặc `fcm` (cần `FCM_PROJECT_ID`, `GOOGLE_APPLICATION_CREDENTIALS`) |
| `CALLBACK_URL`, `CALLBACK_TOKEN`, `CALLBACK_ALLOWED_HOSTS` | Bật callback; phải đặt đủ cả ba. Sự kiện cuộc gọi gửi tới `<origin>/internal/device-events` |
| `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET` | Tùy chọn: tra `spotify:track:` cho `music_play` |

Tạo hash cho một token thô:

```powershell
uv run python -c "from app.auth import token_digest, PUBLIC_API_DOMAIN as D; print(token_digest('<raw-token>', D).hex())"
```

(dùng `DEVICE_API_DOMAIN` cho token điện thoại).

## Chạy

```powershell
Set-Location server
uv sync --frozen
uv run alembic upgrade head
uv run uvicorn app.main:app --app-dir src --host 0.0.0.0 --port 8000
```

Hoặc bằng Docker (PostgreSQL + migrate + server):

```powershell
docker compose -f compose.yaml up --build
```

`GET /health/live` = process sống; `GET /health/ready` = DB và worker đã sẵn sàng.

## Endpoint

| Nhóm | Endpoint | Xác thực |
|---|---|---|
| Public | `POST /api/v1/service/{ride/quote, ride/confirm, music/play, music/stop, music/volume, navigation/start, navigation/stop, emergency/call, contact/call, location/get, capabilities, call/answer, call/reject}` | Public token, scope `service:execute` |
| Public | `GET /api/v1/requests/{request_id}` | Public token, scope `requests:read` |
| Điện thoại | `POST /api/v1/device/{register, report, event}`, `POST /api/v1/device/glasses/{link, unlink}` | Device token |
| Điện thoại | `POST /api/v1/device/link`, `/api/v1/auth/*`, `/api/v1/preferences`, `/api/v1/support/tickets` | Session token của tài khoản app |

Input/output từng endpoint: [`../docs/external-client-api.md`](../docs/external-client-api.md),
OpenAPI: [`../contracts/`](../contracts/).

## Kiểm thử

```powershell
uv run ruff format --check .
uv run ruff check .
uv run mypy
.\scripts\test-postgres.ps1 start          # PostgreSQL tạm (tmpfs) ở 127.0.0.1:55432
$env:TEST_DATABASE_URL = "postgresql+asyncpg://app_demo_test:app_demo_test@127.0.0.1:55432/app_demo_test"
uv run pytest
uv run python scripts/export_openapi.py --check
uv build
.\scripts\test-postgres.ps1 stop
```

Integration test tự migrate database (tên phải chứa `test`). Test không đọc `.env` của máy.
`tests/integration/test_public_flow.py` chạy trọn luồng accept → deliver → report → status, cùng các
trường hợp trùng request, xung đột 409, report sai thiết bị và timeout.

### E2E với điện thoại thật / emulator

1. Chạy server với `DELIVERY_TRANSPORT=fcm` và đăng ký điện thoại trong app (Hồ sơ → chạm "phiên bản"
   7 lần → "Lưu Cấu Hình & Kết Nối Server"). Emulator dùng `http://10.0.2.2:8000` (bản debug cho phép HTTP).
2. Ghép kính: `POST /api/v1/device/glasses/link {"user_id": "...", "device_id": "glasses-..."}`.
3. Copy `scripts/real_client.example.json` thành `scripts/real_client.local.json` (gitignored), điền
   token public thô và `device_id` kính, rồi:

```powershell
uv run python scripts/real_client_e2e.py                          # mọi action trừ emergency_call
uv run python scripts/real_client_e2e.py --only emergency_call    # gọi + SMS thật tới người thân
```
