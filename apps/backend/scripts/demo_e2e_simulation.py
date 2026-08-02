"""End-to-End E2E Simulation script proving full functional flow.

Tests:
1. Database Schema Bootstrap (Alembic migration)
2. Android Device Registration (/api/v1/device/register)
3. Public Action Request Submission (/api/v1/service/music/volume)
4. Delivery Worker Claiming & FCM/Fake Push Dispatch
5. Android Device Execution Reporting (/api/v1/device/report)
6. Public Status Query (/api/v1/requests/{request_id})
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import os
import secrets
import subprocess
import sys
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

from httpx import ASGITransport, AsyncClient

from app.application import create_app
from app.config import AppEnvironment, DeliveryTransport, Settings
from app.database import build_async_engine, build_session_factory
from app.workers.delivery import DeliveryWorker

_PUBLIC_DOMAIN = b"app-demo-auth/v1/public-api"
_DEVICE_DOMAIN = b"app-demo-auth/v1/device-api"


def _digest(raw_token: str, domain: bytes) -> str:
    return hmac.new(raw_token.encode("utf-8"), domain, hashlib.sha256).hexdigest()


def _run_alembic_upgrade(db_url: str) -> None:
    """Run Alembic migrations to bootstrap tables."""
    backend_dir = Path(__file__).resolve().parent.parent
    alembic_exe = str(Path(sys.executable).parent / "alembic.exe")
    env = os.environ.copy()
    env["DATABASE_URL"] = db_url
    result = subprocess.run(
        [alembic_exe, "upgrade", "head"],
        cwd=backend_dir,
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"Alembic migration failed: {result.stderr}")







async def run_simulation() -> None:
    raw_public_token = secrets.token_urlsafe(32)
    raw_device_token = secrets.token_urlsafe(32)

    pub_hash = _digest(raw_public_token, _PUBLIC_DOMAIN)
    dev_hash = _digest(raw_device_token, _DEVICE_DOMAIN)

    db_url = "postgresql+asyncpg://app_demo_test:app_demo_test@127.0.0.1:57432/app_demo_test"

    print("=== 0. Bootstrapping Database Schema (Alembic Upgrade) ===")
    _run_alembic_upgrade(db_url)
    print("Database schema bootstrapped cleanly")

    settings = Settings.model_validate(
        {
            "app_env": AppEnvironment.TEST,
            "http_port": 8000,
            "database_url": db_url,
            "public_api_token_hash": pub_hash,
            "public_api_client_id": "simulated-client",
            "public_api_scopes": {"service:execute", "requests:read"},
            "device_api_token_hash": dev_hash,
            "field_encryption_key": "simulated-key-field-encryption-12345",
            "delivery_transport": DeliveryTransport.FAKE,
        }
    )

    engine = build_async_engine(settings.database_url)
    session_factory = build_session_factory(engine)

    app = create_app(settings)
    app.state.engine = engine
    app.state.session_factory = session_factory

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        print("\n=== 1. Registering Android Device ===")
        reg_payload = {
            "user_id": "user-demo-100",
            "device_id": "device-android-100",
            "platform": "android",
            "push_token": "fcm-push-token-demo-xyz",
        }
        res_reg = await client.post(
            "/api/v1/device/register",
            json=reg_payload,
            headers={"Authorization": f"Bearer {raw_device_token}"},
        )
        print(f"Register status: {res_reg.status_code}, body: {res_reg.json()}")
        assert res_reg.status_code == 200

        print("\n=== 2. Submitting Music Volume Public API Request ===")
        request_id = str(uuid4())
        vol_payload = {
            "user_id": "user-demo-100",
            "request_id": request_id,
            "level": 75,
        }
        res_vol = await client.post(
            "/api/v1/service/music/volume",
            json=vol_payload,
            headers={"Authorization": f"Bearer {raw_public_token}"},
        )
        print(f"Submit status: {res_vol.status_code}, body: {res_vol.json()}")
        assert res_vol.status_code == 202

        print("\n=== 3. Running Delivery Worker Claim & Fake Push ===")
        delivery_worker = DeliveryWorker(session_factory, settings)
        claimed_count = await delivery_worker.run_once()
        print(f"Delivery worker claimed & dispatched {claimed_count} operation(s)")
        assert claimed_count == 1

        print("\n=== 4. Checking Initial Status (Processing / Delivery Sent) ===")
        res_status_1 = await client.get(
            f"/api/v1/requests/{request_id}",
            headers={"Authorization": f"Bearer {raw_public_token}"},
        )
        print(f"Initial status: {res_status_1.json()}")
        assert res_status_1.json()["data"]["request_state"] == "processing"

        print("\n=== 5. Simulating Android Device Reporting Execution Outcome ===")
        report_payload = {
            "user_id": "user-demo-100",
            "device_id": "device-android-100",
            "request_id": request_id,
            "action": "music_volume",
            "execution_state": "succeeded",
            "result": {"level": 75, "status": "volume_applied_successfully"},
            "timestamp": datetime.now(UTC).isoformat(),
        }
        res_rep = await client.post(
            "/api/v1/device/report",
            json=report_payload,
            headers={"Authorization": f"Bearer {raw_device_token}"},
        )
        print(f"Report status: {res_rep.status_code}, body: {res_rep.json()}")
        assert res_rep.status_code == 200

        print("\n=== 6. Querying Terminal Status API ===")
        res_status_2 = await client.get(
            f"/api/v1/requests/{request_id}",
            headers={"Authorization": f"Bearer {raw_public_token}"},
        )
        print(f"Terminal status: {res_status_2.json()}")
        assert res_status_2.json()["data"]["request_state"] == "succeeded"
        assert res_status_2.json()["data"]["result"] == {
            "level": 75,
            "status": "volume_applied_successfully",
        }

        print("\nSUCCESS: End-to-End Functional Simulation Completed 100% Cleanly!")


    await engine.dispose()


if __name__ == "__main__":
    asyncio.run(run_simulation())
