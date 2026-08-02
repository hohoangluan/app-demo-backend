# HƯỚNG DẪN HỌC KIẾN TRÚC PROTOTYPE

> Tài liệu giải thích các quyết định trong [`architeture.md`](./architeture.md) cho người mới.  
> Blueprint dùng để viết code nằm ở `architeture.md`; file này tập trung vào “vì sao” và “khi nào cần nâng cấp”.

## 1. Cách học với hai tài liệu

Khi triển khai một phần:

1. Đọc cấu trúc và yêu cầu tương ứng trong `architeture.md`.
2. Viết code/test theo blueprint đó.
3. Quay lại file này để hiểu failure mode và lý do thiết kế.
4. Tự tạo một lỗi có chủ đích, quan sát rồi sửa bằng invariant/test.

Đừng cố học toàn bộ queue, distributed system hoặc Kubernetes trước khi hoàn thành một vertical slice. Hãy học khái niệm ngay khi code hiện tại làm xuất hiện vấn đề đó.

## 2. Từ điển ngắn

| Khái niệm | Cách hiểu |
|---|---|
| Public API | Cửa vào cho External API Client |
| Internal Device API | Cửa vào chỉ dành cho Android register/report |
| Operation | Một bản ghi theo dõi request từ lúc nhận đến lúc kết thúc |
| Action | Tên handler cố định, ví dụ `music_play` |
| Delivery | Quá trình server gửi command đến điện thoại |
| Handler | Code Android thực hiện một chức năng |
| Adapter | Lớp che chi tiết SDK/provider khỏi handler |
| Report | Kết quả Android gửi về server |
| Worker | Code chạy nền để delivery, timeout hoặc callback |
| State machine | Danh sách trạng thái và chuyển đổi được phép |
| Idempotency | Retry cùng request không tạo lần thực thi mới |
| Lease | Quyền xử lý một job có thời hạn |
| Terminal state | Trạng thái cuối không sửa lại |
| At-least-once | Message có thể đến nhiều lần nhưng không bị bỏ qua âm thầm |
| Transaction | Nhóm thay đổi DB hoặc cùng thành công, hoặc cùng rollback |
| Race condition | Kết quả phụ thuộc hai tác vụ chạy đồng thời theo thứ tự nào |

## 3. Vì sao Public API phải bất đồng bộ?

Điện thoại có thể đang ngủ, mất mạng, force-stop hoặc bị Android giới hạn chạy nền. Nếu server giữ HTTP request mở chờ điện thoại:

- Client dễ timeout.
- Server tốn connection.
- Không biết nên chờ bao lâu.
- Restart server làm mất request đang chờ.

Do đó hệ thống dùng mẫu Accepted + Poll/Callback:

```text
Client POST command
  → Server lưu operation
  → Server trả 202 processing
  → Xử lý tiếp ở nền
  → Client GET status hoặc nhận callback
```

Điểm cần nhớ: `202` chỉ có nghĩa server đã nhận và lưu request, không có nghĩa điện thoại đã thực hiện thành công.

## 4. Vì sao dùng PostgreSQL làm job store trong prototype?

### 4.1. Cách dễ nhưng sai

```python
@router.post("/service/music/play")
async def play_music(body):
    asyncio.create_task(send_fcm(body))
    return {"state": "processing"}
```

Task nằm trong RAM. Nếu process crash ngay sau response, client đã nhận `processing` nhưng task biến mất.

### 4.2. Baseline bền vững

1. API insert `operations` trong transaction.
2. Chỉ trả `202` sau commit.
3. Worker loop query operation cần delivery.
4. Restart process không xóa operation.

PostgreSQL giải quyết đồng thời state, transaction, unique constraint và job persistence. Với một backend instance và tải demo, thêm Redis/Celery chưa giải quyết vấn đề mới nào đủ lớn.

### 4.3. Khi nào database polling không còn phù hợp?

- Hàng nghìn job/giây.
- Nhiều loại job với scheduling phức tạp.
- Polling tạo tải DB đáng kể.
- Nhiều worker tranh chấp cùng index.
- Cần dashboard/priority/routing chuyên dụng.

Lúc đó mới cân nhắc outbox + Celery/Redis.

## 5. Vì sao chỉ một Python process?

Lợi ích cho prototype/người mới:

- Một command để chạy.
- Debug API và worker cùng IDE.
- Ít network/config/deployment boundary.
- Dễ nhìn một request đi qua toàn hệ thống.

Đánh đổi:

- API và worker restart cùng nhau.
- Không scale độc lập.
- Một job blocking có thể ảnh hưởng HTTP nếu code sai.
- Uvicorn nhiều workers làm worker loop khởi động nhiều bản.

Vì vậy prototype chạy đúng một Uvicorn worker. Khi delivery/callback tải cao hoặc cần restart độc lập, tách `api.py` và `worker.py` nhưng vẫn dùng cùng package Python.

## 6. Những khái niệm bảo đảm tính đúng

### 6.1. Idempotency

Mạng không đáng tin cậy: client có thể timeout dù server đã nhận request, sau đó gửi lại.

Server lưu:

- `request_id`: nhận diện cùng một request.
- `request_fingerprint`: hash client, method, path và body đã chuẩn hóa.

Quy tắc:

- ID mới: tạo operation.
- ID cũ + fingerprint giống: trả operation cũ.
- ID cũ + fingerprint khác: `409 REQUEST_ID_CONFLICT`.

Bài tập: gửi 50 request đồng thời với cùng ID và chứng minh DB chỉ có một row.

### 6.2. State machine

Nếu code được phép gán state tùy ý, report muộn có thể sửa `timed_out` thành `succeeded`, hoặc callback lỗi làm request quay lại `processing`.

State machine chỉ cho phép:

```text
processing → succeeded | failed | timed_out
```

Terminal state là immutable. State transition nên là conditional SQL update hoặc update dưới row lock.

Bài tập: chạy report và timeout đồng thời; chỉ một bên được chuyển terminal state.

### 6.3. Transaction boundary

Hai thay đổi liên quan phải commit cùng nhau. Ví dụ nhận report:

```text
save result/error
set terminal request_state
set callback pending
```

Nếu ba bước commit riêng, crash ở giữa tạo state không nhất quán. Đặt chúng trong một transaction.

### 6.4. Lease

Worker cần đánh dấu “tôi đang xử lý row này” nhưng không nên giữ DB transaction mở trong lúc gọi network.

```text
claim row → set locked_until → commit
call FCM
save outcome in a new transaction
```

Nếu process chết, lease hết hạn và job được claim lại. Đây là cách phục hồi job kẹt.

Bài tập: kill backend sau claim; chờ lease hết và chứng minh job chạy lại.

### 6.5. At-least-once và duplicate

Process có thể chết sau khi FCM đã nhận message nhưng trước khi DB ghi `sent`. Worker sẽ gửi lại sau lease.

Không thể dùng một PostgreSQL transaction để bao trùm cả FCM. Vì vậy:

- Server chấp nhận có thể gửi trùng.
- Android Room unique `request_id` chống chạy handler trùng.
- Report API nhận duplicate idempotently.

Exactly-once end-to-end thường là ảo tưởng; hệ thống thực tế đạt “at-least-once delivery + idempotent processing”.

### 6.6. Không giữ DB lock khi gọi network

FCM/callback có thể mất nhiều giây. Giữ transaction/row lock trong thời gian đó gây:

- Connection pool cạn.
- Row khác chờ lock.
- Transaction dài khó rollback/retry.

Claim bằng transaction ngắn, gọi network bên ngoài, rồi transaction ngắn để lưu kết quả.

## 7. Vì sao Android phải persist trước khi execute?

`onMessageReceived` chỉ có ít thời gian xử lý và process có thể bị kill. Nếu receiver gọi provider ngay:

- Command mất khi process chết.
- Retry FCM có thể chạy handler lại.
- Không có dữ liệu cho màn hình log.

Luồng đúng:

```text
FCM receiver
  → validate
  → insert Room unique request_id
  → enqueue WorkManager
  → execute handler
  → save pending report
  → report worker retry đến HTTP 200
```

Room đóng vai trò source of truth trên thiết bị giống PostgreSQL trên server.

## 8. Vì sao dùng handler + adapter?

Handler mô tả chức năng; adapter mô tả cách gọi một SDK/provider cụ thể.

```text
MusicPlayHandler
    ↓ depends on interface
MediaPlaybackAdapter
    ├── FakeMediaPlaybackAdapter
    └── MusicKitMediaPlaybackAdapter
```

Lợi ích:

- Demo không bị chặn bởi credential.
- Unit test không gọi provider thật.
- Đổi provider không sửa pipeline command/report.
- Permission/provider error được map về stable error code ở một nơi.

Fake adapter không phải code “giả cho xong”; nó là test double có giá trị lâu dài.

## 9. Thứ tự học qua việc viết code

### 9.1. Khung Python

Làm FastAPI health endpoint, SQLAlchemy session, Alembic migration, uv/Ruff/mypy/pytest.

Học được:

- ASGI app và dependency injection đơn giản.
- Python environment/lockfile lặp lại được.
- ORM model khác Pydantic API schema như thế nào.
- Schema migration khác tự tạo bảng khi startup như thế nào.

### 9.2. Một vertical slice

Chọn `music_volume` và làm trọn:

```text
POST → operation → fake delivery → fake report → GET succeeded
```

Học được router → schema → service → repository → state transition. Một slice dọc phát hiện điểm thiếu của kiến trúc sớm hơn việc viết cả 9 controller nhưng chưa có pipeline chạy.

### 9.3. Worker/retry/race

Thêm delivery, timeout, callback loop và các test crash/race.

Học được event loop, transaction, lease, retry classification và eventual consistency.

### 9.4. Android pipeline

FCM → Room → WorkManager → handler → pending report.

Học được Android process lifecycle, offline-first và idempotency hai phía.

### 9.5. Nhân đủ contract rồi mới gắn provider thật

Khi một action chạy ổn, thêm 8 action còn lại bằng cùng pipeline. Provider thật được thêm sau fake E2E.

Học được giá trị của reuse và dependency inversion: tính năng mới chủ yếu thêm schema/handler/adapter, không copy delivery/report flow.

## 10. Thứ tự test và kiến thức thu được

| Test | Chứng minh | Kiến thức |
|---|---|---|
| Unit state/fingerprint | Logic thuần đúng | Pure function, type, boundary |
| PostgreSQL integration | Constraint/lock/transaction đúng | Database concurrency |
| FastAPI test | HTTP contract/auth đúng | Transport vs application logic |
| Android Room/worker | Process death/duplicate đúng | Offline-first |
| E2E | Các component ghép đúng | System boundary |
| Crash/race test | Failure mode được phục hồi | Resilience |

Không dùng SQLite để chứng minh PostgreSQL row locking/JSONB. Mock phù hợp với unit test, nhưng không thay được integration test cho database semantics.

## 11. Lộ trình nâng cấp có tín hiệu

| Dấu hiệu thật | Nâng cấp | Vấn đề được giải quyết | Kiến thức mới |
|---|---|---|---|
| API và worker ảnh hưởng nhau | Tách hai Python process | Scale/restart độc lập | Process boundary, deployment topology |
| Polling DB thành bottleneck | Celery + Redis | Scheduling/throughput job | Broker, producer/consumer |
| Commit DB và publish queue có khoảng trống | Transactional outbox | Dual-write an toàn | Eventual consistency, relay |
| Cần lịch sử từng attempt | Chuẩn hóa delivery/callback tables | Audit, analytics | Normalization, event history |
| Nhiều client/credential | OAuth2/IAM + credential tables | Lifecycle và phân quyền | Authentication/authorization |
| Có secret/data production | Secret manager/KMS | Rotation, access control | Envelope encryption |
| Debug hệ phân tán khó | OpenTelemetry/metrics/dashboard | Quan sát end-to-end | SLI/SLO, tracing |
| Một instance không đủ SLA | Replicas + managed PostgreSQL HA | Tải và single point of failure | Horizontal scale, failover |
| Client có version khác nhau | API versioning policy | Backward compatibility | Contract evolution |

### 11.1. Các level đề xuất

```text
Level 0 — Prototype
  one Python process + PostgreSQL + FCM + Android fake adapters
        ↓
Level 1 — API process + worker process
        ↓
Level 2 — Outbox + Celery/Redis
        ↓
Level 3 — Normalized audit + IAM/KMS + telemetry
        ↓
Level 4 — HA, autoscaling, backup/DR và SLO
```

### 11.2. Vì sao không bắt đầu ở Level 4?

Microservice, queue, Kubernetes và tracing đều tạo failure mode mới. Nếu dùng quá sớm, bạn phải debug platform trước khi hiểu operation state machine.

Chỉ nâng cấp khi:

1. Baseline có test.
2. Metric/yêu cầu chứng minh vấn đề.
3. Hiểu failure mode mới của giải pháp.
4. Có migration và rollback plan.

Nguyên tắc: mỗi độ phức tạp phải “trả tiền thuê” bằng lợi ích đo được.

## 12. Bài tập thực hành nâng cấp

- Kill backend sau khi claim lease; chứng minh job phục hồi.
- Gửi duplicate FCM; chứng minh Android chỉ execute một lần.
- Chạy report và timeout đồng thời; chứng minh terminal state immutable.
- Tách worker process; kill worker và chứng minh API vẫn lưu request.
- Thêm queue; tạo duplicate message và giữ idempotency.
- Thêm outbox; crash giữa commit/publish và chứng minh event không mất.
- Chạy hai worker cùng lúc; chứng minh row chỉ được một worker claim.
- Rotate encryption key mà vẫn đọc được dữ liệu cũ.
- Dùng trace/log theo một request từ POST đến callback.

## 13. Những giới hạn provider cần hiểu

### 13.1. FCM

High-priority FCM cố gắng đánh thức thiết bị nhưng Android chỉ cho thời gian xử lý giới hạn. Message không tạo trải nghiệm user-visible có thể bị hạ priority. Receiver nên persist/notify/schedule work thay vì chạy tác vụ dài.

### 13.2. Uber

Estimate/ride request production cần OAuth và có thể cần privileged scope. Nếu chưa có quyền, fake/sandbox hoặc deep link là lựa chọn trung thực hơn việc giả vờ integration đã production-ready.

### 13.3. MusicKit

Android SDK tồn tại nhưng cần Apple developer configuration, developer token, user permission và subscription. Không nhúng private key tạo developer token vào APK.

### 13.4. Navigation

Navigation SDK Android yêu cầu Google Play services, API key, target/min SDK phù hợp, foreground location flow và attribution/terms. Walking mode được SDK hỗ trợ.

### 13.5. Call/SMS/contacts

Direct call/SMS và đọc contacts chịu runtime permission, background restriction và Google Play policy. `ACTION_DIAL`/SMS Intent cần user xác nhận là baseline an toàn hơn. Production cần product/legal/policy review.

## 14. Checklist tự đánh giá

Bạn đã hiểu baseline khi có thể tự trả lời:

- Vì sao `202` không phải business success?
- Vì sao insert operation phải commit trước response?
- Fingerprint khác request ID thế nào?
- Vì sao FCM accepted không chuyển request thành succeeded?
- Lease phục hồi worker crash thế nào?
- Tại sao call network ngoài DB transaction?
- At-least-once khác exactly-once thế nào?
- Tại sao Android phải persist command/report?
- Report và timeout race được giải quyết ở đâu?
- Khi nào DB polling mới đáng thay bằng queue?
- Transactional outbox giải quyết dual-write problem nào?

Nếu chưa trả lời được câu nào, hãy tạo một test nhỏ cho failure mode đó thay vì chỉ đọc lý thuyết.

## 15. Tài liệu chính thức để học tiếp

- [Python releases](https://www.python.org/downloads/)
- [FastAPI type hints and OpenAPI](https://fastapi.tiangolo.com/python-types/)
- [SQLAlchemy asyncio](https://docs.sqlalchemy.org/en/20/orm/extensions/asyncio.html)
- [Alembic](https://alembic.sqlalchemy.org/)
- [uv project management](https://docs.astral.sh/uv/guides/projects/)
- [Firebase Admin SDK setup](https://firebase.google.com/docs/admin/setup)
- [FCM Android message priority](https://firebase.google.com/docs/cloud-messaging/android/message-priority)
- [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
- [Android background task restrictions](https://developer.android.com/develop/background-work/background-tasks/bg-work-restrictions)
- [Navigation SDK requirements](https://developers.google.com/maps/documentation/navigation/android-sdk/setup-overview)
- [Google Play SMS/Call Log policy](https://support.google.com/googleplay/android-developer/answer/10208820)
