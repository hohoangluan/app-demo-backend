# Your Eyes — App Communication Server và ứng dụng điện thoại

Server kính (External API Client) gọi một API cố định; server này đẩy lệnh tới điện thoại
của người dùng qua FCM, điện thoại thực thi rồi báo kết quả, server công bố kết quả qua
status API hoặc callback.

```text
server kính ──HTTP──► server/ ──FCM──► mobile/ ──HTTP report──► server/ ──status/callback──► server kính
```

| Thư mục | Nội dung | Bắt đầu từ |
|---|---|---|
| [`server/`](server/) | FastAPI + PostgreSQL + workers | [`server/README.md`](server/README.md) |
| [`mobile/`](mobile/) | App Android Kotlin/Compose | [`mobile/README.md`](mobile/README.md) |
| [`contracts/`](contracts/) | OpenAPI sinh từ code server | [`contracts/README.md`](contracts/README.md) |
| [`docs/`](docs/) | Hợp đồng, kiến trúc, API cho server kính, xử lý sự cố | [`docs/architecture.md`](docs/architecture.md) |

Tài liệu:

- [`docs/project-context.md`](docs/project-context.md) — hợp đồng nghiệp vụ (nguồn chuẩn).
- [`docs/architecture.md`](docs/architecture.md) — module, luồng request, trạng thái.
- [`docs/external-client-api.md`](docs/external-client-api.md) — hướng dẫn tích hợp cho server kính.
- [`docs/troubleshooting.md`](docs/troubleshooting.md) — xử lý sự cố.
- [`claude.md`](claude.md) — quy tắc phát triển và cổng chất lượng bắt buộc.

Không commit `.env`, `local.properties`, `google-services.json`, file service-account Firebase hay token thô.
