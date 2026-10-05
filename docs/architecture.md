# Kiến trúc và luồng xử lý

Repo gồm hai ứng dụng độc lập, nói chuyện với nhau chỉ qua HTTP và FCM:

| Thư mục | Ứng dụng | Công nghệ |
|---|---|---|
| `server/` | App Communication Server | Python 3.13, FastAPI, SQLAlchemy async, PostgreSQL, Alembic, Firebase Admin |
| `mobile/` | Ứng dụng điện thoại "Your Eyes" | Kotlin, Jetpack Compose, FCM, SQLite |
| `contracts/` | OpenAPI sinh từ code server | YAML, kiểm tra bằng `scripts/export_openapi.py --check` |

Hợp đồng nghiệp vụ (endpoint, input, output, mã lỗi) nằm ở [`project-context.md`](project-context.md).
Hướng dẫn cho bên gọi API (server kính) nằm ở [`external-client-api.md`](external-client-api.md).

## 1. Toàn cảnh

```text
External API Client (server kính)
   │  POST /api/v1/service/<chức năng>     Bearer public token
   │  GET  /api/v1/requests/{request_id}
   ▼
server/ ── PostgreSQL (operations, devices, glasses_devices, users, ...)
   │  FCM data message {request_id, user_id, device_id, action, params_json}
   ▼
mobile/ ── chạy handler trên điện thoại (gọi điện, nhạc, bản đồ, Grab, vị trí...)
   │  POST /api/v1/device/report            Bearer device token
   ▼
server/ ── cập nhật trạng thái cuối ── POST callback tới server kính (nếu cấu hình)
```

## 2. Luồng một request

1. **Nhận** – `api/service.py` xác thực token public (scope `service:execute`), validate body
   (lỗi → `400 INVALID_REQUEST`), gọi `OperationService.submit()`.
2. **Chấp nhận** – `services/operation.py`:
   - tra `device_id` (kính) → `user_id` qua `glasses_devices` (không có → `404 GLASSES_DEVICE_NOT_LINKED`);
   - tính fingerprint SHA-256 của body gốc + client + path;
   - `music_play`: tra Spotify để thêm `spotify_uri` vào params (không ảnh hưởng fingerprint);
   - `INSERT ... ON CONFLICT DO NOTHING`: request mới → lưu `processing`; gửi lại y hệt → trả operation cũ;
     cùng `request_id` khác body → `409 REQUEST_ID_CONFLICT`;
   - commit, đánh thức delivery worker, trả `202` kèm `status_url`.
3. **Giao lệnh** – `workers/delivery.py` claim operation đến hạn bằng `FOR UPDATE SKIP LOCKED`
   + lease, chọn điện thoại active mới nhất của user, gửi FCM qua `adapters/delivery.py`.
   Lỗi tạm thời → retry 5s/20s; lỗi vĩnh viễn (token chết) → `failed / DELIVERY_FAILED`.
4. **Thực thi trên điện thoại** – `FcmPushReceiver` → `CommandExecutionService` (foreground) →
   `ReportFlusher` gửi lại report còn treo → `CommandDispatcher`:
   - ghi `request_id` vào SQLite; trùng → bỏ qua, không chạy lại handler;
   - `ActionRegistry` chọn handler; action lạ → `UNSUPPORTED_ACTION`;
   - handler chờ điện thoại xác nhận kết quả thật (cuộc gọi off-hook, nhạc thực sự phát...);
   - lưu report vào hàng đợi rồi gửi `POST /api/v1/device/report`.
5. **Nhận report** – `services/device.py` kiểm tra đúng user, đúng điện thoại đã nhận lệnh, đúng action
   (sai → `404`), khóa dòng operation và chuyển `succeeded`/`failed`. Report gửi lại y hệt → `200`
   không đổi gì; report khác sau khi đã kết thúc → `409`.
6. **Hết hạn** – `workers/timeout.py` chuyển operation `processing` quá `expires_at` sang
   `timed_out / REPORT_TIMEOUT`. Khóa dòng bảo đảm report và timeout chỉ một bên thắng.
7. **Công bố** – client đọc `GET /api/v1/requests/{id}`; nếu có `CALLBACK_URL`, `workers/callback.py`
   POST kết quả (kèm `Idempotency-Key`), retry 10s/60s/5m/30m rồi dead-letter. Callback không bao giờ
   đổi trạng thái operation.

Sự kiện tự phát từ điện thoại (cuộc gọi đến) đi đường riêng: `telephony/IncomingCallMonitor` →
`POST /api/v1/device/event` → `services/device_events.py` lọc thông tin người gọi theo cài đặt riêng tư
→ chuyển tới `<origin của CALLBACK_URL>/internal/device-events`.

## 3. Server (`server/src/app`)

```text
api/ ──► services/ ──► repositories/ ──► PostgreSQL
            │
            └──► adapters/ ──► FCM, callback HTTP, Spotify Web API
workers/ ──► repositories/, adapters/
```

| Module | Trách nhiệm |
|---|---|
| `actions.py` | Bảng cố định endpoint → operation/action → timeout (13 action) |
| `api/` | HTTP: xác thực, validate, envelope. Không truy vấn DB trực tiếp |
| `services/operation.py` | Chấp nhận request: kính → user, fingerprint, idempotency |
| `services/device.py` | Đăng ký điện thoại, nhận report |
| `services/glasses.py`, `auth.py`, `device_events.py` | Ghép kính, tài khoản app, sự kiện cuộc gọi |
| `repositories/` | Truy vấn, khóa, cập nhật có điều kiện. Không commit |
| `adapters/` | FCM / fake, callback (chặn SSRF), chuyển sự kiện, Spotify |
| `workers/` | delivery, timeout, callback; `runner.py` quản lý vòng đời |
| `contract_api/` | App FastAPI chỉ để sinh OpenAPI trong `contracts/` |

## 4. Điện thoại (`mobile/app/src/main/java/com/youreyes/app`)

| Package | Trách nhiệm |
|---|---|
| `command/` | Pipeline lệnh: `FcmPushReceiver` → `CommandExecutionService` → `CommandDispatcher` → `CommandStore` (SQLite) → `ReportFlusher` |
| `actions/` | Handler theo nhóm: `CallActions`, `MusicActions`, `NavigationActions`, `RideActions`, `StatusActions`; `ActionRegistry` ánh xạ action → handler |
| `network/` | `DeviceApiClient` (API server), `GlassesServerClient`, `GlassesBleProvisioner` |
| `core/` | `AppConfig` (SharedPreferences), `Timestamps` |
| `launch/` | Mở màn hình từ nền qua full-screen notification |
| `media/`, `service/` | Spotify App Remote; listener cấp quyền điều khiển media session |
| `telephony/` | Theo dõi cuộc gọi đến, gửi sự kiện |
| `ui/` | Màn hình Compose (một số là UI mock có `DemoBanner`: dịch, biên bản họp) |

Handler báo thành công chỉ khi điện thoại xác nhận: `contact_call` chờ máy off-hook, `music_play`
chờ âm thanh phát đúng bài ≥ 4 giây, `navigation_start`/`ride_quote` lỗi rõ ràng khi không có app
bản đồ/Grab để mở.

## 5. Trạng thái

| Trường | Giá trị |
|---|---|
| `request_state` (public) | `processing` → `succeeded` \| `failed` \| `timed_out` |
| `delivery_state` | `received` → `sending` → `sent` \| `retry` \| `failed` → `report_received` \| `report_timeout` |
| `callback_state` | `not_required` \| `pending` → `sending` → `delivered` \| `retry` \| `dead_letter` |
| Report trên điện thoại | `PENDING` → `SENT` \| `FAILED` (retry tới 8 lần) → `ABANDONED` |

## 6. Bảo đảm

- Giao lệnh **at-least-once**, thực thi **một lần** trên điện thoại (khóa theo `request_id`).
- Lease có fencing: worker cũ hết lease không ghi đè kết quả worker mới.
- Restart server: operation đến hạn hoặc lease hết hạn được claim lại; restart điện thoại: report treo
  được gửi lại ở lệnh kế tiếp, không chạy lại handler.
- Log không chứa token, số điện thoại đầy đủ; số điện thoại trong kết quả được che (`***768`).
