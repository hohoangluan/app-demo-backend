# Thiết kế: Pairing kính (glasses) + đổi Public Service API sang `device_id`

Ngày: 2026-08-03
Trạng thái: Đã duyệt thiết kế (chờ viết plan thực thi)

## 1. Bối cảnh và vấn đề

Kính (thiết bị đeo) do một server khác quản lý ("sever kính" / External API Client).
Server kính gọi Public Service API của App Communication Server để chuyển lệnh
(gọi điện, phát nhạc, điều hướng...) tới app Android của người dùng đã đăng ký.

Server kính chỉ biết `device_id` của kính (ID phần cứng cố định), không biết
`user_id` nội bộ của App Communication Server. Contract Public API hiện tại
(`project_context.md` §6.1, `app/schemas/service_requests.py`) yêu cầu
`user_id` trong mọi request body — trường này không tồn tại phía server kính.

Cần: (1) một bước pairing để người dùng — qua app Android — khai báo
"`device_id` kính này là của tôi", và (2) đổi Public Service API để nhận
`device_id` thay vì `user_id`, server tự resolve `device_id` → `user_id` nội
bộ trước khi xử lý.

`device_id` của kính là một không gian định danh **hoàn toàn khác** với
`devices.device_id` hiện có (ID cài đặt app Android, gắn với push token FCM).
Không tái dùng bảng `devices` — cần bảng mới.

## 2. Phạm vi

### Trong phạm vi

- Bảng mới `glasses_devices` + migration Alembic.
- `GlassesDeviceRepository`: link/unlink/resolve, PostgreSQL thật (không SQLite).
- 2 endpoint Internal Device API: `POST /api/v1/device/glasses/link`,
  `POST /api/v1/device/glasses/unlink`. Auth: `require_device_bearer_token`
  (cùng shared secret với `/register`, `/report`) — **không** dùng user
  session, vì app Android demo hiện chưa có flow login/OTP nào (chỉ có ô nhập
  `user_id` + Device bearer token thủ công trên Overview screen).
- Đổi `ServiceRequest.user_id` → `ServiceRequest.device_id` cho toàn bộ 9
  action endpoint Public Service API. Đây là **breaking change có chủ đích**
  (đã xác nhận với người dùng) vì contract này chưa có client thật nào dùng.
- Resolve `device_id` → `user_id` nội bộ trong `service.py` trước khi gọi
  `OperationService.accept`; lỗi khi chưa pairing: `404 GLASSES_DEVICE_NOT_LINKED`.
- Cập nhật đồng bộ: `project_context.md`, `contracts/public-api.openapi.yaml`,
  `contracts/device-api.openapi.yaml`, `docs/glasses-server-client-api.md`,
  contract test (`test_openapi_contracts.py`), toàn bộ test hiện có đang gửi
  `user_id` trong body Public Service API.
- Màn hình Android mới để nhập `device_id` kính, gọi endpoint link, tái dùng
  `user_id`/Device bearer token đã lưu trong `SharedPreferences`
  (`FcmPushReceiver.KEY_USER_ID`, `KEY_BEARER_TOKEN`).

### Ngoài phạm vi

- Login/OTP thật trên Android (không xây dựng ở đây).
- Giao tiếp/kiến trúc nội bộ của server kính (đã ngoài phạm vi theo
  `project_context.md` §4.2).
- Đa kính cho 1 user (chỉ 1 kính active/user, theo quyết định thiết kế).
- Revoke/audit log pairing nâng cao (chỉ active/inactive, không có trạng thái
  `revoked` riêng như bảng `devices`).

## 3. Data model

Bảng `glasses_devices` (mirror phong cách bảng `devices`):

| Cột | Kiểu | Ghi chú |
|---|---|---|
| `id` | UUID PK | `default=uuid4` |
| `device_id` | string | Unique constraint `uq_glasses_devices_device_id` |
| `user_id` | string | `public_user_id`, cùng không gian định danh với `devices.user_id` |
| `status` | enum (`active`, `inactive`) | `CheckConstraint` như `ck_devices_status` |
| `linked_at` | timestamptz | Cập nhật mỗi lần link/relink thành công |
| `created_at` | timestamptz | |
| `updated_at` | timestamptz | |

Index: `ix_glasses_devices_user_id` (tìm pairing active của 1 user để
deactivate khi relink kính khác), unique trên `device_id`.

## 4. Backend API mới (Internal Device API)

### `POST /api/v1/device/glasses/link`

Auth: `require_device_bearer_token`. Request: `{user_id, device_id}`.

Logic (`GlassesDeviceRepository.link`, lock row `device_id` bằng
`with_for_update`):

1. Tồn tại row cùng `device_id`, cùng `user_id` → reactivate (idempotent),
   cập nhật `linked_at`, `status=active`. Trả `linked: true`.
2. Tồn tại row cùng `device_id`, khác `user_id`, `status=active` → raise
   `GlassesDeviceOwnerConflictError` (`409 GLASSES_DEVICE_OWNER_CONFLICT`).
3. Tồn tại row cùng `device_id`, khác `user_id`, `status=inactive` → chuyển
   quyền sở hữu (chủ cũ đã unlink): update `user_id`, `status=active`.
4. Không tồn tại row → deactivate mọi row `active` khác của `user_id` này
   (1 kính active/user), insert row mới `status=active`.

Response: `OkResponse[GlassesLinkData]` = `{device_id, linked: true}`.

### `POST /api/v1/device/glasses/unlink`

Auth: `require_device_bearer_token`. Request: `{user_id}`.

Deactivate mọi row `active` của `user_id`. Không lỗi nếu không có gì để
unlink (idempotent). Response: `OkResponse[GlassesUnlinkData]` =
`{unlinked: bool}` (`true` nếu có row bị đổi trạng thái, `false` nếu không có
pairing active nào từ trước).

## 5. Đổi Public Service API

- `app/schemas/service_requests.py`: `ServiceRequest.user_id` →
  `ServiceRequest.device_id: TrimmedNonEmptyStr`. Comment giải thích đây là
  `device_id` của kính, không phải `devices.device_id` của Android.
- `app/services/operation.py`:
  - `OperationService.accept(self, *, client_id, user_id, route, request)` —
    thêm tham số `user_id` tường minh (đã resolve), không còn đọc
    `request.user_id`.
  - `_extract_business_params` pop `"device_id"` thay vì `"user_id"`.
  - `compute_request_fingerprint` không đổi logic (vẫn hash toàn bộ
    `request.model_dump()`, giờ chứa `device_id` thay vì `user_id`) — chỉ
    sửa docstring.
- `app/api/service.py` (`_accept_operation`): trước khi gọi
  `service.accept(...)`, gọi
  `resolve_glasses_device_owner(GlassesDeviceRepository(session), device_id=body.device_id)`
  → trả `user_id` nội bộ hoặc raise `GlassesDeviceNotLinkedError`
  (`404 GLASSES_DEVICE_NOT_LINKED`).
- `operations.user_id` (cột DB) tiếp tục lưu `user_id` nội bộ đã resolve —
  **không đổi schema `operations`**, không cần migration cho bảng này.

## 6. Lỗi mới (`app/errors.py`)

| Exception | HTTP | code |
|---|---|---|
| `GlassesDeviceOwnerConflictError` | 409 | `GLASSES_DEVICE_OWNER_CONFLICT` |
| `GlassesDeviceNotLinkedError` | 404 | `GLASSES_DEVICE_NOT_LINKED` |

## 7. Đồng bộ tài liệu/contract

- `project_context.md` §6.1: đổi bảng "trường chung" từ `user_id` sang
  `device_id`, cập nhật mọi ví dụ JSON của §6.4–6.12.
- Thêm mục mới mô tả `POST /api/v1/device/glasses/link` và `/unlink` vào
  §6.13 (Internal Device API).
- `docs/glasses-server-client-api.md`: bỏ khối cảnh báo "chưa implement",
  đổi field mẫu từ `user_id` sang `device_id`, thêm mã lỗi
  `GLASSES_DEVICE_NOT_LINKED`.
- Export lại `contracts/public-api.openapi.yaml` và
  `contracts/device-api.openapi.yaml`, xác nhận diff đúng chủ đích trong
  `test_openapi_contracts.py`.

## 8. Android

- Model mới trong `Models.kt`: `GlassesLinkPayload(userId, deviceId)` +
  response tương ứng.
- `DeviceApiClient.linkGlassesDevice(baseUrl, bearerToken, payload)` — theo
  đúng pattern `registerDevice`/`sendReport` hiện có (raw `HttpURLConnection`,
  `Authorization: Bearer $bearerToken`).
- Màn hình mới (route riêng từ `MainActivity`, tối giản): một ô nhập
  `device_id` kính + nút "Pair". Đọc `userId`/`bearerToken` có sẵn từ
  `SharedPreferences` (`FcmPushReceiver.KEY_USER_ID`, `KEY_BEARER_TOKEN`) thay
  vì bắt nhập lại.
- `GlassesLinkViewModel` (StateFlow UI state, gọi `DeviceApiClient` trên
  `Dispatchers.IO`) + JUnit/MockK test cho các nhánh: thành công, 409 conflict,
  lỗi mạng.

## 9. Test bắt buộc

- **Unit**: `compute_request_fingerprint`/`_extract_business_params` với
  `device_id`; mapping lỗi mới trong `errors.py`.
- **PostgreSQL integration**: `GlassesDeviceRepository` — link mới, relink
  cùng chủ (idempotent), conflict khác chủ khi active, chuyển chủ khi
  inactive, unlink, race giữa 2 request link đồng thời cùng `device_id`
  (dùng `with_for_update`).
- **API (HTTPX)**: `/api/v1/device/glasses/link` và `/unlink` — mọi nhánh ở
  mục 4, thiếu/sai Device bearer token → 401.
- **API (HTTPX)**: 9 action endpoint Public Service API với `device_id` hợp
  lệ (202 như cũ) và `device_id` chưa pairing (`404 GLASSES_DEVICE_NOT_LINKED`).
- **Contract test**: `test_openapi_contracts.py` cập nhật theo schema mới,
  không có diff ngoài chủ đích.
- **Migration**: chạy trên DB sạch; kiểm tra constraint/index đúng như mục 3.
- **Cập nhật test hiện có**: mọi test đang gửi `user_id` trong body Public
  Service API (`ride/quote`, `music/play`, ... ) phải đổi sang `device_id` +
  seed một `glasses_devices` row pairing trước khi gọi, nếu không sẽ luôn
  nhận `404 GLASSES_DEVICE_NOT_LINKED`.
- Android: JUnit/MockK cho `GlassesLinkViewModel` theo mục 8.

## 10. Rủi ro / đánh đổi đã chấp nhận

- Breaking change trên Public Service API (`user_id` → `device_id`) — chấp
  nhận vì chưa có client thật tích hợp.
- Auth endpoint link/unlink dùng Device bearer token dùng chung (shared
  secret), không phải session riêng từng người dùng — chấp nhận vì app demo
  Android chưa có login thật; rủi ro: bất kỳ ai có Device bearer token có thể
  link/unlink kính cho bất kỳ `user_id` nào họ tự gõ vào (không xác thực
  danh tính người dùng thật). Chấp nhận được cho phạm vi demo hiện tại.
