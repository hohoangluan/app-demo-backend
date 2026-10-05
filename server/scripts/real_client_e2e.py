"""Drive a running server exactly like the External API Client (glasses server) does.

Each case POSTs one ``/api/v1/service/*`` request for a linked glasses
``device_id``, expects ``202``, then polls ``GET /api/v1/requests/{id}`` until
the phone's report makes it terminal. Two extra cases check that a replayed
``request_id`` is idempotent and that a reused one with another body gets 409.

    python scripts/real_client_e2e.py --config scripts/real_client.local.json
    python scripts/real_client_e2e.py --only music_play navigation_start

``emergency_call`` really calls and texts the configured emergency contact, so
it only runs when named with ``--only``.

Outcomes: PASS (succeeded), DEVICE_ERROR (pipeline worked, the phone reported
failed/timed_out — e.g. CONTACT_NOT_FOUND), TIMEOUT (still processing),
FAIL (contract violation such as a non-202 accept).
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx

TERMINAL_STATES = {"succeeded", "failed", "timed_out"}
OPT_IN_ONLY = {"emergency_call"}


@dataclass(frozen=True)
class Config:
    """Connection settings for one run."""

    base_url: str
    public_bearer_token: str
    device_id: str
    poll_timeout_seconds: float = 90.0
    contact_name: str = "test"
    music_song: str = "Noi nay co anh - Son Tung M-TP"

    @classmethod
    def load(cls, path: Path) -> Config:
        """Read the JSON config file (see ``real_client.example.json``)."""
        data = json.loads(path.read_text(encoding="utf-8"))
        return cls(
            base_url=data["base_url"].rstrip("/"),
            public_bearer_token=data["public_bearer_token"],
            device_id=data["device_id"],
            poll_timeout_seconds=float(data.get("poll_timeout_seconds", 90.0)),
            contact_name=data.get("contact_name", cls.contact_name),
            music_song=data.get("music_song", cls.music_song),
        )


def case_bodies(cfg: Config, ctx: dict[str, str]) -> dict[str, tuple[str, dict[str, Any]]]:
    """Return ``name -> (path, business body)``; later cases reuse ids from earlier ones."""
    return {
        "capabilities_get": ("capabilities", {}),
        "location_get": ("location/get", {}),
        "ride_quote": (
            "ride/quote",
            {
                "current_location": {"lat": 10.7769, "lng": 106.7009},
                "destination": {"address": "Dai hoc Bach Khoa TP.HCM"},
            },
        ),
        "ride_confirm": (
            "ride/confirm",
            {"quote_id": ctx.get("quote_id", "quote-manual-test"), "confirm": True},
        ),
        "music_play": ("music/play", {"song": cfg.music_song, "volume": 60}),
        "music_volume": ("music/volume", {"direction": "up"}),
        "music_stop": ("music/stop", {}),
        "navigation_start": (
            "navigation/start",
            {"destination": {"address": "Buu dien Thanh pho Ho Chi Minh"}},
        ),
        "navigation_stop": (
            "navigation/stop",
            {"navigation_id": ctx.get("navigation_id", "nav-manual-test")},
        ),
        "contact_call": ("contact/call", {"name": cfg.contact_name}),
        "call_answer": ("call/answer", {}),
        "call_reject": ("call/reject", {}),
        "emergency_call": ("emergency/call", {}),
    }


class Runner:
    """Sends requests and collects one outcome line per case."""

    def __init__(self, client: httpx.Client, cfg: Config) -> None:
        """Bind the HTTP client and config."""
        self.client = client
        self.cfg = cfg
        self.headers = {"Authorization": f"Bearer {cfg.public_bearer_token}"}
        self.failures = 0

    def report(self, name: str, outcome: str, note: object) -> None:
        """Print one result line and count hard failures."""
        if outcome in {"FAIL", "TIMEOUT"}:
            self.failures += 1
        print(f"{outcome:13s} {name:22s} {note}")

    def post(self, path: str, body: dict[str, Any]) -> httpx.Response:
        """POST one Public function request."""
        return self.client.post(
            f"{self.cfg.base_url}/api/v1/service/{path}", json=body, headers=self.headers
        )

    def wait_terminal(self, request_id: str) -> dict[str, Any] | None:
        """Poll the status API until the request is terminal or the timeout passes."""
        deadline = time.monotonic() + self.cfg.poll_timeout_seconds
        while time.monotonic() < deadline:
            response = self.client.get(
                f"{self.cfg.base_url}/api/v1/requests/{request_id}", headers=self.headers
            )
            data: dict[str, Any] = response.json().get("data", {}) if response.is_success else {}
            if data.get("request_state") in TERMINAL_STATES:
                return data
            time.sleep(1.0)
        return None

    def run_case(self, name: str, path: str, business: dict[str, Any], ctx: dict[str, str]) -> None:
        """Run one action end to end."""
        request_id = str(uuid.uuid4())
        started = time.monotonic()
        response = self.post(
            path, {"device_id": self.cfg.device_id, "request_id": request_id, **business}
        )
        if response.status_code != 202:  # noqa: PLR2004
            self.report(name, "FAIL", f"accept returned {response.status_code}: {response.text}")
            return
        data = self.wait_terminal(request_id)
        elapsed = f"{time.monotonic() - started:5.1f}s"
        if data is None:
            self.report(name, "TIMEOUT", f"{elapsed} still processing")
        elif data["request_state"] == "succeeded":
            result = data.get("result") or {}
            for key in ("quote_id", "navigation_id"):
                if key in result:
                    ctx[key] = str(result[key])
            self.report(name, "PASS", f"{elapsed} {result}")
        else:
            self.report(name, "DEVICE_ERROR", f"{elapsed} {data['request_state']}: {data['error']}")

    def run_idempotency(self) -> None:
        """Replay one request and reuse its id with a different body."""
        request_id = str(uuid.uuid4())
        body = {"device_id": self.cfg.device_id, "request_id": request_id, "direction": "up"}
        first = self.post("music/volume", body)
        replay = self.post("music/volume", body)
        same = (
            first.status_code == replay.status_code == 202  # noqa: PLR2004
            and first.json()["data"]["accepted_at"] == replay.json()["data"]["accepted_at"]
        )
        self.report("idempotent_replay", "PASS" if same else "FAIL", "same operation reused")
        conflict = self.post("music/volume", {**body, "direction": "down"})
        ok = conflict.status_code == 409  # noqa: PLR2004
        self.report("request_id_conflict", "PASS" if ok else "FAIL", conflict.status_code)


def main() -> int:
    """Run the selected cases and return a non-zero exit code on hard failures."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--config", type=Path, default=Path(__file__).with_name("real_client.local.json")
    )
    parser.add_argument("--only", nargs="+", help="run only these action names")
    args = parser.parse_args()

    cfg = Config.load(args.config)
    ctx: dict[str, str] = {}
    known = case_bodies(cfg, ctx)
    selected = args.only or [name for name in known if name not in OPT_IN_ONLY]
    unknown = sorted(set(selected) - set(known))
    if unknown:
        print(f"Unknown actions {unknown}; choose from {sorted(known)}")
        return 2

    print(f"External client run against {cfg.base_url} for glasses {cfg.device_id}\n")
    with httpx.Client(timeout=10.0) as client:
        runner = Runner(client, cfg)
        for name in selected:
            path, body = case_bodies(cfg, ctx)[name]
            runner.run_case(name, path, body, ctx)
        if not args.only:
            runner.run_idempotency()
    return 1 if runner.failures else 0


if __name__ == "__main__":
    sys.exit(main())
