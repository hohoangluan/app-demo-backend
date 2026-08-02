# P1 API Design Plan

Kế hoạch triển khai độc lập cho Public API foundation của P1, tập trung vào
Bearer auth, error handling, idempotency, Status API và vertical slice
`music_volume`. File này không thay đổi contract và không triển khai backend.

Nguồn bắt buộc:

- `project_context.md` mục 6, 7 và 12.
- `architeture.md` mục 5, 8, 13-16.
- `claude.md` mục 2-6.
- [`CONTRACT_DECISIONS.md`](../CONTRACT_DECISIONS.md).
- [`contracts/contract-matrix.md`](../contracts/contract-matrix.md).

Các blocker liên quan trực tiếp:

- `D-03`: `TARGET_UNAVAILABLE` là synchronous 503 hay async terminal failure.
- `D-06`: Status API trả 403 hay 404 khi request thuộc client khác.
- `D-07`: extra fields, null, string normalization/length và validation details.
- `D-10`: `accepted_at` của idempotent duplicate là timestamp gốc hay retry.

Recommended default trong decision register không phải quyết định. Những nhánh
bị block bên dưới không được âm thầm chọn trong code hoặc contract test.

## Public API invariants

- Function base path: `/api/v1/service`.
- Public auth: `Authorization: Bearer <client_token>`.
- POST content type: `application/json`.
- Mọi function request có `user_id: string` và client-generated
  `request_id: UUID`.
- Mọi accepted function request được persist trước `HTTP 202`.
- Accepted envelope:

  ```json
  {
    "status": "ok",
    "data": {
      "request_id": "550e8400-e29b-41d4-a716-446655440000",
      "operation": "music_volume",
      "request_state": "processing",
      "status_url": "/api/v1/requests/550e8400-e29b-41d4-a716-446655440000",
      "accepted_at": "2026-08-02T10:00:00Z"
    }
  }
  ```

- Router chỉ làm HTTP/auth/validation/envelope. Mapping, fingerprint,
  idempotency và state rules ở service/repository transaction.
- Public response không expose device ID/token, action command, delivery state,
  provider response hay stack trace.

## Bearer authentication

### Provisioning format

Prototype token là opaque token sinh từ tối thiểu 32 random bytes (256-bit
entropy), biểu diễn base64url không padding. Raw token chỉ được đưa cho client
qua provisioning ngoài repository.

`PUBLIC_API_TOKEN_HASH` chứa lowercase hex của digest 32 bytes:

```text
HMAC-SHA256(
  key = UTF-8 bytes của raw token,
  message = "app-demo-auth/v1/public-api"
)
```

Domain-separated HMAC cho phép Public và Device token có digest khác ngay cả khi
misconfiguration dùng cùng raw token. Digest này không thay thế entropy của raw
token; token yếu vẫn bị offline guessing. Không commit raw token hoặc digest thật.

Config validation lúc startup:

1. `PUBLIC_API_TOKEN_HASH` bắt buộc.
2. Phải là đúng 64 lowercase hexadecimal characters.
3. Decode thành đúng 32 bytes; invalid config làm startup fail trước database và
   worker.
4. `PUBLIC_API_CLIENT_ID` là non-empty configured identifier.
5. `PUBLIC_API_SCOPES` parse thành immutable set; unknown/duplicate/empty item làm
   startup fail.

Scope names cho P1:

| Scope | Route requirement |
|---|---|
| `service:execute` | Mọi `POST /api/v1/service/*` |
| `requests:read` | `GET /api/v1/requests/{request_id}` |

### Request verification

FastAPI dependency dùng `HTTPBearer(auto_error=False)` và thực hiện đúng thứ tự:

1. Missing header, scheme khác Bearer, empty credential hoặc malformed header:
   trả `401 UNAUTHORIZED` với `WWW-Authenticate: Bearer`.
2. Không trim, lowercase hay log credential. Bearer token được xử lý opaque.
3. Tính candidate HMAC một lần theo algorithm/domain ở trên.
4. Decode configured digest một lần ở startup; request path chỉ gọi
   `hmac.compare_digest(candidate_digest, configured_digest_bytes)` trên hai byte
   arrays cùng length.
5. Compare false: cùng `401` response như missing/malformed; không tiết lộ token
   có tồn tại hay gần đúng.
6. Compare true: tạo immutable `ClientPrincipal` từ config.
7. Route dependency kiểm required scope. Thiếu scope trả `403 FORBIDDEN`; không
   truy cập repository.

```python
@dataclass(frozen=True, slots=True)
class ClientPrincipal:
    client_id: str
    scopes: frozenset[str]
```

Không so sánh raw token, không dùng `==` cho digest, không đưa Authorization vào
structured log/exception context. Unit test dùng token giả sinh runtime, không
đưa credential cố định vào fixture/snapshot.

## Public error envelope

Mọi lỗi đã định nghĩa trả cùng shape:

```json
{
  "status": "error",
  "error": {
    "code": "INVALID_REQUEST",
    "message": "Request validation failed",
    "details": {}
  }
}
```

| HTTP | Stable code | Source |
|---:|---|---|
| 400 | `INVALID_REQUEST` | Schema/JSON/content validation |
| 401 | `UNAUTHORIZED` | Missing/malformed/invalid Bearer token |
| 403 | `FORBIDDEN` | Authenticated principal lacks scope; ownership branch waits for `D-06` |
| 404 | `REQUEST_NOT_FOUND` | Request absent; wrong-owner branch waits for `D-06` |
| 409 | `REQUEST_ID_CONFLICT` | Existing request ID has different fingerprint/client |
| 500 | `INTERNAL_ERROR` | Unexpected server failure |
| 503 | `TARGET_UNAVAILABLE` | Exact sync/async use waits for `D-03` |

Schema models:

```python
class ErrorData(BaseModel):
    code: str
    message: str
    details: dict[str, JsonValue]

class ErrorResponse(BaseModel):
    status: Literal["error"] = "error"
    error: ErrorData
```

`details` structure và exact validation message bị block bởi `D-07`. Trước khi
`D-07` được chốt, API tests chỉ khóa status, envelope keys, stable code, JSON
content type và absence of raw input; không khóa một field-details format mới.

### Exception taxonomy

Application service trả/raise typed domain errors, không raise `HTTPException`:

```text
InvalidRequest
Unauthenticated
Forbidden
RequestAbsent
RequestOwnedByAnotherClient
RequestIdConflict
TargetUnavailable
UnexpectedFailure
```

Router/application exception mapper là nơi duy nhất chuyển domain error thành
HTTP status và error envelope. `RequestOwnedByAnotherClient` giữ riêng cho tới
khi `D-06` được chốt.

### Validation handler

Register handlers theo thứ tự/scope sau:

1. `RequestValidationError` (gồm invalid UUID, bounds, XOR, malformed JSON body)
   -> `400 INVALID_REQUEST`.
2. Public domain errors -> mapping table ở trên.
3. Unexpected `Exception` -> log sanitized correlation context và trả
   `500 INTERNAL_ERROR`; response không chứa exception class/message/trace.

Contract không công bố FastAPI default `422`; Public routes phải override OpenAPI
responses để không sinh 422 contract ngoài ý muốn. Missing/wrong JSON content
type cũng dùng `400 INVALID_REQUEST`, nhưng exact details vẫn chờ `D-07`.

Log validation error chỉ chứa route template, request ID nếu đã parse an toàn,
client ID, stable code và field paths; không log raw body, user ID, token, contact,
location hoặc Pydantic `input` values.

## Fingerprint canonicalization

### Inputs

Fingerprint identity giữ đúng blueprint:

```text
client_id + HTTP method + route template + canonical validated body
```

Không dùng host, query string, raw URL, header order, raw JSON whitespace hoặc
client-provided key order.

Canonicalization sau Pydantic validation và trước repository call:

1. `method = method.upper()`.
2. `path` là fixed route template, ví dụ
   `/api/v1/service/music/volume`, không phải raw path alias.
3. `body_object = request_model.model_dump(mode="json", exclude_none=True)`.
4. Không tự trim/case-fold/Unicode-normalize business strings; hành vi đó phụ
   thuộc schema và `D-07`.
5. Serialize một identity object bằng UTF-8 JSON với `sort_keys=True`,
   `separators=(",", ":")`, `ensure_ascii=False`, `allow_nan=False`:

   ```json
   {
     "body": {},
     "client_id": "demo-client",
     "method": "POST",
     "path": "/api/v1/service/music/volume"
   }
   ```

6. SHA-256 canonical bytes; persist lowercase 64-char hex.

`request_id` và `user_id` nằm trong validated body và vì vậy nằm trong
fingerprint. Pydantic coercion biến semantically accepted input thành canonical
type trước khi hash. Extra-field và explicit-null equivalence phụ thuộc `D-07`;
fingerprint golden tests cho các case đó không được khóa trước quyết định.

### Idempotency behavior

Trong một PostgreSQL transaction:

1. Build operation candidate với fingerprint, mapping và timeout cố định.
2. Repository chạy atomic
   `INSERT ... ON CONFLICT (request_id) DO NOTHING RETURNING ...`.
3. Inserted: commit, sau đó router trả 202.
4. Conflict: repository đọc existing operation trong cùng transaction.
5. Existing cùng `client_id` và fingerprint: return existing; không update row,
   không tăng delivery attempts, không enqueue lần hai.
6. Fingerprint hoặc client khác: service trả `RequestIdConflict` -> HTTP 409.
7. Không gọi device resolver, adapter hoặc network trong transaction.

Storage luôn giữ original `created_at`. Trường `accepted_at` của duplicate
response phụ thuộc `D-10`; response assembler phải nhận policy/decision thay vì
vô tình dùng request-time clock.

Target resolution trước/sau 202 phụ thuộc `D-03`. Operation service giữ hai
outcome riêng (`Accepted`, `TargetUnavailable`) nhưng route flow không chọn nhánh
resolver cho tới khi decision được chốt.

## Interfaces

Các interface dưới đây là boundary, không phải ORM/HTTP model.

```python
@dataclass(frozen=True, slots=True)
class NewOperation:
    request_id: UUID
    client_id: str
    user_id: str
    operation: OperationName
    action: ActionName
    params: dict[str, JsonValue]
    request_fingerprint: str
    expires_at: datetime
    callback_required: bool

@dataclass(frozen=True, slots=True)
class OperationRecord:
    request_id: UUID
    client_id: str
    user_id: str
    operation: OperationName
    action: ActionName
    request_fingerprint: str
    request_state: RequestState
    result: dict[str, JsonValue] | None
    error: ErrorData | None
    created_at: datetime
    updated_at: datetime

@dataclass(frozen=True, slots=True)
class InsertOrExisting:
    operation: OperationRecord
    inserted: bool

class OperationRepository(Protocol):
    async def insert_or_get(
        self, candidate: NewOperation
    ) -> InsertOrExisting: ...

    async def get_by_request_id(
        self, request_id: UUID
    ) -> OperationRecord | None: ...

class OperationUnitOfWork(Protocol):
    operations: OperationRepository
    async def __aenter__(self) -> Self: ...
    async def __aexit__(self, exc_type, exc, tb) -> None: ...
    async def commit(self) -> None: ...
```

Repository không commit ngầm, không biết FastAPI response/error và không map
endpoint/action. SQLAlchemy implementation nằm dưới interface và dùng real
PostgreSQL cho race tests.

```python
@dataclass(frozen=True, slots=True)
class AcceptOperation:
    principal: ClientPrincipal
    method: str
    route_template: str
    request: ServiceRequest
    operation: OperationName
    action: ActionName
    timeout: timedelta

class OperationService:
    async def accept(self, command: AcceptOperation) -> OperationRecord: ...

    async def get_status(
        self, principal: ClientPrincipal, request_id: UUID
    ) -> StatusLookup: ...
```

`StatusLookup` phải giữ ba outcome quan sát được ở service layer:

```text
Found(operation)
Absent
OwnedByAnotherClient
```

Router chỉ map `OwnedByAnotherClient` sau `D-06`. Điều này tránh vô tình chốt
403/404 trong repository query.

## Status API

```http
GET /api/v1/requests/{request_id}
Authorization: Bearer <client_token>
```

Route yêu cầu scope `requests:read`. Success luôn `HTTP 200`:

```json
{
  "status": "ok",
  "data": {
    "request_id": "550e8400-e29b-41d4-a716-446655440004",
    "operation": "music_volume",
    "request_state": "processing",
    "result": null,
    "error": null,
    "created_at": "2026-08-02T10:00:00Z",
    "updated_at": "2026-08-02T10:00:00Z"
  }
}
```

State invariants:

- `processing`: result/error SQL null.
- `succeeded`: action-specific result object, error null.
- `failed`/`timed_out`: result null, error `{code,message,details}`.
- Time output ISO 8601 UTC.
- No delivery/callback/device fields.

Repository lookup may load row by request ID, nhưng service luôn compare
`operation.client_id` với principal. Exact wrong-owner response remains blocked
by `D-06`. Missing request is `404 REQUEST_NOT_FOUND`.

## `music_volume` vertical slice

### Fixed mapping

| Property | Value |
|---|---|
| Method/path | `POST /api/v1/service/music/volume` |
| Required scope | `service:execute` |
| Operation | `music_volume` |
| Action | `music_volume` |
| Timeout | 30 seconds |
| Initial public state | `processing` |
| Initial delivery state | `received` |

### Request model

Known contract:

```json
{
  "user_id": "user-123",
  "request_id": "550e8400-e29b-41d4-a716-446655440004",
  "direction": "up"
}
```

hoặc:

```json
{
  "user_id": "user-123",
  "request_id": "550e8400-e29b-41d4-a716-446655440004",
  "level": 70
}
```

Known validation được phép khóa ngay:

- `request_id` là UUID.
- Chính xác một trong `direction` hoặc `level`.
- `direction` là literal `up` hoặc `down`.
- `level` là integer trong `0..100`.
- `user_id` là JSON string.

Chưa được khóa trước `D-07`: empty/whitespace `user_id`, integer coercion từ
string/float/bool, explicit null, extra fields, trim/case normalization và exact
validation details.

Sau validation:

- Fingerprint body vẫn gồm common fields và selected volume field.
- Persist `params` chỉ gồm business params: `{"direction":"up"}` hoặc
  `{"level":70}`; không lặp `user_id`, `request_id`, token hoặc client ID trong
  command params.
- Không gọi volume capability trên server.
- Endpoint chỉ tạo/reuse operation. Device delivery/handler/report thuộc P2/P3.
- Việc check device trước 202 bị block bởi `D-03`.

### Accepted response

HTTP 202 dùng common envelope, `operation="music_volume"`, state `processing` và
relative status URL. `accepted_at` của new request là persisted `created_at`;
duplicate behavior chờ `D-10`.

Terminal success result do Android report ở phase sau:

```json
{
  "volume_state": "changed",
  "level": 70
}
```

Async action errors: `INVALID_VOLUME`, `VOLUME_CHANGE_FAILED`. P1 không fabricate
terminal result/error khi chưa có device report/worker.

## Acceptance criteria

- Valid public token + required scope + valid volume request creates exactly one
  PostgreSQL operation before 202.
- Persisted mapping/action/timeout/params/fingerprint đúng bảng trên.
- Response envelope và status URL đúng contract; không có internal fields.
- Same request ID + same canonical identity returns same operation and creates no
  second row/delivery.
- Same request ID + different direction/level/user/client returns 409 envelope.
- Status owner retrieves processing representation with null result/error.
- Missing status request returns 404 envelope.
- Invalid/missing auth never calls service/repository.
- Validation failure is 400, never FastAPI default 422, and never echoes raw
  input/token.
- Wrong-owner assertion waits for `D-06`; target-unavailable assertion waits for
  `D-03`; duplicate `accepted_at` equality waits for `D-10`; ambiguous validation
  cases wait for `D-07`.

## API test matrix

API tests chạy qua HTTPX `AsyncClient` + ASGI transport; repository/UoW fake phải
deterministic và chỉ mock tại persistence boundary.

| ID | Scenario | Expected assertion | Blocker |
|---|---|---|---|
| `API-01` | Missing Authorization | 401 error envelope, Bearer challenge, no repository call | None |
| `API-02` | Wrong scheme / invalid token | Same observable response as invalid auth | None |
| `API-03` | Valid token missing execute scope | 403 error envelope, no repository call | None |
| `API-04` | Valid level 0 and 100 | 202, operation `music_volume`, processing | None |
| `API-05` | Valid direction up/down | 202 and correct business params | None |
| `API-06` | Both/neither direction and level | 400 `INVALID_REQUEST`, not 422 | None |
| `API-07` | Invalid direction / level -1 or 101 | 400 envelope | None |
| `API-08` | Invalid UUID / malformed JSON / wrong content type | 400 envelope; raw input absent | `D-07` for details only |
| `API-09` | Empty user, extra key, explicit null, coercible scalar | Keep test pending/parameterized without expected branch | `D-07` |
| `API-10` | New accepted request | Service called once; exact 202 envelope | None |
| `API-11` | Idempotent duplicate | Same operation, no second insert/delivery | `D-10` only for accepted_at |
| `API-12` | Conflicting request ID | 409 `REQUEST_ID_CONFLICT` | None |
| `API-13` | Status owner / absent | 200 processing representation / 404 envelope | None |
| `API-14` | Status wrong owner | Service returns distinct ownership outcome | `D-06` for HTTP/code |
| `API-15` | Device unavailable at accept time | Do not lock sync/async behavior | `D-03` |
| `API-16` | Unexpected service exception | 500 generic envelope; no trace/detail leak | None |
| `API-17` | OpenAPI for volume/status | 202/200/error models present; no default 422 | `D-07` for ambiguous schema behavior |

## PostgreSQL integration test matrix

Không dùng SQLite. Mỗi test migrate database sạch và dùng independent sessions.

| ID | Scenario | Expected assertion | Blocker |
|---|---|---|---|
| `INT-API-01` | POST valid volume | Row committed before response; exact mapping/state/JSONB/expiry | None |
| `INT-API-02` | GET status after POST | Same request/client and persisted timestamps represented | None |
| `INT-API-03` | Sequential same ID/same payload | One row; existing operation returned untouched | `D-10` response timestamp only |
| `INT-API-04` | Sequential same ID/different payload | One row; second response 409 | None |
| `INT-API-05` | Two concurrent identical POSTs | One insert winner; both resolve to same row; no 500 | None |
| `INT-API-06` | Two concurrent conflicting POSTs | One 202 and one 409; one row | None |
| `INT-API-07` | Same request ID from different client | Existing row preserved; conflict outcome | None |
| `INT-API-08` | Transaction commit failure | No 202; 500 generic envelope; no partial row | None |
| `INT-API-09` | Status wrong client | Data never returned to wrong principal | `D-06` response mapping |
| `INT-API-10` | Fingerprint golden cases | Key order/whitespace do not change digest after validation | `D-07` for null/extra/coercion cases |
| `INT-API-11` | JSONB round trip | Direction/level params retain canonical JSON type | None |
| `INT-API-12` | Expiry | `expires_at` equals service clock + 30 seconds | None |

Concurrency tests dùng event/barrier và separate DB connections với finite
timeouts; không dùng arbitrary sleep. Test database guard phải từ chối DSN không
được đánh dấu test.

## Small implementation tasks

| Task | Scope | Output | Verification |
|---|---|---|---|
| `P1-API-01` | Auth digest utility | Domain-separated HMAC, config parser, constant-time verifier | Unit tests malformed config, valid/invalid digest, no secret logging |
| `P1-API-02` | Principal/scope dependency | `ClientPrincipal`, Bearer dependency, scope guard | `API-01` to `API-03` |
| `P1-API-03` | Common response schemas | Success/error/status Pydantic models, UTC serializer | Schema/unit tests; no ORM coupling |
| `P1-API-04` | Error mapper | Domain errors + validation/unexpected handlers | `API-06` to `API-09`, `API-16` |
| `P1-API-05` | Mapping/fingerprint | Fixed operation map and canonical hash utility | 9-action mapping unit test + fingerprint golden tests |
| `P1-API-06` | Repository/UoW boundary | Protocols and SQLAlchemy `insert_or_get`/status lookup | Focused PostgreSQL tests `INT-API-03` to `INT-API-11` |
| `P1-API-07` | Operation service | Accept/idempotency/ownership outcomes; no HTTP imports | Service unit tests for inserted/existing/conflict/ownership |
| `P1-API-08` | Status route | Auth, UUID path, response assembler, error mapping branch | `API-13`, `API-14`, `INT-API-02`, `INT-API-09` |
| `P1-API-09` | Music volume schema/route | Known XOR/bounds, fixed mapping, 202 envelope | `API-04` to `API-12`, `INT-API-01`, `INT-API-12` |
| `P1-API-10` | Contract/OpenAPI | Public-only export and contract comparison | `API-17`; no Internal routes in artifact |
| `P1-API-11` | Quality gate | Focused/full tests, Ruff, mypy, build, diff review | Commands from actual project config pass |

Recommended execution order:

```text
01 -> 02
03 -> 04
05 -> 06 -> 07 -> 08/09 -> 10 -> 11
```

Tasks `P1-API-08` và `P1-API-09` có thể scaffold distinct ownership/target
outcomes nhưng không finalize blocked HTTP behavior trước `D-06`/`D-03`.

## Exit criteria

- Auth digest config fail-fast; raw token never persisted/logged; comparison is
  constant-time over equal-length digests.
- Principal and scopes flow through typed dependencies; operation stores exact
  authenticated `client_id`.
- Public errors always use stable envelope; validation is 400, not 422.
- Canonical fingerprint is deterministic and concurrent idempotency creates one
  operation only.
- Status API enforces ownership without exposing wrong-client data.
- `music_volume` validates all contract-known cases, persists before 202 and
  never executes device capability on server.
- API tests and real PostgreSQL integration tests pass for all unblocked cases.
- Public OpenAPI contains only intended Public routes/models and has no silent
  contract change.
- `D-03`, `D-06`, `D-07`, `D-10` remain explicit blockers until Decisions log
  records approval.
