# BỐI CẢNH VÀ KIẾN TRÚC SERVER GIAO TIẾP ỨNG DỤNG DI ĐỘNG

## 1. Mục đích

Tài liệu mô tả App Communication Server cung cấp Public API cho API client bên ngoài và bảo đảm yêu cầu được thực thi trên ứng dụng Android.

API client chỉ chọn endpoint, gửi dữ liệu đầu vào và nhận kết quả. Việc liên kết thiết bị, đánh thức ứng dụng, gửi command, chạy handler, retry và thu thập kết quả thuộc nội bộ App Communication Server.

Repo gồm App Communication Server và ứng dụng demo Android. Mọi hệ thống gọi Public API được xem là External API Client.

## 2. Bối cảnh hệ thống

```text
External API Client
    ↓ Public API
App Communication Server
    ├── Public API Facade
    └── Internal App Execution Pipeline
            ↓ internal device channel
Ứng dụng điện thoại
    ↓ thực thi chức năng
Dịch vụ hoặc capability trên thiết bị

Ứng dụng điện thoại
    ↓ internal report
App Communication Server
    ↓ Public Result API / callback
External API Client
```

### 2.1. Biên hệ thống

- Project bắt đầu tại Public API nhận request từ External API Client.
- Project kết thúc tại API lấy trạng thái hoặc callback trả kết quả cho client.
- Public contract chỉ gồm endpoint, input, `request_id`, trạng thái và output.
- Push token, cơ chế wake-up, command nội bộ và device report không thuộc Public API.
- Kiến trúc và luồng xử lý của External API Client không thuộc tài liệu.

### 2.2. App Communication Server

- Xác thực request từ External API Client.
- Kiểm tra định dạng dữ liệu theo endpoint.
- Ánh xạ endpoint cố định sang `action` tương ứng.
- Liên kết người dùng với thiết bị đã đăng ký.
- Đánh thức và gửi lệnh đến ứng dụng điện thoại.
- Theo dõi quá trình giao nhận, retry và timeout.
- Nhận report từ ứng dụng và công bố kết quả cho API client.

App Communication Server không thực hiện:

- Suy luận action từ nội dung tự do.
- Điều phối chuỗi nghiệp vụ.
- Gọi API của ride, music hoặc map provider.
- Thực thi chức năng thay cho điện thoại.

### 2.3. Ứng dụng điện thoại

- Nhận push command.
- Ánh xạ `action` đến handler.
- Truy cập capability và dữ liệu trên thiết bị.
- Gọi provider thông qua adapter tương ứng.
- Thực hiện chức năng.
- Gửi internal report về App Communication Server.

## 3. Public API được cung cấp

External API Client chỉ sử dụng các endpoint dưới đây.

| Endpoint | Input chính | Kết quả |
|---|---|---|
| `POST /api/v1/service/ride/quote` | Vị trí hiện tại, điểm đến | Báo giá và ETA |
| `POST /api/v1/service/ride/confirm` | `quote_id`, `confirm` | Trạng thái đặt hoặc hủy xe |
| `POST /api/v1/service/music/play` | Tên bài hát, âm lượng tùy chọn | Track và trạng thái phát |
| `POST /api/v1/service/music/stop` | Thông tin người dùng | Trạng thái dừng |
| `POST /api/v1/service/music/volume` | Hướng hoặc mức âm lượng | Trạng thái âm lượng |
| `POST /api/v1/service/navigation/start` | Điểm đến | `navigation_id` và trạng thái `navigating` |
| `POST /api/v1/service/navigation/stop` | `navigation_id` | Trạng thái `stopped` |
| `POST /api/v1/service/emergency/call` | Thông tin người dùng | Trạng thái gọi và gửi vị trí |
| `POST /api/v1/service/contact/call` | Tên liên hệ | Trạng thái tìm kiếm và cuộc gọi |
| `GET /api/v1/requests/{request_id}` | `request_id` | Trạng thái và kết quả cuối |

## 4. Phạm vi

### 4.1. Trong phạm vi

- Public API tiếp nhận lệnh từ External API Client.
- Xác thực và phân quyền API client.
- Validate request theo schema của endpoint.
- Ánh xạ endpoint sang action cố định.
- Đăng ký thiết bị và quản lý push token.
- Liên kết người dùng với thiết bị.
- Wake-up, gửi command và retry giao nhận với ứng dụng.
- Nhận report và công bố kết quả qua status API hoặc callback.
- Idempotency, timeout, retry giao nhận và nhật ký kỹ thuật.
- Giao diện demo và mã nguồn ứng dụng Android.

### 4.2. Ngoài phạm vi

- Kiến trúc và luồng xử lý nội bộ của External API Client.
- Kính và mọi giao tiếp liên quan đến kính.
- Business orchestration.
- Tích hợp provider trực tiếp trên App Communication Server.
- Xử lý dữ liệu danh bạ, vị trí, cuộc gọi, SMS và phát nhạc trên server.
- Hệ thống turn-by-turn riêng, TTS chỉ đường và WebSocket chỉ dẫn.

## 5. Kiến trúc tổng thể

```text
External API Client
    ↓ Public API
App Communication Server
    ├── Public API Facade
    │       ├── Client Authentication
    │       ├── Function Endpoints
    │       └── Request Status API
    └── Internal App Execution Pipeline
            ├── Endpoint-to-Action Mapper
            ├── Device Resolver
            ├── App Wake & Delivery
            ├── Operation State Store
            ├── Report Receiver
            └── Result Publisher
                    ↓ internal device channel
Ứng dụng Android
    ├── Push Receiver
    ├── Action Dispatcher
    ├── Feature Handlers
    ├── Device Capability Adapters
    ├── Provider Adapters
    ├── Report Client
    └── Demo UI
            ↓ internal report
App Communication Server
            ↓ status API / callback
External API Client
```

### 5.1. App Communication Server

| Thành phần | Phạm vi | Trách nhiệm |
|---|---|---|
| Client Authentication | Public | Xác thực và phân quyền API client |
| Function Endpoints | Public | Tiếp nhận yêu cầu chức năng qua `/api/v1/service/*` |
| Request Status API | Public | Cung cấp trạng thái và kết quả theo `request_id` |
| Endpoint-to-Action Mapper | Internal | Ánh xạ path và method thành action cố định |
| Device Resolver | Internal | Liên kết `user_id` với thiết bị đang hoạt động |
| App Wake & Delivery | Internal | Đánh thức app, gửi command và retry giao nhận |
| Operation State Store | Internal | Lưu trạng thái kỹ thuật theo `request_id` |
| Report Receiver | Internal | Nhận kết quả từ `/api/v1/device/report` |
| Result Publisher | Internal | Cập nhật Status API và gửi callback |

Server không chứa Ride Service, Music Service, Navigation Service hoặc Call Service. Các tên này chỉ được dùng làm nhóm endpoint.

### 5.2. Internal App Execution Pipeline

```text
HTTP method + endpoint
        ↓
Validate schema
        ↓
Tạo command { request_id, action, params }
        ↓
Device Resolver tìm thiết bị
        ↓
App Wake & Delivery đánh thức và gửi command
        ↓
Ứng dụng thực thi handler
        ↓
Report Receiver nhận kết quả
        ↓
Result Publisher công bố kết quả
```

Pipeline là xử lý nội bộ. External API Client không nhận push token, action nội bộ hoặc trạng thái giao nhận thiết bị.

### 5.3. Ứng dụng Android

| Thành phần | Trách nhiệm |
|---|---|
| Push Receiver | Nhận command từ App Wake & Delivery |
| Action Dispatcher | Ánh xạ `action` đến feature handler |
| Feature Handlers | Thực thi từng chức năng chuyên biệt |
| Device Capability Adapters | Truy cập vị trí, danh bạ, cuộc gọi, SMS và âm lượng |
| Provider Adapters | Kết nối dịch vụ ride, music và map |
| Report Client | Gửi internal report về server |
| Local Config | Lưu `user_id`, số khẩn cấp và cấu hình demo |
| Demo UI | Hiển thị lệnh, trạng thái và kết quả |

### 5.4. Adapter trên ứng dụng

Handler chỉ giao tiếp qua interface của adapter. Nhà cung cấp được cấu hình tại lớp triển khai.

| Adapter | Interface | Triển khai tham chiếu |
|---|---|---|
| Push Adapter | Nhận push command | Firebase Cloud Messaging |
| Ride Adapter | Báo giá, đặt và theo dõi chuyến | Uber API/SDK |
| Music Catalog Adapter | Tìm kiếm nội dung âm nhạc | Apple Music Catalog API |
| Media Playback Adapter | Phát, dừng và điều chỉnh âm lượng | MusicKit |
| Navigation Adapter | Bắt đầu, duy trì và dừng turn-by-turn realtime | Google Maps Navigation SDK |
| Location Adapter | Lấy vị trí thiết bị | Android Location API |
| Contact Adapter | Tìm kiếm danh bạ | Android ContactsContract |
| Call & Message Adapter | Gọi điện và gửi SMS | Android Telecom/SMS API |

## 6. Hợp đồng giao tiếp

### 6.1. Quy ước Public API

- Base URL: `https://{app-communication-host}`.
- Function base path: `/api/v1/service`.
- Xác thực: `Authorization: Bearer <client_token>`.
- Content type: `application/json`.
- Thời gian: ISO 8601 UTC.
- `request_id`: UUID do API client sinh, dùng cho idempotency.
- `user_id`: định danh người dùng có thiết bị Android đã đăng ký.
- API client đăng ký `callback_url` trong cấu hình client.

Các JSON example trong tài liệu là JSON hợp lệ. Phần giải thích từng trường được đặt trong bảng ngay sau example.

Headers:

```http
Authorization: Bearer <client_token>
Content-Type: application/json
Accept: application/json
```

| Header | Bắt buộc | Ý nghĩa |
|---|---|---|
| `Authorization` | Có | Bearer token xác thực API client |
| `Content-Type` | Có với POST | Kiểu dữ liệu request; luôn là `application/json` |
| `Accept` | Không | Kiểu response client chấp nhận; mặc định `application/json` |

Các trường chung trong mọi request body:

| Trường | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `user_id` | Có | string | Người dùng sở hữu điện thoại Android cần thực thi chức năng |
| `request_id` | Có | UUID string | Định danh duy nhất của request; gửi lại cùng giá trị không tạo lần thực thi mới |

Mọi function endpoint trả `HTTP 202 Accepted`:

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

| Trường response | Kiểu | Ý nghĩa |
|---|---|---|
| `status` | string | `ok` khi server đã chấp nhận request |
| `data.request_id` | UUID string | ID dùng để truy vấn kết quả và đối chiếu callback |
| `data.operation` | string | Tên chức năng tương ứng với endpoint đã gọi |
| `data.request_state` | string | `processing`: yêu cầu đang được xử lý trên điện thoại |
| `data.status_url` | string | Endpoint lấy trạng thái và kết quả cuối |
| `data.accepted_at` | datetime | Thời điểm server chấp nhận request, ISO 8601 UTC |

Giá trị `data.operation`:

| Endpoint | `data.operation` |
|---|---|
| `ride/quote` | `ride_quote` |
| `ride/confirm` | `ride_confirm` |
| `music/play` | `music_play` |
| `music/stop` | `music_stop` |
| `music/volume` | `music_volume` |
| `navigation/start` | `navigation_start` |
| `navigation/stop` | `navigation_stop` |
| `emergency/call` | `emergency_call` |
| `contact/call` | `contact_call` |

Response lỗi tức thời:

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

| Trường lỗi | Kiểu | Ý nghĩa |
|---|---|---|
| `status` | string | `error` khi request bị từ chối trước khi thực thi |
| `error.code` | string | Mã lỗi ổn định dùng cho xử lý bằng chương trình |
| `error.message` | string | Mô tả lỗi dành cho log hoặc hiển thị |
| `error.details` | object | Dữ liệu bổ sung theo từng mã lỗi |

Public API không trả push token, FCM command hoặc trạng thái giao nhận nội bộ.

### 6.2. Public Request Status API

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

| Trường response | Kiểu | Ý nghĩa |
|---|---|---|
| `status` | string | Trạng thái xử lý HTTP; `ok` khi truy vấn thành công |
| `data.request_id` | UUID string | Request đang được truy vấn |
| `data.operation` | string | Chức năng đã yêu cầu |
| `data.request_state` | enum | `processing`, `succeeded`, `failed` hoặc `timed_out` |
| `data.result` | object/null | Kết quả theo endpoint; chỉ có khi đã có dữ liệu thực thi |
| `data.error` | object/null | Lỗi thực thi gồm `code`, `message`, `details` |
| `data.created_at` | datetime | Thời điểm tiếp nhận request |
| `data.updated_at` | datetime | Thời điểm cập nhật trạng thái gần nhất |

`result` bằng `null` khi request còn `processing`. `error` chỉ có giá trị khi request là `failed` hoặc `timed_out`.

Response khi thực thi lỗi:

```json
{
  "status": "ok",
  "data": {
    "request_id": "550e8400-e29b-41d4-a716-446655440000",
    "operation": "ride_quote",
    "request_state": "failed",
    "result": null,
    "error": {
      "code": "NO_RIDE_AVAILABLE",
      "message": "No ride is available",
      "details": {}
    },
    "created_at": "2026-08-02T10:00:00Z",
    "updated_at": "2026-08-02T10:00:03Z"
  }
}
```

### 6.3. Public Result Callback

```text
POST {client_callback_url}
```

Headers:

```http
Authorization: Bearer <callback_token>
Content-Type: application/json
```

Body sử dụng trường `data` giống Request Status API. Callback được gửi khi request chuyển sang `succeeded`, `failed` hoặc `timed_out`.

| Thành phần callback | Kiểu | Ý nghĩa |
|---|---|---|
| `Authorization` | header | Callback token dùng để API client xác thực App Communication Server |
| `data` | object | Toàn bộ trạng thái và kết quả theo định dạng mục 6.2 |

API client trả:

```http
HTTP/1.1 200 OK
```

Server retry callback khi timeout hoặc nhận HTTP `5xx`.

Đối với các mục 6.4–6.12:

- Response tiếp nhận luôn dùng cấu trúc `HTTP 202` tại mục 6.1.
- Kết quả cuối luôn dùng response đầy đủ tại mục 6.2 hoặc callback tại mục 6.3.
- Mỗi mục bên dưới định nghĩa request body và cấu trúc `data.result` tương ứng.

### 6.4. Lấy báo giá chuyến đi

```http
POST /api/v1/service/ride/quote
```

Request:

```json
{
  "user_id": "user-123",
  "request_id": "550e8400-e29b-41d4-a716-446655440000",
  "current_location": {
    "lat": 10.7769,
    "lng": 106.7009
  },
  "destination": {
    "address": "Đại học Bách Khoa TP.HCM",
    "lat": 10.7721,
    "lng": 106.6578
  }
}
```

| Trường request | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `user_id` | Có | string | Người dùng cần lấy báo giá trên điện thoại |
| `request_id` | Có | UUID string | ID duy nhất của lần lấy báo giá |
| `current_location` | Có | object | Vị trí bắt đầu chuyến đi |
| `current_location.lat` | Có | number | Vĩ độ hiện tại, từ `-90` đến `90` |
| `current_location.lng` | Có | number | Kinh độ hiện tại, từ `-180` đến `180` |
| `destination` | Có | object | Điểm đến cần báo giá |
| `destination.address` | Có điều kiện | string | Địa chỉ điểm đến; bắt buộc khi không có `lat/lng` |
| `destination.lat` | Có điều kiện | number | Vĩ độ điểm đến; đi cùng `destination.lng` |
| `destination.lng` | Có điều kiện | number | Kinh độ điểm đến; đi cùng `destination.lat` |

Kết quả cuối trong `data.result`:

```json
{
  "quote_id": "quote-123",
  "product_type": "standard",
  "price_estimate": {
    "currency": "VND",
    "amount": 85000
  },
  "eta_minutes": 6,
  "expires_at": "2026-08-02T10:05:00Z"
}
```

| Trường kết quả | Kiểu | Ý nghĩa |
|---|---|---|
| `quote_id` | string | ID báo giá dùng cho endpoint `ride/confirm` |
| `product_type` | string | Loại phương tiện hoặc gói dịch vụ |
| `price_estimate` | object | Thông tin giá dự kiến |
| `price_estimate.currency` | string | Mã tiền tệ ISO 4217 |
| `price_estimate.amount` | number | Số tiền dự kiến |
| `eta_minutes` | integer | Số phút dự kiến xe đến điểm đón |
| `expires_at` | datetime | Thời điểm báo giá hết hiệu lực |

Mã lỗi kết quả: `INVALID_LOCATION`, `INVALID_DESTINATION`, `RIDE_ACCOUNT_NOT_CONNECTED`, `NO_RIDE_AVAILABLE`, `RIDE_PROVIDER_ERROR`.

### 6.5. Xác nhận hoặc hủy đặt xe

```http
POST /api/v1/service/ride/confirm
```

Request:

```json
{
  "user_id": "user-123",
  "request_id": "550e8400-e29b-41d4-a716-446655440001",
  "quote_id": "quote-123",
  "confirm": true
}
```

| Trường request | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `user_id` | Có | string | Người dùng sở hữu báo giá |
| `request_id` | Có | UUID string | ID duy nhất của yêu cầu xác nhận/hủy |
| `quote_id` | Có | string | ID nhận từ kết quả `ride/quote` |
| `confirm` | Có | boolean | `true`: đặt xe; `false`: hủy quy trình đặt xe |

Kết quả khi `confirm = true`:

```json
{
  "quote_id": "quote-123",
  "ride_id": "ride-123",
  "ride_status": "requested"
}
```

| Trường kết quả | Kiểu | Ý nghĩa |
|---|---|---|
| `quote_id` | string | Báo giá đã được xử lý |
| `ride_id` | string/null | ID chuyến đi; `null` khi `confirm = false` |
| `ride_status` | enum | `requested` khi đã gửi yêu cầu đặt xe |

Kết quả khi `confirm = false`:

```json
{
  "quote_id": "quote-123",
  "ride_id": null,
  "ride_status": "cancelled"
}
```

| Trường kết quả | Kiểu | Ý nghĩa |
|---|---|---|
| `quote_id` | string | Báo giá đã được hủy |
| `ride_id` | null | Không tạo chuyến đi |
| `ride_status` | enum | `cancelled`: quy trình đặt xe đã dừng |

Mã lỗi kết quả: `QUOTE_NOT_FOUND`, `QUOTE_EXPIRED`, `QUOTE_ALREADY_USED`, `RIDE_CONFIRM_FAILED`.

### 6.6. Phát nhạc

```http
POST /api/v1/service/music/play
```

Request:

```json
{
  "user_id": "user-123",
  "request_id": "550e8400-e29b-41d4-a716-446655440002",
  "song": "Nơi này có anh - Sơn Tùng M-TP",
  "volume": 60
}
```

| Trường request | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `user_id` | Có | string | Người dùng cần phát nhạc trên điện thoại |
| `request_id` | Có | UUID string | ID duy nhất của lệnh phát nhạc |
| `song` | Có | string | Chuỗi tìm kiếm gồm tên bài hát, nghệ sĩ hoặc cả hai |
| `volume` | Không | integer | Mức âm lượng ban đầu, từ `0` đến `100` |

Kết quả cuối trong `data.result`:

```json
{
  "track_id": "track-123",
  "title": "Nơi này có anh",
  "artist": "Sơn Tùng M-TP",
  "playback_state": "playing",
  "volume": 60
}
```

| Trường kết quả | Kiểu | Ý nghĩa |
|---|---|---|
| `track_id` | string | ID bài hát do Music Provider trả về |
| `title` | string | Tên bài hát thực tế đã chọn |
| `artist` | string | Nghệ sĩ của bài hát |
| `playback_state` | enum | `playing`: bài hát đang phát trên điện thoại |
| `volume` | integer | Mức âm lượng thực tế sau khi bắt đầu phát |

Mã lỗi kết quả: `SONG_NOT_FOUND`, `MUSIC_ACCOUNT_NOT_CONNECTED`, `SUBSCRIPTION_INACTIVE`, `PLAYBACK_FAILED`.

### 6.7. Dừng nhạc

```http
POST /api/v1/service/music/stop
```

Request:

```json
{
  "user_id": "user-123",
  "request_id": "550e8400-e29b-41d4-a716-446655440003"
}
```

| Trường request | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `user_id` | Có | string | Người dùng có phiên phát nhạc cần dừng |
| `request_id` | Có | UUID string | ID duy nhất của lệnh dừng nhạc |

Kết quả cuối trong `data.result`:

```json
{
  "playback_state": "stopped"
}
```

| Trường kết quả | Kiểu | Ý nghĩa |
|---|---|---|
| `playback_state` | enum | `stopped`: phát nhạc trên điện thoại đã dừng |

Mã lỗi kết quả: `NO_ACTIVE_PLAYBACK`, `PLAYBACK_STOP_FAILED`.

### 6.8. Thay đổi âm lượng

```http
POST /api/v1/service/music/volume
```

Request theo hướng:

```json
{
  "user_id": "user-123",
  "request_id": "550e8400-e29b-41d4-a716-446655440004",
  "direction": "up"
}
```

Request theo mức tuyệt đối:

```json
{
  "user_id": "user-123",
  "request_id": "550e8400-e29b-41d4-a716-446655440004",
  "level": 70
}
```

| Trường request | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `user_id` | Có | string | Người dùng có âm lượng điện thoại cần thay đổi |
| `request_id` | Có | UUID string | ID duy nhất của lệnh thay đổi âm lượng |
| `direction` | Có điều kiện | enum | `up` hoặc `down`; không gửi cùng `level` |
| `level` | Có điều kiện | integer | Mức âm lượng tuyệt đối `0..100`; không gửi cùng `direction` |

Kết quả cuối trong `data.result`:

```json
{
  "volume_state": "changed",
  "level": 70
}
```

| Trường kết quả | Kiểu | Ý nghĩa |
|---|---|---|
| `volume_state` | enum | `changed`: điện thoại đã áp dụng mức âm lượng mới |
| `level` | integer | Mức âm lượng thực tế sau khi thay đổi |

Mã lỗi kết quả: `INVALID_VOLUME`, `VOLUME_CHANGE_FAILED`.

### 6.9. Bắt đầu điều hướng realtime

```http
POST /api/v1/service/navigation/start
```

Request theo địa chỉ:

```json
{
  "user_id": "user-123",
  "request_id": "550e8400-e29b-41d4-a716-446655440005",
  "destination": {
    "address": "Bưu điện Thành phố Hồ Chí Minh"
  }
}
```

Request theo tọa độ:

```json
{
  "user_id": "user-123",
  "request_id": "550e8400-e29b-41d4-a716-446655440005",
  "destination": {
    "lat": 10.7798,
    "lng": 106.6990
  }
}
```

| Trường request | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `user_id` | Có | string | Người dùng có điện thoại cần bắt đầu điều hướng |
| `request_id` | Có | UUID string | ID duy nhất của lệnh bắt đầu điều hướng |
| `destination` | Có | object | Điểm đích của phiên điều hướng |
| `destination.address` | Có điều kiện | string | Địa chỉ điểm đích; dùng khi không gửi tọa độ |
| `destination.lat` | Có điều kiện | number | Vĩ độ điểm đích; đi cùng `destination.lng` |
| `destination.lng` | Có điều kiện | number | Kinh độ điểm đích; đi cùng `destination.lat` |

Chế độ điều hướng cố định là `walking`. Request hoàn tất khi Google Maps Navigation SDK bắt đầu guidance trên điện thoại.

Kết quả cuối trong `data.result`:

```json
{
  "navigation_id": "nav-123",
  "navigation_state": "navigating",
  "travel_mode": "walking",
  "destination": {
    "address": "Bưu điện Thành phố Hồ Chí Minh",
    "lat": 10.7798,
    "lng": 106.6990
  }
}
```

| Trường kết quả | Kiểu | Ý nghĩa |
|---|---|---|
| `navigation_id` | string | ID phiên điều hướng; dùng cho endpoint `navigation/stop` |
| `navigation_state` | enum | `navigating`: guidance realtime đang chạy trên điện thoại |
| `travel_mode` | enum | `walking`: chế độ đi bộ |
| `destination` | object | Điểm đích đã được Navigation Provider xác định |
| `destination.address` | string/null | Địa chỉ chuẩn hóa của điểm đích |
| `destination.lat` | number | Vĩ độ điểm đích |
| `destination.lng` | number | Kinh độ điểm đích |

Mã lỗi kết quả: `CURRENT_LOCATION_UNAVAILABLE`, `INVALID_DESTINATION`, `LOCATION_PERMISSION_DENIED`, `NAVIGATION_PROVIDER_ERROR`, `NAVIGATION_START_FAILED`.

### 6.10. Dừng điều hướng

```http
POST /api/v1/service/navigation/stop
```

Request:

```json
{
  "user_id": "user-123",
  "request_id": "550e8400-e29b-41d4-a716-446655440006",
  "navigation_id": "nav-123"
}
```

| Trường request | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `user_id` | Có | string | Người dùng sở hữu phiên điều hướng |
| `request_id` | Có | UUID string | ID duy nhất của lệnh dừng điều hướng |
| `navigation_id` | Có | string | ID nhận từ kết quả `navigation/start` |

Kết quả cuối trong `data.result`:

```json
{
  "navigation_id": "nav-123",
  "navigation_state": "stopped"
}
```

| Trường kết quả | Kiểu | Ý nghĩa |
|---|---|---|
| `navigation_id` | string | Phiên điều hướng đã được xử lý |
| `navigation_state` | enum | `stopped`: guidance trên điện thoại đã dừng |

Mã lỗi kết quả: `NAVIGATION_NOT_FOUND`, `NAVIGATION_ALREADY_STOPPED`, `NAVIGATION_STOP_FAILED`.

### 6.11. Gọi khẩn cấp

```http
POST /api/v1/service/emergency/call
```

Request:

```json
{
  "user_id": "user-123",
  "request_id": "550e8400-e29b-41d4-a716-446655440007"
}
```

| Trường request | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `user_id` | Có | string | Người dùng có số khẩn cấp đã cấu hình trên điện thoại |
| `request_id` | Có | UUID string | ID duy nhất của lần kích hoạt gọi khẩn cấp |

Ứng dụng sử dụng số khẩn cấp đã cấu hình trên điện thoại, gửi vị trí qua SMS và thực hiện chu kỳ gọi.

Kết quả cuối trong `data.result`:

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

| Trường kết quả | Kiểu | Ý nghĩa |
|---|---|---|
| `emergency_state` | enum | Trạng thái tổng thể của quy trình gọi khẩn cấp |
| `attempt` | integer | Số thứ tự lần gọi gần nhất |
| `answered` | boolean | `true` khi cuộc gọi đã được trả lời |
| `cycle` | enum | `calling_5min`, `paused_10min` hoặc `stopped` |
| `contact` | string | Số khẩn cấp đã che bớt ký tự |
| `sms_sent` | boolean | Kết quả gửi SMS chứa vị trí |

Mã lỗi kết quả: `EMERGENCY_CONTACT_NOT_CONFIGURED`, `CURRENT_LOCATION_UNAVAILABLE`, `CALL_PERMISSION_DENIED`, `SMS_PERMISSION_DENIED`, `CALL_FAILED`, `SMS_FAILED`.

### 6.12. Gọi người trong danh bạ

```http
POST /api/v1/service/contact/call
```

Request:

```json
{
  "user_id": "user-123",
  "request_id": "550e8400-e29b-41d4-a716-446655440008",
  "name": "Nguyễn Văn A"
}
```

| Trường request | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `user_id` | Có | string | Người dùng sở hữu danh bạ cần tìm kiếm |
| `request_id` | Có | UUID string | ID duy nhất của lệnh gọi liên hệ |
| `name` | Có | string | Tên liên hệ cần tìm; không được rỗng |

Kết quả cuối trong `data.result`:

```json
{
  "call_state": "calling",
  "contact_name": "Nguyễn Văn A",
  "phone_number": "***789"
}
```

| Trường kết quả | Kiểu | Ý nghĩa |
|---|---|---|
| `call_state` | enum | `calling`: điện thoại đã bắt đầu cuộc gọi |
| `contact_name` | string | Tên liên hệ thực tế được chọn |
| `phone_number` | string | Số điện thoại đã che bớt ký tự |

Khi có nhiều liên hệ trùng tên, request chuyển sang `failed`:

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

| Trường lỗi nhiều kết quả | Kiểu | Ý nghĩa |
|---|---|---|
| `code` | string | `MULTIPLE_CONTACTS_FOUND` |
| `message` | string | Mô tả kết quả tìm kiếm không duy nhất |
| `details.candidates` | array | Danh sách liên hệ phù hợp |
| `details.candidates[].name` | string | Tên của liên hệ ứng viên |
| `details.candidates[].phone_number` | string | Số điện thoại đã che bớt ký tự |

Mã lỗi kết quả: `CONTACT_NOT_FOUND`, `MULTIPLE_CONTACTS_FOUND`, `CONTACT_PERMISSION_DENIED`, `CALL_PERMISSION_DENIED`, `CALL_FAILED`.

### 6.13. Internal Device API

Các endpoint dưới đây chỉ dành cho ứng dụng Android, không công bố cho External API Client.

```http
Authorization: Bearer <app_access_token>
Content-Type: application/json
```

```http
POST /api/v1/device/register
```

```json
{
  "user_id": "user-123",
  "device_id": "android-device-123",
  "platform": "android",
  "push_token": "fcm-token"
}
```

| Trường register request | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `user_id` | Có | string | Người dùng liên kết với thiết bị |
| `device_id` | Có | string | ID ổn định của bản cài đặt ứng dụng Android |
| `platform` | Có | enum | Luôn là `android` |
| `push_token` | Có | string | FCM registration token dùng để gửi command đến app |

Response `HTTP 200`:

```json
{
  "status": "ok",
  "data": {
    "device_id": "android-device-123",
    "registered": true
  }
}
```

| Trường register response | Kiểu | Ý nghĩa |
|---|---|---|
| `status` | string | `ok` khi đăng ký thành công |
| `data.device_id` | string | Thiết bị vừa được đăng ký hoặc cập nhật |
| `data.registered` | boolean | `true` khi server đã lưu liên kết thiết bị |

```http
POST /api/v1/device/report
```

```json
{
  "user_id": "user-123",
  "device_id": "android-device-123",
  "request_id": "550e8400-e29b-41d4-a716-446655440008",
  "action": "contact_call",
  "execution_state": "succeeded | failed",
  "result": {},
  "error": null,
  "timestamp": "ISO 8601"
}
```

| Trường report request | Bắt buộc | Kiểu | Ý nghĩa |
|---|---|---|---|
| `user_id` | Có | string | Người dùng sở hữu command |
| `device_id` | Có | string | Thiết bị đã thực thi command |
| `request_id` | Có | UUID string | Request Public API liên quan |
| `action` | Có | string | Action app vừa thực thi |
| `execution_state` | Có | enum | `succeeded` hoặc `failed` |
| `result` | Có điều kiện | object/null | Kết quả handler khi thành công |
| `error` | Có điều kiện | object/null | `code`, `message`, `details` khi thất bại |
| `timestamp` | Có | datetime | Thời điểm app tạo report, ISO 8601 UTC |

Response `HTTP 200`:

```json
{
  "status": "ok",
  "data": {
    "request_id": "550e8400-e29b-41d4-a716-446655440008",
    "report_received": true
  }
}
```

| Trường report response | Kiểu | Ý nghĩa |
|---|---|---|
| `status` | string | `ok` khi server nhận report |
| `data.request_id` | UUID string | Request đã được cập nhật |
| `data.report_received` | boolean | `true` khi report đã được ghi nhận |

### 6.14. Internal Device Command

```json
{
  "request_id": "uuid",
  "action": "contact_call",
  "params": {
    "name": "Nguyễn Văn A"
  },
  "timestamp": "ISO 8601"
}
```

| Trường command | Kiểu | Ý nghĩa |
|---|---|---|
| `request_id` | UUID string | ID dùng để app chống thực thi command trùng |
| `action` | string | Tên handler app phải gọi |
| `params` | object | Tham số nghiệp vụ lấy từ request của endpoint |
| `timestamp` | datetime | Thời điểm server tạo command, ISO 8601 UTC |

Command format, push channel và retry policy không thuộc Public API.

### 6.15. Trạng thái

Public request state:

```text
processing → succeeded
          ↘ failed
          ↘ timed_out
```

Internal delivery state:

```text
received → resolving_device → waking_app → command_sent → report_received
                  ↘ device_unavailable
                                   ↘ delivery_failed
                                                  ↘ report_timeout
```

External API Client chỉ nhận public request state.

### 6.16. Mã lỗi Public API

| HTTP | Mã lỗi |
|---|---|
| 400 | `INVALID_REQUEST` |
| 401 | `UNAUTHORIZED` |
| 403 | `FORBIDDEN` |
| 404 | `REQUEST_NOT_FOUND` |
| 409 | `REQUEST_ID_CONFLICT` |
| 500 | `INTERNAL_ERROR` |
| 503 | `TARGET_UNAVAILABLE` |

Lỗi từ handler trên điện thoại được công bố qua `request_state = failed` và trường `error`.

## 7. Luồng xử lý chung

### 7.1. Luồng Public API

```text
1. External API Client gọi endpoint chức năng.
2. Server trả request_id và request_state = processing.
3. Client lấy kết quả qua Status API hoặc callback.
```

### 7.2. Internal App Execution Pipeline

```text
1. Xác thực client và validate request.
2. Tạo operation theo request_id.
3. Ánh xạ endpoint sang action cố định.
4. Liên kết user_id với thiết bị và push token.
5. Đánh thức ứng dụng và gửi command.
6. Retry giao nhận theo policy.
7. Ứng dụng ánh xạ action đến handler.
8. Handler thực hiện chức năng trên điện thoại.
9. Ứng dụng gửi internal report.
10. Server cập nhật public request state và result.
11. Server công bố kết quả qua Status API và callback.
```

Toàn bộ pipeline là nội bộ. Mỗi endpoint tạo đúng một action; server không suy luận nghiệp vụ hoặc tạo thêm chuỗi chức năng.

## 8. Luồng chức năng trên ứng dụng

Các luồng trong mục này thuộc xử lý nội bộ của server và ứng dụng, không thuộc Public API contract.

### 8.1. Đặt xe

#### Lấy báo giá

```text
POST /api/v1/service/ride/quote
    ↓
action = ride_quote
    ↓
Ride Handler nhận vị trí hiện tại và điểm đến
    ↓
Ride Adapter lấy loại xe, giá và ETA
    ↓
Ứng dụng report quote_id và thông tin báo giá
```

#### Xác nhận hoặc hủy

```text
POST /api/v1/service/ride/confirm
    ↓
action = ride_confirm
    ↓
Ride Handler nhận quote_id và confirm
    ↓
Ride Adapter xác nhận hoặc hủy
    ↓
Ứng dụng report ride_id và ride_status
```

Các thay đổi trạng thái chuyến đi được ứng dụng gửi thành internal report mới với cùng `ride_id`.

### 8.2. Điều khiển âm nhạc

```text
POST /api/v1/service/music/play
    ↓ action = music_play
Music Handler tìm nội dung và phát bài hát
    ↓
Report track và playback_state

POST /api/v1/service/music/stop
    ↓ action = music_stop
Music Handler dừng phát
    ↓
Report playback_state

POST /api/v1/service/music/volume
    ↓ action = music_volume
Music Handler thay đổi âm lượng
    ↓
Report volume_state
```

### 8.3. Điều hướng đi bộ

```text
POST /api/v1/service/navigation/start
    ↓
action = navigation_start
    ↓
Navigation Handler nhận điểm đến và lấy vị trí hiện tại
    ↓
Tạo phiên điều hướng và navigation_id
    ↓
Navigation Adapter khởi chạy walking guidance
    ↓
Google Maps Navigation SDK duy trì vị trí, tuyến đường,
turn-by-turn và re-route realtime trên điện thoại
    ↓
Ứng dụng report { navigation_id, navigation_state: navigating }
```

```text
POST /api/v1/service/navigation/stop
    ↓
action = navigation_stop
    ↓
Navigation Handler tìm phiên theo navigation_id
    ↓
Navigation Adapter dừng guidance trên điện thoại
    ↓
Ứng dụng report { navigation_id, navigation_state: stopped }
```

Phiên điều hướng tiếp tục chạy trên điện thoại đến khi đến đích, nhận lệnh `navigation/stop` hoặc gặp lỗi. Public API không stream turn-by-turn; phần realtime được Navigation Adapter hiển thị và xử lý trên điện thoại.

### 8.4. Gọi khẩn cấp

```text
POST /api/v1/service/emergency/call
    ↓
action = emergency_call
    ↓
Emergency Handler đọc số khẩn cấp đã cấu hình
    ↓
Lấy vị trí và gửi qua SMS
    ↓
Thực hiện cuộc gọi
    ↓
Ứng dụng report attempt, answered, cycle, contact và sms_sent
```

Chu kỳ thực thi trên ứng dụng:

```text
Gọi tối đa 5 phút hoặc đến khi được trả lời
    ↓ không được trả lời
Chờ 10 phút
    ↓
Gọi lần thứ hai
    ↓
Dừng khi được trả lời hoặc khi lần thứ hai kết thúc
```

### 8.5. Gọi người trong danh bạ

```text
POST /api/v1/service/contact/call
    ↓
action = contact_call
    ↓
Contact Handler tìm tên trong danh bạ
    ├── Không tìm thấy → report not_found
    ├── Nhiều kết quả → report multiple_matched và candidates
    └── Một kết quả → thực hiện cuộc gọi và report calling
```

## 9. Giao diện demo ứng dụng

Ứng dụng Android cung cấp các màn hình và trạng thái sau.

| Màn hình | Nội dung tối thiểu |
|---|---|
| Tổng quan | Kết nối server, thiết bị, push token, quyền và command gần nhất |
| Cấu hình | `user_id`, `device_id`, số khẩn cấp và tài khoản provider |
| Đặt xe | Vị trí, điểm đến, báo giá, xác nhận và trạng thái chuyến |
| Âm nhạc | Tên bài hát, Play, Stop, âm lượng và trạng thái |
| Điều hướng | Điểm đến, Start, `navigation_id`, Stop và trạng thái realtime |
| Khẩn cấp | Số đã cấu hình, nút chạy thử và trạng thái gọi/SMS |
| Danh bạ | Tên liên hệ, kết quả tìm kiếm và trạng thái gọi |
| Command Detail | `request_id`, action, params, trạng thái, result và error |
| Nhật ký | Danh sách internal command và report theo thời gian |

Luồng minh họa đầy đủ:

```text
External API Client gọi endpoint
    ↓
Server gửi push command
    ↓
Ứng dụng mở Command Detail
    ↓
Handler thực hiện chức năng
    ↓
UI hiển thị execution state
    ↓
Ứng dụng gửi report
```

Chế độ local demo cho phép chạy trực tiếp cùng feature handler từ màn hình chức năng, không đi qua server.

## 10. Cấu trúc mã nguồn ứng dụng tối thiểu

| Thành phần | Trách nhiệm |
|---|---|
| UI/Screens | Hiển thị giao diện demo |
| API Client | Đăng ký thiết bị và gửi report |
| Push Receiver | Nhận push command |
| Action Dispatcher | Ánh xạ action đến handler |
| Feature Handlers | Thực thi chức năng chuyên biệt |
| Capability Adapters | Truy cập API hệ thống trên thiết bị |
| Provider Adapters | Kết nối dịch vụ bên ngoài |
| Permission Manager | Quản lý quyền thiết bị |
| Local Config | Lưu cấu hình ứng dụng |
| Request Store | Lưu command và trạng thái cục bộ |

## 11. Dữ liệu server quản lý

App Communication Server chỉ lưu dữ liệu phục vụ giao nhận:

- `user_id`, `device_id`, platform và push token.
- `request_id`, endpoint, action và thời điểm nhận request.
- Public request state: `processing`, `succeeded`, `failed` hoặc `timed_out`.
- Internal delivery state: `resolving_device`, `waking_app`, `command_sent`, `report_received` hoặc `delivery_failed`.
- Internal report nhận từ ứng dụng.
- Trạng thái callback về External API Client.
- Nhật ký kỹ thuật và số lần retry.

Server không quản lý:

- OAuth token hoặc phiên đăng nhập của ride/music provider.
- Quote, chuyến đi và playback session như trạng thái nghiệp vụ nội bộ.
- Danh bạ, số khẩn cấp, vị trí và quyền trên thiết bị.
- Kiến trúc hoặc trạng thái xử lý nội bộ của External API Client.

## 12. Yêu cầu vận hành và bảo mật

- Chỉ chấp nhận request từ External API Client đã xác thực.
- Xác thực ứng dụng khi đăng ký thiết bị và gửi report.
- Kiểm tra `user_id` và `device_id` thuộc phạm vi token.
- Không thực thi lặp command có cùng `request_id`.
- Thiết lập timeout chờ internal report.
- Retry khi App Wake & Delivery hoặc callback tạm thời lỗi.
- Vô hiệu hóa push token hết hạn.
- Giới hạn tần suất endpoint nhạy cảm, đặc biệt `emergency/call`.
- Ghi log theo `request_id`; không ghi token và dữ liệu nhạy cảm.
- Chuyển result và error từ ứng dụng mà không biến đổi nghiệp vụ.

## 13. Giới hạn nền tảng

- Android nhận lệnh nền qua high-priority data message.
- Khả năng chạy nền Android phụ thuộc chính sách pin và ROM thiết bị.
- Android cần quyền vị trí, danh bạ, cuộc gọi, SMS, thông báo và foreground service tương ứng.

## 14. Tham số triển khai

- App Communication Server base URL.
- Public API authentication credentials.
- External API Client callback base URL.
- App Wake & Delivery provider credentials.
- Timeout chờ internal report.
- Chính sách retry push và callback.
- Thời gian lưu technical request log.
- Giới hạn tần suất theo endpoint.
- Cấu hình provider trên ứng dụng Android.
