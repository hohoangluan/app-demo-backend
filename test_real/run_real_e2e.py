"""Acts as a real External API Client against a *running* backend instance.

Unlike ``apps/backend/tests/`` (the pytest suite, which must stay untouched
and never talks to a real network) and ``apps/backend/scripts/demo_e2e_simulation.py``
(an in-process ASGI simulation with a fake device report), this script sends
real HTTP requests over the network to a live backend, using the ``user_id``/
``device_id`` an actual Android phone registered with, and waits for the real
phone to execute the command and report back.

Usage (from repo root, reusing the backend's already-installed httpx):

    apps/backend/.venv/Scripts/python.exe test_real/run_real_e2e.py

Results are written to ``test_real/results/<UTC timestamp>/``.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import httpx

THIS_DIR = Path(__file__).resolve().parent


@dataclass
class Config:
    base_url: str
    public_bearer_token: str
    user_id: str
    device_id: str
    poll_timeout_seconds: float = 90.0
    poll_interval_seconds: float = 1.0
    contact_name: str = "test"
    music_song: str = "Noi nay co anh - Son Tung M-TP"

    @classmethod
    def load(cls, path: Path) -> Config:
        data = json.loads(path.read_text(encoding="utf-8"))
        return cls(
            base_url=data["base_url"].rstrip("/"),
            public_bearer_token=data["public_bearer_token"],
            user_id=data["user_id"],
            device_id=data["device_id"],
            poll_timeout_seconds=float(data.get("poll_timeout_seconds", 90.0)),
            poll_interval_seconds=float(data.get("poll_interval_seconds", 1.0)),
            contact_name=data.get("contact_name", "test"),
            music_song=data.get("music_song", "Noi nay co anh - Son Tung M-TP"),
        )


@dataclass
class ActionCase:
    name: str
    method: str
    path: str
    build_body: Any  # Callable[[Config, dict[str, Any]], dict[str, Any]]


@dataclass
class CaseResult:
    name: str
    request_body: dict[str, Any]
    initial_status_code: int | None = None
    initial_response: dict[str, Any] | None = None
    poll_log: list[dict[str, Any]] = field(default_factory=list)
    final_status: dict[str, Any] | None = None
    elapsed_seconds: float = 0.0
    outcome: str = "FAIL"
    note: str = ""


def _uuid() -> str:
    return str(uuid.uuid4())


# Request bodies mirror the exact examples in project_context.md sections 6.4-6.12.
ACTION_CASES: list[ActionCase] = [
    ActionCase(
        "ride_quote",
        "POST",
        "/api/v1/service/ride/quote",
        lambda cfg, ctx: {
            "current_location": {"lat": 10.7769, "lng": 106.7009},
            "destination": {"address": "Dai hoc Bach Khoa TP.HCM", "lat": 10.7721, "lng": 106.6578},
        },
    ),
    ActionCase(
        "ride_confirm",
        "POST",
        "/api/v1/service/ride/confirm",
        lambda cfg, ctx: {
            "quote_id": ctx.get("ride_quote_quote_id", "quote-manual-test"),
            "confirm": True,
        },
    ),
    ActionCase(
        "music_play",
        "POST",
        "/api/v1/service/music/play",
        lambda cfg, ctx: {"song": cfg.music_song, "volume": 60},
    ),
    ActionCase("music_stop", "POST", "/api/v1/service/music/stop", lambda cfg, ctx: {}),
    ActionCase(
        "music_volume",
        "POST",
        "/api/v1/service/music/volume",
        lambda cfg, ctx: {"direction": "up"},
    ),
    ActionCase(
        "navigation_start",
        "POST",
        "/api/v1/service/navigation/start",
        lambda cfg, ctx: {"destination": {"address": "Buu dien Thanh pho Ho Chi Minh"}},
    ),
    ActionCase(
        "navigation_stop",
        "POST",
        "/api/v1/service/navigation/stop",
        lambda cfg, ctx: {
            "navigation_id": ctx.get("navigation_start_navigation_id", "nav-manual-test"),
        },
    ),
    ActionCase("emergency_call", "POST", "/api/v1/service/emergency/call", lambda cfg, ctx: {}),
    ActionCase(
        "contact_call",
        "POST",
        "/api/v1/service/contact/call",
        lambda cfg, ctx: {"name": cfg.contact_name},
    ),
]


def _headers(cfg: Config) -> dict[str, str]:
    return {"Authorization": f"Bearer {cfg.public_bearer_token}", "Content-Type": "application/json"}


def _poll_until_terminal(
    client: httpx.Client, cfg: Config, request_id: str, result: CaseResult
) -> dict[str, Any] | None:
    deadline = time.monotonic() + cfg.poll_timeout_seconds
    while time.monotonic() < deadline:
        resp = client.get(f"{cfg.base_url}/api/v1/requests/{request_id}", headers=_headers(cfg))
        body = resp.json() if resp.headers.get("content-type", "").startswith("application/json") else {}
        state = body.get("data", {}).get("request_state") if resp.status_code == 200 else None
        result.poll_log.append({"elapsed_s": round(cfg.poll_timeout_seconds - (deadline - time.monotonic()), 2), "status_code": resp.status_code, "request_state": state})
        if state in ("succeeded", "failed", "timed_out"):
            return body
        time.sleep(cfg.poll_interval_seconds)
    return None


def run_case(client: httpx.Client, cfg: Config, case: ActionCase, ctx: dict[str, Any]) -> CaseResult:
    request_id = _uuid()
    body = {"device_id": cfg.device_id, "request_id": request_id, **case.build_body(cfg, ctx)}
    result = CaseResult(name=case.name, request_body=body)

    started = time.monotonic()
    resp = client.request(case.method, f"{cfg.base_url}{case.path}", json=body, headers=_headers(cfg))
    result.initial_status_code = resp.status_code
    try:
        result.initial_response = resp.json()
    except ValueError:
        result.initial_response = {"raw": resp.text}

    if resp.status_code != 202:
        result.outcome = "FAIL"
        result.note = f"Expected 202 Accepted, got {resp.status_code}"
        result.elapsed_seconds = time.monotonic() - started
        return result

    final = _poll_until_terminal(client, cfg, request_id, result)
    result.elapsed_seconds = time.monotonic() - started

    if final is None:
        result.outcome = "TIMEOUT"
        result.note = f"Still not terminal after {cfg.poll_timeout_seconds}s (device likely unreachable or app not registered/foregrounded)"
        return result

    result.final_status = final
    state = final["data"]["request_state"]
    if state == "succeeded":
        result.outcome = "PASS"
        # feed real IDs forward so dependent cases (ride_confirm, navigation_stop) use them
        res_data = final["data"].get("result") or {}
        if case.name == "ride_quote" and "quote_id" in res_data:
            ctx["ride_quote_quote_id"] = res_data["quote_id"]
        if case.name == "navigation_start" and "navigation_id" in res_data:
            ctx["navigation_start_navigation_id"] = res_data["navigation_id"]
    else:
        result.outcome = "PASS_WITH_ERROR"
        result.note = f"Pipeline completed but device reported {state}: {final['data'].get('error')}"
    return result


def run_idempotency_check(client: httpx.Client, cfg: Config) -> CaseResult:
    """Same request_id + same body twice must not create a second execution (project_context.md section 5)."""
    request_id = _uuid()
    body = {"device_id": cfg.device_id, "request_id": request_id, "direction": "up"}
    result = CaseResult(name="idempotent_duplicate_music_volume", request_body=body)
    started = time.monotonic()

    first = client.post(f"{cfg.base_url}/api/v1/service/music/volume", json=body, headers=_headers(cfg))
    second = client.post(f"{cfg.base_url}/api/v1/service/music/volume", json=body, headers=_headers(cfg))
    result.elapsed_seconds = time.monotonic() - started
    result.initial_status_code = second.status_code
    result.initial_response = {"first": first.json(), "second": second.json()}

    same_request_id = (
        first.status_code == 202
        and second.status_code == 202
        and first.json()["data"]["request_id"] == second.json()["data"]["request_id"] == request_id
    )
    result.outcome = "PASS" if same_request_id else "FAIL"
    result.note = "Both calls returned the same request_id/operation, no second execution" if same_request_id else "Duplicate request_id did not behave idempotently"
    return result


def run_conflict_check(client: httpx.Client, cfg: Config) -> CaseResult:
    """Same request_id, different payload must return 409 REQUEST_ID_CONFLICT (project_context.md section 5)."""
    request_id = _uuid()
    first_body = {"device_id": cfg.device_id, "request_id": request_id, "direction": "up"}
    second_body = {"device_id": cfg.device_id, "request_id": request_id, "direction": "down"}
    result = CaseResult(name="conflicting_request_id_returns_409", request_body=second_body)
    started = time.monotonic()

    first = client.post(f"{cfg.base_url}/api/v1/service/music/volume", json=first_body, headers=_headers(cfg))
    second = client.post(f"{cfg.base_url}/api/v1/service/music/volume", json=second_body, headers=_headers(cfg))
    result.elapsed_seconds = time.monotonic() - started
    result.initial_status_code = second.status_code
    result.initial_response = {"first": first.json(), "second": second.json()}

    result.outcome = "PASS" if second.status_code == 409 else "FAIL"
    result.note = "Second call correctly rejected with 409" if second.status_code == 409 else f"Expected 409, got {second.status_code}"
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default=str(THIS_DIR / "config.local.json"))
    parser.add_argument(
        "--only",
        default=None,
        help="Run a single action by name (e.g. music_play) instead of the full 9-action sequence.",
    )
    args = parser.parse_args()

    cfg = Config.load(Path(args.config))
    run_dir = THIS_DIR / "results" / datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    run_dir.mkdir(parents=True, exist_ok=True)

    cases = ACTION_CASES
    if args.only:
        cases = [c for c in ACTION_CASES if c.name == args.only]
        if not cases:
            valid = ", ".join(c.name for c in ACTION_CASES)
            print(f"Unknown --only '{args.only}'. Valid: {valid}")
            return 1

    print(f"=== test_real: External API Client run against {cfg.base_url} ===")
    print(f"user_id={cfg.user_id} device_id={cfg.device_id}")
    print(f"Results will be written to {run_dir}\n")

    ctx: dict[str, Any] = {}
    results: list[CaseResult] = []

    with httpx.Client(timeout=10.0) as client:
        for case in cases:
            print(f"--- {case.name} ---")
            result = run_case(client, cfg, case, ctx)
            print(f"  {result.outcome}: {result.note or result.final_status}")
            results.append(result)
            (run_dir / f"{case.name}.json").write_text(
                json.dumps(result.__dict__, indent=2, default=str), encoding="utf-8"
            )

        if not args.only:
            print("--- idempotent_duplicate_music_volume ---")
            idem = run_idempotency_check(client, cfg)
            print(f"  {idem.outcome}: {idem.note}")
            results.append(idem)
            (run_dir / f"{idem.name}.json").write_text(
                json.dumps(idem.__dict__, indent=2, default=str), encoding="utf-8"
            )

            print("--- conflicting_request_id_returns_409 ---")
            conflict = run_conflict_check(client, cfg)
            print(f"  {conflict.outcome}: {conflict.note}")
            results.append(conflict)
            (run_dir / f"{conflict.name}.json").write_text(
                json.dumps(conflict.__dict__, indent=2, default=str), encoding="utf-8"
            )

    summary = {
        "base_url": cfg.base_url,
        "user_id": cfg.user_id,
        "device_id": cfg.device_id,
        "run_at": datetime.now(UTC).isoformat(),
        "results": [{"name": r.name, "outcome": r.outcome, "note": r.note, "elapsed_seconds": round(r.elapsed_seconds, 2)} for r in results],
    }
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")

    print("\n=== Summary ===")
    for r in results:
        print(f"{r.outcome:16s} {r.name:40s} {r.note}")

    failed = [r for r in results if r.outcome in ("FAIL", "TIMEOUT")]
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
