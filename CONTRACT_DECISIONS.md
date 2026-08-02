# Contract Decisions Register

Tài liệu này lưu các điểm chưa rõ hoặc mâu thuẫn cần được chốt trước khi khóa
Pydantic schema, OpenAPI artifact và contract test. Tài liệu không tự thay đổi
Public/Internal API contract.

## Source of truth

Thứ tự sử dụng tài liệu hiện tại:

1. `project_context.md` là nguồn contract và phạm vi nghiệp vụ chính thức.
2. `architeture.md` là implementation blueprint. Nội dung trong file này không
   được âm thầm ghi đè contract trong `project_context.md`.
3. `claude.md` là quy tắc phát triển và kiểm thử bắt buộc. Khi tài liệu, schema
   và mã nguồn mâu thuẫn, phải chỉ rõ mâu thuẫn và xác nhận nguồn đúng trước khi
   thay đổi hành vi public.

Mọi recommended default bên dưới đã được chốt theo đúng nội dung đề xuất (xem
Decisions log). Trạng thái **CHỐT** nghĩa là hành vi này được dùng làm căn cứ
thiết kế/contract/test từ nay; nó không tự động nghĩa là code đã cài đặt đầy đủ
hành vi đó. Bất kỳ chỗ nào code hiện tại chưa khớp quyết định đã chốt phải được
liệt kê là việc còn lại trong `TASK_PLAN.md`, không được coi là đã hoàn tất.

## Open questions và mâu thuẫn

### 1. Internal Device Command format

- **Trạng thái:** CHỐT (2026-08-02, xem Decisions log)
- **Mâu thuẫn:** `project_context.md` định nghĩa command gồm `request_id`,
  `action`, `params`, `timestamp`; `architeture.md` định nghĩa command versioned
  gồm `schema_version`, `request_id`, `action`, `params`, `issued_at`,
  `expires_at` và yêu cầu Android validate các trường này.
- **Recommended default:** Dùng format versioned trong `architeture.md` với
  `schema_version = 1`, `issued_at` và `expires_at`; sau khi được duyệt phải cập
  nhật nguồn contract và Internal OpenAPI đồng bộ.

### 2. Callback body và retry semantics

- **Trạng thái:** CHỐT (2026-08-02, xem Decisions log)
- **Mâu thuẫn:** `project_context.md` nói callback dùng trường `data` giống
  Status API, chưa rõ body là `{ "data": ... }` hay full envelope
  `{ "status": "ok", "data": ... }`. Tài liệu này chỉ nêu retry timeout/5xx và
  client trả HTTP 200, trong khi `architeture.md` coi mọi 2xx là thành công,
  retry network/408/429/5xx và coi 4xx còn lại là lỗi vĩnh viễn.
- **Recommended default:** Gửi full Status API envelope; mọi 2xx là thành công;
  retry network/408/429/5xx; các 4xx khác là permanent failure.

### 3. `TARGET_UNAVAILABLE` là lỗi sync hay async

- **Trạng thái:** CHỐT (2026-08-02, xem Decisions log)
- **Mâu thuẫn:** Bảng lỗi Public API ánh xạ `503 TARGET_UNAVAILABLE`, nhưng
  worker blueprint persist operation, trả 202 rồi mới resolve device; permanent
  delivery failure chuyển operation sang `failed`.
- **Recommended default:** Persist trước 202 và công bố `TARGET_UNAVAILABLE`
  dưới dạng async terminal error; chỉ dùng HTTP 503 cho lỗi hạ tầng đồng bộ thật
  sự trước khi operation được chấp nhận.

### 4. Internal Device API error contract

- **Trạng thái:** CHỐT (2026-08-02, xem Decisions log)
- **Chưa rõ:** Register/report chỉ có success response; chưa định nghĩa status
  và envelope cho bad token, sai user/device/action, invalid result schema,
  unknown request, late report hoặc conflicting duplicate report.
- **Recommended default:** Dùng chung stable error envelope
  `{ "status": "error", "error": { "code", "message", "details" } }`, nhưng
  định nghĩa một error-code set riêng cho Internal API và không đưa các lỗi nội
  bộ này vào Public OpenAPI.

### 5. Device enrollment, token scope và reassignment

- **Trạng thái:** CHỐT (2026-08-02, xem Decisions log)
- **Chưa rõ:** Device token phải khớp `user_id`/`device_id`, nhưng chưa mô tả
  bootstrap enrollment; chưa rõ token demo dùng chung hay theo enrollment, có
  cho đổi owner của `device_id`, rotate push token và cách enforce một active
  device cho mỗi user.
- **Recommended default:** Dùng enrollment token có scope user/device; cho phép
  idempotent re-register và push-token rotation với cùng owner; cấm đổi owner;
  khi activate device mới thì deactivate device cũ của user trong một
  transaction.

### 6. Status API ownership failure: 403 hay 404

- **Trạng thái:** CHỐT (2026-08-02, xem Decisions log)
- **Chưa rõ:** Contract có cả `FORBIDDEN` và `REQUEST_NOT_FOUND`; blueprint chỉ
  yêu cầu kiểm tra operation ownership theo `client_id`.
- **Recommended default:** Trả `404 REQUEST_NOT_FOUND` cho cả request không tồn
  tại và request thuộc client khác để tránh làm lộ sự tồn tại của resource.

### 7. Validation policy còn thiếu

- **Trạng thái:** CHỐT (2026-08-02, xem Decisions log)
- **Chưa rõ:** Chưa quy định reject/ignore extra fields; trim/min/max length cho
  ID, song, name, address; địa chỉ rỗng; range tọa độ ở mọi destination; amount,
  ETA và attempt bounds; timezone bắt buộc; `result`/`error` required-null hay
  optional; cấu trúc `error.details`.
- **Recommended default:** Pydantic models dùng `extra = "forbid"`; trim và
  reject chuỗi rỗng; tái sử dụng coordinate constraints; yêu cầu timezone-aware
  UTC; report luôn chứa cả `result` và `error`, một trường là object và trường
  còn lại là null theo `execution_state`. Mọi giới hạn độ dài/số phải được ghi
  rõ trong contract trước khi enforce.

### 8. Emergency result state và partial failure

- **Trạng thái:** CHỐT (2026-08-02, xem Decisions log)
- **Chưa rõ:** `emergency_state` được ghi là enum nhưng chỉ có example
  `completed`; chưa rõ trường hợp call thành công/SMS thất bại hoặc ngược lại
  trả success result với cờ false hay terminal error `CALL_FAILED`/`SMS_FAILED`.
- **Recommended default:** Chỉ coi operation succeeded khi toàn bộ flow bắt buộc
  hoàn tất; lỗi của bước bắt buộc trả terminal failed với stable error code.
  Danh sách đầy đủ giá trị `emergency_state` phải được bổ sung trước khi khóa
  schema.

### 9. Late và conflicting duplicate reports

- **Trạng thái:** CHỐT (2026-08-02, xem Decisions log)
- **Chưa rõ:** Exact duplicate payload hash được trả 200 không đổi state; chưa
  quy định response cho report khác payload sau khi operation đã terminal hoặc
  `timed_out`.
- **Recommended default:** Exact duplicate trả 200 idempotently; payload khác
  cho cùng `request_id` sau terminal trả 409 với Internal stable error; late
  report sau timeout không thay đổi terminal public state.

### 10. `accepted_at` khi retry idempotent Public request

- **Trạng thái:** CHỐT (2026-08-02, xem Decisions log)
- **Chưa rõ:** Blueprint nói duplicate cùng fingerprint trả original operation,
  nhưng contract không nói `accepted_at` là thời điểm tạo ban đầu hay thời điểm
  retry.
- **Recommended default:** Luôn trả timestamp tiếp nhận ban đầu của original
  operation để toàn bộ accepted response là representation ổn định.

### 11. Destination: address, coordinates và precedence

- **Trạng thái:** CHỐT (2026-08-02, xem Decisions log)
- **Chưa rõ:** Ride quote example gửi cả address và coordinates; navigation có
  example riêng cho từng dạng. Contract chưa nói có cho gửi cả hai, khi lệch
  nhau thì nguồn nào ưu tiên, và normalized result có bắt buộc đủ address/coords
  hay không.
- **Recommended default:** Cho phép `address` hoặc cặp `lat`/`lng`, đồng thời cho
  phép cả hai; coordinates là vị trí chính xác, address là label/hint. Provider
  result phải trả normalized coordinates; address có thể null đúng như result
  schema đã mô tả.

### 12. Status và callback terminal representation

- **Trạng thái:** CHỐT (2026-08-02, xem Decisions log)
- **Mâu thuẫn:** Definition of Done yêu cầu Status API và callback expose cùng
  terminal representation, nhưng callback contract chưa khóa top-level
  envelope và vì vậy chưa chứng minh được hai representation giống nhau.
- **Recommended default:** Dùng cùng một Pydantic response model cho Status API
  và callback payload; callback serialize nguyên full terminal response envelope.

### 13. `REPORT_TIMEOUT` trong Public timeout error

- **Trạng thái:** CHỐT (2026-08-02, xem Decisions log)
- **Mâu thuẫn:** `architeture.md` đặt `error = REPORT_TIMEOUT` khi timeout worker
  chuyển operation sang `timed_out`, nhưng `project_context.md` chỉ yêu cầu
  terminal error có `{code,message,details}` và không công bố `REPORT_TIMEOUT`
  trong bảng Public error hoặc danh sách async error theo action.
- **Recommended default:** Cho phép P1/P2 dùng `REPORT_TIMEOUT` làm internal
  blueprint reason để persist và kiểm thử state transition. Cho tới khi Public
  contract được phê duyệt, không coi đây là stable Public error code, không thêm
  nó vào Public OpenAPI/example/callback contract và không viết consumer contract
  test phụ thuộc vào giá trị này.

## Decisions log

| Ngày | Decision ID | Quyết định | Người chốt | Tài liệu/code cần cập nhật |
|---|---|---|---|---|
| 2026-08-02 | D-01 | Internal Device Command dùng format versioned của `architeture.md`: `schema_version=1`, `request_id`, `action`, `params`, `issued_at`, `expires_at`. | User | Internal command schema (chưa cài đặt), Internal OpenAPI, Android command DTO |
| 2026-08-02 | D-02 | Callback body = full Status API envelope; mọi 2xx là success; retry network/408/429/5xx; 4xx khác là permanent failure. | User | Callback worker (chưa cài đặt), callback contract test |
| 2026-08-02 | D-03 | `TARGET_UNAVAILABLE` là async terminal error sau khi đã persist/202; HTTP 503 chỉ dùng cho lỗi hạ tầng đồng bộ trước khi operation được chấp nhận. | User | `docs/p1-api-plan.md`, delivery/service logic (chưa cài đặt), Public OpenAPI error mapping |
| 2026-08-02 | D-04 | Internal Device API dùng chung error envelope `{status:"error",error:{code,message,details}}` với error-code set riêng cho Internal, không đưa vào Public OpenAPI. | User | Internal Device schema/router (chưa cài đặt error responses), `contracts/device-api.openapi.yaml` |
| 2026-08-02 | D-05 | Enrollment token có scope user/device; cho phép idempotent re-register và push-token rotation cùng owner; cấm đổi owner; activate device mới thì deactivate device cũ của user trong một transaction. | User | `DeviceService`/`DeviceRepository` (chưa cài đặt), `docs/p1-database-plan.md` |
| 2026-08-02 | D-06 | Status API trả `404 REQUEST_NOT_FOUND` cho cả request không tồn tại và request thuộc client khác. | User | `docs/p1-api-plan.md`, Status API handler (chưa cài đặt) |
| 2026-08-02 | D-07 | Pydantic models dùng `extra="forbid"`; trim và reject chuỗi rỗng; coordinate constraints tái sử dụng; timezone-aware UTC bắt buộc; report luôn có cả `result`/`error`, một object một null theo `execution_state`. Giới hạn độ dài/số cụ thể phải bổ sung trước khi enforce riêng lẻ. | User | `app/schemas/*.py` (hiện CHƯA có `extra="forbid"`/trim — cần code + test bổ sung), contract tests |
| 2026-08-02 | D-08 | Operation chỉ succeeded khi toàn bộ flow bắt buộc hoàn tất; lỗi bước bắt buộc trả terminal failed với stable error code. Danh sách đầy đủ `emergency_state` vẫn cần bổ sung trước khi khóa schema chặt (enum), hiện giữ `str`. | User | `app/schemas/service_results.py::EmergencyCallResult` (giữ `str`, chưa enum đầy đủ), emergency service logic (chưa cài đặt) |
| 2026-08-02 | D-09 | Exact-duplicate report trả 200 idempotent; payload khác cho cùng `request_id` sau terminal trả 409 Internal stable error; late report sau timeout không đổi terminal public state. | User | `ReportService` (chưa cài đặt), `docs/p1-database-plan.md` PG-21/PG-23 |
| 2026-08-02 | D-10 | `accepted_at` của duplicate idempotent request luôn là thời điểm tiếp nhận gốc (`created_at` của operation gốc), không phải thời điểm retry. | User | `OperationService` idempotency path (chưa cài đặt) |
| 2026-08-02 | D-11 | Destination cho phép `address` hoặc `lat`/`lng`, hoặc cả hai; coordinates là vị trí chính xác, address là label/hint; provider result luôn trả normalized coordinates, address có thể null. | User | Đã khớp `app/schemas/service_requests.py::Destination`; cần áp dụng khi ride/navigation provider logic được cài đặt |
| 2026-08-02 | D-12 | Status API và callback dùng cùng một Pydantic response model; callback serialize nguyên full terminal response envelope. | User | Callback worker/payload builder (chưa cài đặt) |
| 2026-08-02 | D-13 | `REPORT_TIMEOUT` là internal blueprint reason cho `delivery_state`; KHÔNG phải Public stable error code cho tới khi có quyết định riêng đưa nó vào Public OpenAPI. Quyết định D-13 chỉ xác nhận phạm vi internal hiện tại, không tự động công bố Public. | User | Không đổi code; giữ nguyên ràng buộc "không thêm vào Public OpenAPI/example/consumer test" |
