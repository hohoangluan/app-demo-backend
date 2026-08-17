"""Delivery worker loop processing due command deliveries.

Per ``architeture.md`` section 9.2 & 9.3:
- Polls operations needing delivery (received/retry or expired lease).
- Uses FOR UPDATE SKIP LOCKED.
- Resolves active device and decrypts push token.
- Invokes DeliveryAdapter outside database transaction.
- Updates delivery state with fenced lease check.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING

from app.adapters.delivery import DeliveryOutcomeStatus, get_delivery_adapter
from app.repositories.device import DeviceRepository
from app.repositories.operation import OperationRepository

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

    from app.config import Settings
    from app.models.operation import Operation

logger = logging.getLogger("app.workers.delivery")

_RETRY_BACKOFF = [timedelta(seconds=5), timedelta(seconds=20)]


class DeliveryWorker:
    """Worker polling and executing device delivery tasks."""

    def __init__(
        self,
        session_factory: async_sessionmaker[AsyncSession],
        settings: Settings,
        wake_event: asyncio.Event | None = None,
    ) -> None:
        """Initialize DeliveryWorker with session factory and configuration."""
        self._session_factory = session_factory
        self._settings = settings
        self._adapter = get_delivery_adapter(settings)
        self._wake_event = wake_event or asyncio.Event()

    async def run_once(self) -> int:
        """Execute one polling batch of delivery claims and processing.

        Returns the number of claimed operations processed.
        """
        now = datetime.now(UTC)
        lease_duration = timedelta(seconds=self._settings.delivery_lease_seconds)
        lease_until = now + lease_duration
        batch_size = self._settings.worker_batch_size

        async with self._session_factory() as session:
            repo = OperationRepository(session)
            claimed = await repo.claim_due_deliveries(
                now=now, lease_until=lease_until, limit=batch_size
            )
            await session.commit()

        if not claimed:
            return 0

        for op in claimed:
            try:
                await self._process_claimed_delivery(op)
            except Exception:
                logger.exception(
                    "Unexpected error processing delivery for request_id=%s",
                    op.request_id,
                )

        return len(claimed)

    async def _process_claimed_delivery(self, op: Operation) -> None:
        """Deliver command message and persist outcome."""
        now = datetime.now(UTC)

        async with self._session_factory() as session:
            device_repo = DeviceRepository(session)
            device = await device_repo.get_latest_active_device(op.user_id)
            push_token = device.push_token_ciphertext if device else ""

        if device is None:
            return

        outcome = await self._adapter.send_command(
            device=device,
            push_token=push_token,
            operation=op,
        )

        async with self._session_factory() as session:
            repo = OperationRepository(session)
            locked_until = op.delivery_locked_until or now

            if outcome.status is DeliveryOutcomeStatus.SENT:
                await repo.record_delivery_sent(
                    request_id=op.request_id,
                    lease_until=locked_until,
                )
            elif outcome.status is DeliveryOutcomeStatus.TRANSIENT_FAILURE:
                attempt_idx = min(op.delivery_attempts, len(_RETRY_BACKOFF) - 1)
                retry_delay = _RETRY_BACKOFF[attempt_idx]
                next_delivery_at = now + retry_delay
                await repo.record_delivery_transient_failure(
                    request_id=op.request_id,
                    lease_until=locked_until,
                    next_delivery_at=next_delivery_at,
                )
            else:  # PERMANENT_FAILURE
                reason = outcome.error_reason or "Permanent delivery failure"
                error_payload: dict[str, object] = {
                    "code": "DELIVERY_FAILED",
                    "message": reason,
                    "details": {},
                }
                await repo.record_delivery_permanent_failure(
                    request_id=op.request_id,
                    lease_until=locked_until,
                    error=error_payload,
                )

            await session.commit()

    async def run_loop(self, shutdown_event: asyncio.Event) -> None:
        """Continuous polling worker loop until shutdown signal is set."""
        poll_interval = self._settings.worker_poll_seconds
        while not shutdown_event.is_set():
            try:
                processed = await self.run_once()
                if processed == 0:
                    try:
                        await asyncio.wait_for(self._wake_event.wait(), timeout=poll_interval)
                    except TimeoutError:
                        pass
                    finally:
                        self._wake_event.clear()
            except asyncio.CancelledError:
                break
            except Exception:
                logger.exception("Error in DeliveryWorker loop")
                await asyncio.sleep(poll_interval)
