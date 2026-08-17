# test_real — External API Client thật, dùng để kiểm thử luồng thật

Thư mục này **tách biệt hoàn toàn** khỏi `apps/backend/tests/` (bộ pytest chính thức) và
`apps/backend/scripts/demo_e2e_simulation.py` (giả lập nội bộ, không đi qua mạng thật, không
cần điện thoại thật). `test_real/run_real_e2e.py` gửi HTTP request thật qua mạng tới backend
đang chạy thật, dùng `user_id`/`device_id` mà điện thoại Android thật đã đăng ký, và chờ chính
điện thoại đó thực thi rồi báo cáo kết quả về.

## Chuẩn bị

1. Backend đang chạy (xem hướng dẫn ở README gốc hoặc `infra/compose.yaml`), đã migrate DB sạch.
2. Điện thoại Android đã cài app, đã bấm **Đăng ký** ở tab "Đăng ký" — dùng đúng Server URL trỏ
   tới backend đang chạy (ví dụ `http://<IP-máy-tính-trong-LAN>:8000` nếu test trên thiết bị
   thật qua Wi-Fi, hoặc `http://10.0.2.2:8000` nếu dùng emulator).
3. Ghi lại `user_id`/`device_id` đã đăng ký trên điện thoại — mặc định là `user-100`/`device-100`.
4. Sửa `test_real/config.local.json` (đã có sẵn, gitignored) cho khớp `base_url`, `user_id`,
   `device_id` thật của bạn. `public_bearer_token` đã được set khớp với
   `apps/backend/.env` (`PUBLIC_API_TOKEN_HASH`) trong phiên làm việc này.

## Chạy

```powershell
apps\backend\.venv\Scripts\python.exe test_real\run_real_e2e.py
```

(httpx đã có sẵn trong `.venv` của backend — dev dependency — không cần cài thêm gì.)

## Script làm gì

Gọi tuần tự cả 9 endpoint `/api/v1/service/*` (đúng ví dụ request trong `project_context.md`
mục 6.4-6.12), mỗi lần: POST → nhận `202` → poll `GET /api/v1/requests/{request_id}` tới khi
`request_state` là `succeeded`/`failed`/`timed_out` hoặc hết timeout (mặc định 90s). Sau đó chạy
thêm 2 kịch bản bắt buộc theo `CLAUDE.md` mục 5: duplicate `request_id` (cùng payload) không tạo
lần thực thi mới, và duplicate `request_id` khác payload trả `409`.

Kết quả từng action + summary được ghi vào `test_real/results/<timestamp-UTC>/` (gitignored).

## Đọc kết quả

- `PASS`: round-trip hoàn chỉnh, thiết bị báo `succeeded`.
- `PASS_WITH_ERROR`: pipeline chạy đúng (accept → deliver → report → terminal state), nhưng
  thiết bị báo `failed`/`timed_out` — đây là kết quả **hợp lệ** theo hợp đồng (ví dụ
  `CONTACT_NOT_FOUND` nếu chưa lưu liên hệ "Em iu" trong danh bạ máy demo).
- `TIMEOUT`: request vẫn `processing` sau khi hết thời gian chờ — thường do điện thoại chưa mở
  app/chưa đăng ký/mất kết nối, hoặc FCM chưa đánh thức được app (kiểm tra app có đang bị
  force-stop không — theo giới hạn nền tảng đã ghi trong `architeture.md` mục 19.1).
- `FAIL`: vi phạm hợp đồng thật sự (không trả `202`, không đúng `409`, v.v).
