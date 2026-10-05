# Quy tắc phát triển và kiểm thử

Tài liệu này là chỉ dẫn bắt buộc khi phân tích, viết, sửa, review hoặc hoàn thiện mã nguồn trong repository. Mục tiêu là giữ đúng kiến trúc, phát hiện lỗi sớm và chỉ bàn giao khi thay đổi đã được kiểm chứng bằng bằng chứng có thể lặp lại.

## 1. Nguyên tắc cốt lõi

- Đọc `docs/project-context.md` (hợp đồng) và `docs/architecture.md` (module và luồng) trước khi triển khai phần có liên quan đến nghiệp vụ, API, dữ liệu, worker hoặc Android.
- Không đoán hợp đồng nghiệp vụ. Khi tài liệu, schema và mã nguồn mâu thuẫn, phải chỉ rõ mâu thuẫn và xác nhận nguồn đúng trước khi thay đổi hành vi public.
- Ưu tiên thay đổi nhỏ, tập trung và dễ review. Không refactor ngoài phạm vi nếu không cần thiết để hoàn thành chức năng.
- Không được làm cho test đang chạy thành công bị hỏng. Không xóa, bỏ qua, làm yếu assertion hoặc đánh dấu `skip` chỉ để vượt qua kiểm thử.
- Không được báo chức năng đã hoàn tất nếu chưa chạy các kiểm tra áp dụng được. Nếu môi trường không cho phép chạy, phải nêu rõ kiểm tra nào chưa chạy, lý do và rủi ro còn lại.
- Không đưa secret, token, credential, dữ liệu cá nhân hoặc dữ liệu nhạy cảm vào mã nguồn, fixture, snapshot, log hay commit.

## 2. Quy trình bắt buộc cho mọi thay đổi mã nguồn

1. Xác định hành vi cần giữ nguyên hoặc cần thay đổi, tiêu chí chấp nhận và các trường hợp lỗi.
2. Tìm luồng thực thi, test hiện có, contract, migration và cấu hình liên quan trước khi sửa.
3. Chọn lớp đúng để thay đổi; giữ hướng phụ thuộc:

   ```text
   api -> service -> repository -> PostgreSQL
                  -> adapter -> FCM / callback
   worker -> service / repository / adapter
   ```

4. Viết hoặc cập nhật test trước hay đồng thời với code production.
5. Cài đặt thay đổi nhỏ nhất đáp ứng yêu cầu và giữ tương thích nếu không có yêu cầu breaking change.
6. Chạy test tập trung cho phần vừa sửa để có phản hồi nhanh.
7. Chạy format, lint, type-check, toàn bộ test liên quan và build trước khi hoàn tất.
8. Kiểm tra diff cuối cùng để phát hiện file sinh tự động, debug code, secret, thay đổi ngoài phạm vi hoặc contract bị đổi ngoài ý muốn.
9. Báo cáo ngắn gọn: phần đã đổi, các lệnh kiểm tra đã chạy và kết quả; nêu rõ mọi giới hạn còn lại.

## 3. Quy tắc kiến trúc

### Backend

- Router chỉ xử lý HTTP, authentication, validation và response envelope; không gọi SQLAlchemy hoặc Firebase trực tiếp.
- Business rule, idempotency và state transition nằm ở service/repository transaction.
- Repository chỉ phụ trách truy vấn, locking và persistence; không chứa HTTP/provider logic hoặc quyết định public error.
- Worker không import router. Adapter không tự thay đổi trạng thái operation.
- Dùng async xuyên suốt FastAPI, SQLAlchemy và HTTPX. Tác vụ blocking phải được cô lập và giới hạn concurrency.
- Tách Pydantic request/response schema khỏi SQLAlchemy model.
- Thay đổi database schema phải có Alembic migration; không tự tạo hoặc sửa schema khi application startup.
- Giữ delivery semantics `at-least-once`; handler, request, message và report phải idempotent.
- Locking, claim/lease, JSONB, migration và race-condition phải được kiểm thử trên PostgreSQL thật, không thay bằng SQLite.

### API và contract

- Không âm thầm đổi endpoint, status code, response envelope, error code, action mapping hoặc field đã công bố.
- Khi thay đổi contract có chủ đích, phải cập nhật đồng bộ Pydantic schema, OpenAPI artifact, ví dụ, contract test và Android DTO/consumer liên quan.
- Public và Internal Device OpenAPI phải được xuất và kiểm tra riêng.
- Mapping của toàn bộ 9 action phải rõ ràng, cố định và được test đầy đủ, bao gồm unknown action.

### Android

- FCM receiver không thực thi nghiệp vụ trực tiếp; command phải đi qua Room, dispatcher và WorkManager theo kiến trúc dự án.
- Dispatcher phải dùng cùng production path cho fake/sandbox/real adapter.
- Xử lý duplicate message, process death, retry report, permission denied và user cancellation một cách xác định.
- Không log contact, số điện thoại, vị trí, token hoặc payload nhạy cảm ở dạng raw.

## 4. Chính sách kiểm thử bắt buộc

### Mọi thay đổi hành vi phải có test

- Chức năng mới: thêm unit test cho happy path, boundary và các failure path quan trọng.
- Sửa lỗi: trước tiên thêm regression test tái hiện lỗi; test phải thất bại với hành vi cũ và thành công sau khi sửa.
- Refactor: giữ hoặc tăng mức bảo vệ của test; chứng minh hành vi quan sát được không đổi.
- Thay đổi validation/API: thêm API test và contract test cho request, response, status code và error envelope.
- Thay đổi repository/migration/concurrency: thêm PostgreSQL integration test.
- Thay đổi worker/retry/timeout/callback: test success, transient failure, permanent failure, retry exhaustion và idempotency khi áp dụng.
- Thay đổi Android state/flow: thêm JUnit/MockK/Turbine/Room/Compose test phù hợp.
- Chỉ thay đổi tài liệu hoặc comment không bắt buộc thêm unit test, nhưng vẫn phải kiểm tra link, command, ví dụ và tính nhất quán.

### Chất lượng test

- Test hành vi public hoặc observable, không khóa cứng vào chi tiết triển khai không cần thiết.
- Test phải độc lập, deterministic, có tên mô tả hành vi và không phụ thuộc thứ tự chạy.
- Không gọi network/provider thật trong unit test. Dùng fake rõ ràng và deterministic; chỉ mock tại boundary ngoài hệ thống.
- Không dùng `sleep` tùy ý để kiểm thử async/concurrency. Dùng event, fake clock hoặc cơ chế đồng bộ có timeout hữu hạn.
- Mỗi test tự tạo và dọn dữ liệu của mình. Không dùng chung mutable state giữa các test.
- Kiểm tra cả side effect mong đợi và side effect không được phép xảy ra, đặc biệt với duplicate execution/callback/report.
- Coverage là tín hiệu, không phải mục tiêu duy nhất. Code mới hoặc thay đổi phải có test đủ nhánh có ý nghĩa; không viết test vô nghĩa chỉ để tăng phần trăm.

### Phạm vi test tối thiểu theo thành phần

| Thành phần thay đổi | Kiểm tra tối thiểu |
|---|---|
| Mapping, fingerprint, state transition, retry classification, timeout, redaction | Unit test |
| Repository, migration, unique constraint, claim/lease, recovery, race | PostgreSQL integration test |
| Router/schema/auth/ownership/error envelope | API test qua HTTPX |
| Delivery, timeout hoặc callback worker | Unit/integration test với fake adapter |
| Public/Internal schema hoặc action | Contract test và kiểm tra OpenAPI diff |
| Luồng xuyên backend và Android | E2E cho kịch bản bị ảnh hưởng |
| Kotlin domain/dispatcher/flow | JUnit + MockK/Turbine |
| Room, WorkManager hoặc Compose UI | Room/WorkManager/Compose test phù hợp |

## 5. Các kịch bản quan trọng không được làm hỏng

- Request thành công: persist trước `202`, delivery, report, terminal status và callback nhất quán.
- Duplicate request trả operation cũ và không tạo lần thực thi thứ hai.
- Cùng request ID nhưng payload xung đột trả `409`.
- Duplicate FCM/message/report không gọi handler nhiều lần.
- Device unavailable và unknown action trả lỗi ổn định, đã định nghĩa.
- Operation không có report chuyển sang `timed_out` đúng hạn.
- FCM/callback lỗi tạm thời được retry; lỗi vĩnh viễn được phân loại đúng.
- Callback retry không thay đổi terminal operation state.
- Backend restart khôi phục due/leased operation; Android restart gửi pending report mà không chạy lại handler.
- Report sai device, ownership, action hoặc result schema bị từ chối.
- Log được redact và không làm lộ secret hay dữ liệu nhạy cảm.

Khi thay đổi chạm tới một kịch bản trên, phải chạy hoặc bổ sung test trực tiếp cho kịch bản đó.

## 6. Cổng chất lượng trước khi hoàn tất

Dùng lệnh được định nghĩa trong `pyproject.toml`, Gradle và CI làm nguồn chuẩn. Khi cấu trúc dự kiến đã tồn tại, bộ kiểm tra đầy đủ gồm:

### Backend

```powershell
Set-Location server
uv sync --frozen
uv run ruff format --check .
uv run ruff check .
uv run mypy src tests
uv run pytest
uv build
```

Với integration test, khởi động PostgreSQL test bằng `server/scripts/test-postgres.ps1 start` (hoặc `docker compose -f server/compose.test.yaml up -d`), đặt `TEST_DATABASE_URL`, rồi chạy test; harness tự migrate database sạch. Không được dùng database local chứa dữ liệu thật.

### Hạ tầng và contract

```powershell
Set-Location server
docker compose -f compose.yaml config
docker compose -f compose.yaml build
```

Chạy `uv run python scripts/export_openapi.py --check` trong `server/` và xác nhận `contracts/public-api.openapi.yaml` cùng `contracts/device-api.openapi.yaml` không có diff ngoài chủ đích.

### Android

Chạy Gradle wrapper thuộc repository, ưu tiên các task tương đương sau theo module thực tế:

```powershell
Set-Location mobile
.\gradlew.bat test lint assembleDebug
```

Nếu có instrumentation/Compose test và emulator hoặc device khả dụng, chạy thêm connected tests cho phần bị ảnh hưởng.

Không tự tạo tên lệnh giả nếu scaffold hoặc script chưa tồn tại. Hãy đọc cấu hình hiện có, dùng lệnh tương ứng, và ghi rõ cổng nào chưa áp dụng ở phase hiện tại.

## 7. Xử lý khi kiểm thử thất bại

- Dừng việc mở rộng phạm vi và tìm nguyên nhân gốc.
- Phân biệt lỗi do thay đổi mới, lỗi có sẵn và lỗi môi trường bằng bằng chứng từ test/log/diff.
- Sửa code production hoặc test theo contract đúng; không hạ chuẩn assertion để che lỗi.
- Chạy lại test thất bại, sau đó chạy suite liên quan để kiểm tra hồi quy.
- Không để test flaky. Nếu chưa thể loại bỏ nguyên nhân, phải báo rõ và không coi cổng chất lượng là đạt.

## 8. Definition of Done cho một thay đổi

Một thay đổi chỉ được coi là hoàn tất khi tất cả điều kiện áp dụng đều đạt:

- Tiêu chí chấp nhận và contract được đáp ứng.
- Có unit/regression/integration/contract/E2E test phù hợp với mức rủi ro.
- Test mới chứng minh được hành vi cần bảo vệ, không chỉ chạy qua code.
- Tất cả test liên quan và suite đầy đủ khả dụng đều pass.
- Format, lint, type-check và build đều pass.
- Migration được kiểm tra cả trên database sạch và đường nâng cấp khi có thay đổi schema.
- OpenAPI, tài liệu, cấu hình và ví dụ được cập nhật đồng bộ.
- Không còn debug code, TODO che khuất yêu cầu, secret hoặc thay đổi ngoài phạm vi.
- Báo cáo cuối cùng liệt kê chính xác kiểm tra đã chạy; không tuyên bố đã chạy kiểm tra thực tế chưa chạy.

