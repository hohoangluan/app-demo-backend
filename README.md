# App Communication Server prototype

Monorepo cho App Communication Server và ứng dụng Android demo. Project đang ở
**Phase P0 — Contracts and scaffold**; backend hiện cung cấp cấu hình đã validate
và health endpoints, chưa triển khai 9 Public function endpoints.

## Tài liệu nguồn

- `project_context.md`: Public/Internal contract và phạm vi nghiệp vụ.
- `architeture.md`: tech stack, package boundaries và implementation phases.
- `claude.md`: quy tắc phát triển và quality gates bắt buộc.
- `TASK_PLAN.md`: tracker bền vững để tiếp tục qua các session.
- `CONTRACT_DECISIONS.md`: các điểm contract chưa thống nhất, chưa được phép đoán.

## Chạy bằng Docker Compose

```powershell
Copy-Item .env.example .env
# Thay các giá trị replace-with-* trong .env bằng giá trị local không dùng ở production.
docker compose -f infra/compose.yaml up --build
```

Backend lắng nghe tại `http://localhost:8000`:

- `GET /health/live` trả `200` khi process đang chạy.
- `GET /health/ready` cố ý trả `503` trong P0; P1/P2 sẽ nối PostgreSQL và worker
  bootstrap trước khi endpoint này trả `200`.

Không commit `.env`, bearer token, FCM credential hoặc service-account file.

## Backend development

Yêu cầu Python 3.13.12 và uv 0.12.x:

```powershell
Set-Location apps/backend
uv sync --frozen
uv run ruff format --check .
uv run ruff check .
uv run mypy src tests
uv run pytest
uv build
```

Chạy server trực tiếp sau khi đã cấu hình các biến môi trường bắt buộc:

```powershell
uv run uvicorn app.main:app --app-dir src --host 0.0.0.0 --port 8000
```

## Cấu trúc hiện tại

```text
apps/backend/       FastAPI scaffold, config, health API và tests
contracts/          Nơi nhận OpenAPI được export từ code; chưa chứa spec giả
docs/               Demo/troubleshooting notes
infra/compose.yaml  PostgreSQL và backend container
```

Android scaffold, database models/migrations, auth, operation service và Public
API sẽ được triển khai theo thứ tự trong `TASK_PLAN.md`.
