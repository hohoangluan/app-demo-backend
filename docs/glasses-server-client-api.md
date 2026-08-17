# API cho Server Kính (External API Client)

Tài liệu này dành cho đội phát triển server kính (server quản lý thiết bị kính,
gọi vào App Communication Server để chuyển tiếp lệnh tới app Android của người
dùng đã đăng ký). Nội dung phản ánh đúng contract đã triển khai trong
`project_context.md` §6 và code hiện tại (`apps/backend/src/app/api/service.py`,
`status.py`, `schemas/service_requests.py`, `schemas/glasses.py`). Không có
trường nào được thêm/bớt so với contract thật.

## 0. Điều kiện tiên quyết — pairing kính

`device_id` của mỗi request phải đã được pairing với một `user_id` trước, qua
app Android của người dùng (người dùng nhập `device_id` kính vào app điện
thoại, app gọi Internal Glasses Pairing API — `project_context.md` §6.13a).
Server kính **không** tự gọi endpoint pairing đó; nó chỉ cần gửi `device_id`
đã được người dùng pairing từ trước ở mỗi request bên dưới. Server tự tra
`user_id` nội bộ từ `device_id` trước khi thực thi — server kính không cần
biết `user_id`.

`device_id` chưa từng pairing, hoặc pairing đã bị người dùng hủy, trả `404`
với `error.code = "GLASSES_DEVICE_NOT_LINKED"` (xem mục 4).

## 1. Base URL

```
https://app.visioncare-host.uk/
```

Toàn bộ endpoint bên dưới nối tiếp base URL này, ví dụ:
`https://app.visioncare-host.uk/api/v1/service/ride/quote`.

## 2. Xác thực

```http
Authorization: Bearer <client_token>
Content-Type: application/json
Accept: application/json
```

| Header | Bắt buộc | Ý nghĩa |
|---|---|---|
| `Authorization` | Có | Bearer token cấp cho server kính (client token) |
| `Content-Type` | Có với POST | Luôn là `application/json` |
| `Accept` | Không | Mặc định `application/json` |

Token sai hoặc thiếu quyền (scope) trả lỗi xác thực chuẩn HTTP (`401`/`403`)
theo response envelope lỗi ở mục 4.

## 3. Trường bắt buộc trong mọi request body

| Trường | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `device_id` | Có | string | ID phần cứng của kính, đã pairing với một `user_id` (mục 0). Server dùng trường này để chuyển tiếp đúng tới thiết bị Android đã đăng ký của người dùng đó. |
| `request_id` | Có | UUID string | ID duy nhất do server kính sinh ra cho mỗi lệnh. Gửi lại cùng `request_id` với cùng nội dung sẽ không tạo lần thực thi mới (idempotent); gửi lại cùng `request_id` nhưng nội dung khác trả `409`. |

Mọi trường khác `extra="forbid"` — gửi thêm trường lạ sẽ bị từ chối với lỗi
`INVALID_REQUEST`.

## 4. Response khi tiếp nhận — `HTTP 202 Accepted`

```json
{
  "status": "ok",
  "data": {
    "request_id": "550e8400-e29b-41d4-a716-446655440000",
    "operation": "ride_quote",
    "request_state": "processing",
    "status_url": "/api/v1/requests/550e8400-e29b-41d4-a716-446655440000",
    "accepted_at": "2026-08-02T10:00:00Z"
  }
}
```

| Trường | Kiểu | Ý nghĩa |
|---|---|---|
| `data.request_id` | UUID string | Dùng để tra trạng thái và đối chiếu callback |
| `data.operation` | string | Tên action tương ứng endpoint đã gọi (bảng ở mục 6) |
| `data.request_state` | string | Luôn là `processing` ở bước tiếp nhận |
| `data.status_url` | string | Path để `GET` trạng thái cuối (mục 7) |
| `data.accepted_at` | datetime | ISO 8601 UTC |

Response lỗi tức thời (request bị từ chối trước khi thực thi):

```json
{
  "status": "error",
  "error": {
    "code": "INVALID_REQUEST",
    "message": "destination is required",
    "details": {}
  }
}
```

`device_id` chưa pairing (mục 0) trả `HTTP 404`:

```json
{
  "status": "error",
  "error": {
    "code": "GLASSES_DEVICE_NOT_LINKED",
    "message": "No active glasses pairing found for this device_id",
    "details": { "device_id": "glasses-123" }
  }
}
```

## 5. Danh sách endpoint

| Endpoint | `operation` | Input chính |
|---|---|---|
| `POST /api/v1/service/ride/quote` | `ride_quote` | Vị trí hiện tại, điểm đến |
| `POST /api/v1/service/ride/confirm` | `ride_confirm` | `quote_id`, `confirm` |
| `POST /api/v1/service/music/play` | `music_play` | Tên bài hát, âm lượng tùy chọn |
| `POST /api/v1/service/music/stop` | `music_stop` | Không có input ngoài trường chung |
| `POST /api/v1/service/music/volume` | `music_volume` | Hướng hoặc mức âm lượng |
| `POST /api/v1/service/navigation/start` | `navigation_start` | Điểm đến |
| `POST /api/v1/service/navigation/stop` | `navigation_stop` | `navigation_id` |
| `POST /api/v1/service/emergency/call` | `emergency_call` | Không có input ngoài trường chung |
| `POST /api/v1/service/contact/call` | `contact_call` | Tên liên hệ |
| `GET /api/v1/requests/{request_id}` | — | Tra trạng thái/kết quả cuối |

## 6. Chi tiết từng endpoint

### 6.1. `POST /api/v1/service/ride/quote` — Lấy báo giá chuyến đi

Request:

```json
{
  "device_id": "glasses-123",
  "request_id": "550e8400-e29b-41d4-a716-446655440000",
  "current_location": { "lat": 10.7769, "lng": 106.7009 },
  "destination": {
    "address": "Đại học Bách Khoa TP.HCM",
    "lat": 10.7721,
    "lng": 106.6578
  }
}
```

| Trường | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `current_location.lat` | Có | number | Vĩ độ hiện tại, `-90..90` |
| `current_location.lng` | Có | number | Kinh độ hiện tại, `-180..180` |
| `destination.address` | Có điều kiện | string | Bắt buộc khi không có `lat/lng` |
| `destination.lat`/`destination.lng` | Có điều kiện | number | Phải đi cùng nhau |

Kết quả cuối (`data.result` qua status API/callback):

```json
{
  "quote_id": "quote-123",
  "product_type": "standard",
  "price_estimate": { "currency": "VND", "amount": 85000 },
  "eta_minutes": 6,
  "expires_at": "2026-08-02T10:05:00Z"
}
```

Mã lỗi: `INVALID_LOCATION`, `INVALID_DESTINATION`, `RIDE_ACCOUNT_NOT_CONNECTED`, `NO_RIDE_AVAILABLE`, `RIDE_PROVIDER_ERROR`.

### 6.2. `POST /api/v1/service/ride/confirm` — Xác nhận hoặc hủy đặt xe

Request:

```json
{
  "device_id": "glasses-123",
  "request_id": "550e8400-e29b-41d4-a716-446655440001",
  "quote_id": "quote-123",
  "confirm": true
}
```

| Trường | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `quote_id` | Có | string | ID nhận từ kết quả `ride/quote` |
| `confirm` | Có | boolean | `true`: đặt xe; `false`: hủy quy trình đặt xe |

Kết quả cuối khi `confirm = true`: `{"quote_id", "ride_id", "ride_status": "requested"}`.
Khi `confirm = false`: `{"quote_id", "ride_id": null, "ride_status": "cancelled"}`.

Mã lỗi: `QUOTE_NOT_FOUND`, `QUOTE_EXPIRED`, `QUOTE_ALREADY_USED`, `RIDE_CONFIRM_FAILED`.

### 6.3. `POST /api/v1/service/music/play` — Phát nhạc

Request:

```json
{
  "device_id": "glasses-123",
  "request_id": "550e8400-e29b-41d4-a716-446655440002",
  "song": "Nơi này có anh - Sơn Tùng M-TP",
  "volume": 60
}
```

| Trường | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `song` | Có | string | Chuỗi tìm kiếm: tên bài hát, nghệ sĩ hoặc cả hai |
| `volume` | Không | integer | Mức âm lượng ban đầu, `0..100` |

Kết quả cuối: `{"track_id", "title", "artist", "playback_state": "playing", "volume"}`.

Mã lỗi: `SONG_NOT_FOUND`, `MUSIC_ACCOUNT_NOT_CONNECTED`, `SUBSCRIPTION_INACTIVE`, `PLAYBACK_FAILED`.

### 6.4. `POST /api/v1/service/music/stop` — Dừng nhạc

Request: chỉ gồm `device_id`, `request_id`.

Kết quả cuối: `{"playback_state": "stopped"}`.

Mã lỗi: `NO_ACTIVE_PLAYBACK`, `PLAYBACK_STOP_FAILED`.

### 6.5. `POST /api/v1/service/music/volume` — Thay đổi âm lượng

Request theo hướng:

```json
{ "device_id": "glasses-123", "request_id": "...", "direction": "up" }
```

Request theo mức tuyệt đối:

```json
{ "device_id": "glasses-123", "request_id": "...", "level": 70 }
```

| Trường | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `direction` | Có điều kiện | enum | `up` hoặc `down`; không gửi cùng `level` |
| `level` | Có điều kiện | integer | `0..100`; không gửi cùng `direction` |

Đúng một trong hai (`direction` xor `level`) bắt buộc.

Kết quả cuối: `{"volume_state": "changed", "level"}`.

Mã lỗi: `INVALID_VOLUME`, `VOLUME_CHANGE_FAILED`.

### 6.6. `POST /api/v1/service/navigation/start` — Bắt đầu điều hướng đi bộ

Request theo địa chỉ hoặc tọa độ:

```json
{
  "device_id": "glasses-123",
  "request_id": "...",
  "destination": { "address": "Bưu điện Thành phố Hồ Chí Minh" }
}
```

```json
{
  "device_id": "glasses-123",
  "request_id": "...",
  "destination": { "lat": 10.7798, "lng": 106.6990 }
}
```

Chế độ điều hướng cố định là `walking`.

Kết quả cuối: `{"navigation_id", "navigation_state": "navigating", "travel_mode": "walking", "destination"}`.

Mã lỗi: `CURRENT_LOCATION_UNAVAILABLE`, `INVALID_DESTINATION`, `LOCATION_PERMISSION_DENIED`, `NAVIGATION_PROVIDER_ERROR`, `NAVIGATION_START_FAILED`.

### 6.7. `POST /api/v1/service/navigation/stop` — Dừng điều hướng

Request:

```json
{ "device_id": "glasses-123", "request_id": "...", "navigation_id": "nav-123" }
```

Kết quả cuối: `{"navigation_id", "navigation_state": "stopped"}`.

Mã lỗi: `NAVIGATION_NOT_FOUND`, `NAVIGATION_ALREADY_STOPPED`, `NAVIGATION_STOP_FAILED`.

### 6.8. `POST /api/v1/service/emergency/call` — Gọi khẩn cấp

Request: chỉ gồm `device_id`, `request_id`. App dùng số khẩn cấp đã cấu hình sẵn
trên điện thoại, gửi vị trí qua SMS và thực hiện chu kỳ gọi.

Kết quả cuối:

```json
{
  "emergency_state": "completed",
  "attempt": 1,
  "answered": true,
  "cycle": "stopped",
  "contact": "***789",
  "sms_sent": true
}
```

`cycle` ∈ `calling_5min`, `paused_10min`, `stopped`.

Mã lỗi: `EMERGENCY_CONTACT_NOT_CONFIGURED`, `CURRENT_LOCATION_UNAVAILABLE`, `CALL_PERMISSION_DENIED`, `SMS_PERMISSION_DENIED`, `CALL_FAILED`, `SMS_FAILED`.

### 6.9. `POST /api/v1/service/contact/call` — Gọi người trong danh bạ

Request:

```json
{ "device_id": "glasses-123", "request_id": "...", "name": "Nguyễn Văn A" }
```

| Trường | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `name` | Có | string | Tên liên hệ cần tìm; không được rỗng |

Kết quả cuối: `{"call_state": "calling", "contact_name", "phone_number": "***789"}`.

Khi trùng nhiều liên hệ, request chuyển `failed` với:

```json
{
  "code": "MULTIPLE_CONTACTS_FOUND",
  "message": "Multiple contacts matched",
  "details": {
    "candidates": [
      { "name": "Nguyễn Văn A", "phone_number": "***789" },
      { "name": "Nguyễn Văn A Công ty", "phone_number": "***456" }
    ]
  }
}
```

Mã lỗi: `CONTACT_NOT_FOUND`, `MULTIPLE_CONTACTS_FOUND`, `CONTACT_PERMISSION_DENIED`, `CALL_PERMISSION_DENIED`, `CALL_FAILED`.

## 7. Tra trạng thái — `GET /api/v1/requests/{request_id}`

```http
GET /api/v1/requests/{request_id}
Authorization: Bearer <client_token>
```

Response `HTTP 200`:

```json
{
  "status": "ok",
  "data": {
    "request_id": "550e8400-e29b-41d4-a716-446655440000",
    "operation": "navigation_start",
    "request_state": "processing | succeeded | failed | timed_out",
    "result": {},
    "error": null,
    "created_at": "2026-08-02T10:00:00Z",
    "updated_at": "2026-08-02T10:00:03Z"
  }
}
```

| Trường | Kiểu | Ý nghĩa |
|---|---|---|
| `data.request_state` | enum | `processing`, `succeeded`, `failed`, `timed_out` |
| `data.result` | object/null | `null` khi còn `processing` |
| `data.error` | object/null | Chỉ có giá trị khi `failed`/`timed_out`: `{code, message, details}` |

Request không tồn tại hoặc không thuộc về client gọi trả `404` với
`error.code = "REQUEST_NOT_FOUND"` — cùng một lỗi cho cả hai trường hợp
(không lộ thông tin request của client khác).

## 8. Callback (tùy chọn, thay cho polling)

Nếu server kính đăng ký `callback_url` trong cấu hình client, App
Communication Server sẽ `POST` kết quả cuối (khi `succeeded`, `failed` hoặc
`timed_out`) thay vì bắt server kính phải polling mục 7.

```http
POST {client_callback_url}
Authorization: Bearer <callback_token>
Content-Type: application/json
```

Body: cùng cấu trúc `data` như response ở mục 7. Server kính phải trả
`HTTP 200` — App Communication Server sẽ retry callback khi timeout hoặc nhận
`5xx`.

## 9. Giới hạn phạm vi

- Public API không trả push token, action nội bộ hay trạng thái giao nhận
  thiết bị.
- Không có endpoint nào của Internal Device API (`/api/v1/device/register`,
  `/api/v1/device/report`, `/api/v1/device/link`, `/api/v1/device/glasses/link`,
  `/api/v1/device/glasses/unlink`) được công bố cho server kính — các endpoint
  đó chỉ dành cho app Android.
