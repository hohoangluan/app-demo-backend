# Contract Matrix

Context cô đọng cho Phase P0. Matrix này chỉ tổng hợp contract đã công bố trong
`project_context.md` và timeout trong `architeture.md`; không tự chốt các điểm
đang mở.

Decision ID `D-01` đến `D-13` tương ứng với mục 1 đến 13 trong
[`CONTRACT_DECISIONS.md`](../CONTRACT_DECISIONS.md). Giá trị ở cột **Blocker**
phải được xử lý qua Decisions log trước khi dùng để bổ sung hành vi chưa có
trong source contract.

## Quy ước chung

- Base path function: `/api/v1/service`.
- Public auth: `Authorization: Bearer <client_token>`.
- Mọi POST dùng `Content-Type: application/json` và có hai trường chung:
  `user_id: string`, `request_id: UUID string` do client sinh.
- Mọi function endpoint trả `HTTP 202`:
  `{status:"ok",data:{request_id,operation,request_state:"processing",status_url,accepted_at}}`.
- Synchronous error envelope:
  `{status:"error",error:{code,message,details}}`.
- Synchronous HTTP/code: `400 INVALID_REQUEST`, `401 UNAUTHORIZED`,
  `403 FORBIDDEN`, `404 REQUEST_NOT_FOUND`, `409 REQUEST_ID_CONFLICT`,
  `500 INTERNAL_ERROR`, `503 TARGET_UNAVAILABLE`.
- Exact validation policy còn bị chặn bởi `D-07`; semantics của
  `TARGET_UNAVAILABLE` bị chặn bởi `D-03`; timestamp `accepted_at` của duplicate
  idempotent request bị chặn bởi `D-10`; Public timeout error code bị chặn bởi
  `D-13` (`REPORT_TIMEOUT` hiện chỉ là internal blueprint reason).

Ký hiệu schema: `common` = `{user_id:string, request_id:uuid}`; `?` = trường
optional/conditional theo mô tả; literal được đặt trong dấu nháy; `A | B` là
union đã được contract nêu rõ.

## 9 Public function endpoints

| Method + endpoint | Operation | Action | Timeout | Request schema (ngoài `common`) | Terminal `data.result` khi succeeded | Async error codes | Blocker Decision ID |
|---|---|---|---:|---|---|---|---|
| `POST /api/v1/service/ride/quote` | `ride_quote` | `ride_quote` | 60s | `current_location:{lat:number[-90..90],lng:number[-180..180]}`; `destination:{address?:string,lat?:number,lng?:number}`; address bắt buộc nếu không có coordinates, lat/lng phải đi cùng nhau | `{quote_id:string,product_type:string,price_estimate:{currency:string (ISO 4217),amount:number},eta_minutes:integer,expires_at:datetime}` | `INVALID_LOCATION`, `INVALID_DESTINATION`, `RIDE_ACCOUNT_NOT_CONNECTED`, `NO_RIDE_AVAILABLE`, `RIDE_PROVIDER_ERROR` | `D-07`, `D-11` |
| `POST /api/v1/service/ride/confirm` | `ride_confirm` | `ride_confirm` | 90s | `quote_id:string`; `confirm:boolean` | Confirm: `{quote_id:string,ride_id:string,ride_status:"requested"}`; cancel: `{quote_id:string,ride_id:null,ride_status:"cancelled"}` | `QUOTE_NOT_FOUND`, `QUOTE_EXPIRED`, `QUOTE_ALREADY_USED`, `RIDE_CONFIRM_FAILED` | `D-07` |
| `POST /api/v1/service/music/play` | `music_play` | `music_play` | 45s | `song:string`; `volume?:integer[0..100]` | `{track_id:string,title:string,artist:string,playback_state:"playing",volume:integer}` | `SONG_NOT_FOUND`, `MUSIC_ACCOUNT_NOT_CONNECTED`, `SUBSCRIPTION_INACTIVE`, `PLAYBACK_FAILED` | `D-07` |
| `POST /api/v1/service/music/stop` | `music_stop` | `music_stop` | 30s | Không có trường riêng | `{playback_state:"stopped"}` | `NO_ACTIVE_PLAYBACK`, `PLAYBACK_STOP_FAILED` | `D-07` |
| `POST /api/v1/service/music/volume` | `music_volume` | `music_volume` | 30s | Chính xác một trong `direction:"up"/"down"` hoặc `level:integer[0..100]`; không gửi đồng thời | `{volume_state:"changed",level:integer}` | `INVALID_VOLUME`, `VOLUME_CHANGE_FAILED` | `D-07` |
| `POST /api/v1/service/navigation/start` | `navigation_start` | `navigation_start` | 90s | `destination:{address?:string,lat?:number,lng?:number}`; address khi không gửi coordinates, lat/lng phải đi cùng nhau; travel mode cố định `walking` | `{navigation_id:string,navigation_state:"navigating",travel_mode:"walking",destination:{address:string|null,lat:number,lng:number}}` | `CURRENT_LOCATION_UNAVAILABLE`, `INVALID_DESTINATION`, `LOCATION_PERMISSION_DENIED`, `NAVIGATION_PROVIDER_ERROR`, `NAVIGATION_START_FAILED` | `D-07`, `D-11` |
| `POST /api/v1/service/navigation/stop` | `navigation_stop` | `navigation_stop` | 45s | `navigation_id:string` | `{navigation_id:string,navigation_state:"stopped"}` | `NAVIGATION_NOT_FOUND`, `NAVIGATION_ALREADY_STOPPED`, `NAVIGATION_STOP_FAILED` | `D-07` |
| `POST /api/v1/service/emergency/call` | `emergency_call` | `emergency_call` | 21m; demo clock rút ngắn | Không có trường riêng | `{emergency_state:enum (chưa liệt kê; example "completed"),attempt:integer,answered:boolean,cycle:"calling_5min"/"paused_10min"/"stopped",contact:string (masked),sms_sent:boolean}` | `EMERGENCY_CONTACT_NOT_CONFIGURED`, `CURRENT_LOCATION_UNAVAILABLE`, `CALL_PERMISSION_DENIED`, `SMS_PERMISSION_DENIED`, `CALL_FAILED`, `SMS_FAILED` | `D-07`, `D-08` |
| `POST /api/v1/service/contact/call` | `contact_call` | `contact_call` | 60s | `name:string` không rỗng | `{call_state:"calling",contact_name:string,phone_number:string (masked)}` | `CONTACT_NOT_FOUND`, `MULTIPLE_CONTACTS_FOUND`, `CONTACT_PERMISSION_DENIED`, `CALL_PERMISSION_DENIED`, `CALL_FAILED`; `MULTIPLE_CONTACTS_FOUND.details={candidates:[{name,phone_number(masked)}]}` | `D-07` |

## Public Status và Internal Device API

| Scope | Method + endpoint | Auth | Request schema | Success response | Contract rules | Blocker Decision ID |
|---|---|---|---|---|---|---|
| Public | `GET /api/v1/requests/{request_id}` | Bearer client token; operation phải thuộc authenticated `client_id` | Path `request_id:uuid`; không có body | `HTTP 200 {status:"ok",data:{request_id:uuid,operation:string,request_state:"processing"/"succeeded"/"failed"/"timed_out",result:object-or-null,error:{code,message,details}-or-null,created_at:datetime,updated_at:datetime}}` | Processing: `result=null`; succeeded: result theo action; failed/timed_out: error có giá trị. Không expose internal delivery state. | `D-06`, `D-07`, `D-12`, `D-13` |
| Internal Android | `POST /api/v1/device/register` | `Authorization: Bearer <app_access_token>`; token/user/device scope chưa chốt | `{user_id:string,device_id:string,platform:"android",push_token:string}` | `HTTP 200 {status:"ok",data:{device_id:string,registered:true}}` | Register/update device; revoked device không được register; một active device/user là prototype scope nhưng enrollment/reassignment chưa được định nghĩa đầy đủ. | `D-04`, `D-05`, `D-07` |
| Internal Android | `POST /api/v1/device/report` | App bearer token; authenticated device phải khớp `user_id` và `device_id` | `{user_id:string,device_id:string,request_id:uuid,action:string,execution_state:"succeeded"/"failed",result:object-or-null,error:{code,message,details}-or-null,timestamp:datetime}` | `HTTP 200 {status:"ok",data:{request_id:uuid,report_received:true}}` | Request phải tồn tại; user/device/action/result schema phải khớp operation; exact duplicate payload hash trả 200 không đổi state; chỉ operation `processing` được chuyển terminal. | `D-04`, `D-07`, `D-09` |

## Related unresolved contract surfaces

Các mục sau không được tự suy ra từ matrix:

- Internal command wire format: `D-01`.
- Callback top-level body và retry semantics: `D-02`.
- Public device-unavailable sync/async behavior: `D-03`.
- Callback và Status API dùng cùng terminal representation: `D-12`.
- Public stable timeout error code (`REPORT_TIMEOUT` hiện là internal blueprint
  reason): `D-13`.

## Source consistency check

- Đủ đúng 9 function endpoints và mapping `operation == action` cố định như
  `project_context.md` mục 6.1/6.4-6.12 và `architeture.md` mục 5.
- Timeout khớp bảng tại `architeture.md` mục 5.
- Request/result/error code giữ nguyên `project_context.md`; matrix không thêm
  enum, field hoặc error code mới.
- Public Status, Device register và Device report giữ nguyên envelope/status code
  đã công bố.
- Các điểm hai tài liệu không thống nhất hoặc chưa đủ rõ chỉ được gắn Decision
  ID, không được coi là đã quyết định.
