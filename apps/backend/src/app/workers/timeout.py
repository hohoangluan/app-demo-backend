"""Timeout worker loop expiring stale processing operations.

Per ``architeture.md`` section 8.4 & 9.2:
- Polls operations remaining in processing state past their expires_at deadline.
- Performs atomic conditional state transition to timed_out with stable error content.
- Resolves report-vs-timeout race via database row locking.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime
from typing import TYPE_CHECKING

from sqlalchemy import select

from app.models.enums import CallbackState, RequestState
from app.models.operation import Operation
from app.repositories.operation import OperationRepository

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

    from app.config import Settings

logger = logging.getLogger("app.workers.timeout")

_TIMEOUT_ERROR_PAYLOAD: dict[str, object] = {
    "code": "REPORT_TIMEOUT",
    "message": "Operation timed out waiting for device report",
    "details": {},
}


class TimeoutWorker:
    """Worker polling and transitioning expired processing operations."""

    def __init__(
        self, session_factory: async_sessionmaker[AsyncSession], settings: Settings
    ) -> None:
        """Initialize TimeoutWorker with session factory and configuration."""
        self._session_factory = session_factory
        self._settings = settings

    async def run_once(self) -> int:
        """Execute one batch of timeout checks.

        Returns the number of expired operations transitioned.
        """
        now = datetime.now(UTC)
        batch_size = self._settings.worker_batch_size

        async with self._session_factory() as session:
            result = await session.execute(
                select(Operation)
                .where(
                    Operation.request_state == RequestState.PROCESSING,
                    Operation.expires_at <= now,
                )
                .limit(batch_size)
            )
            expired_ops = result.scalars().all()

            if not expired_ops:
                return 0

            repo = OperationRepository(session)
            count = 0
            for op in expired_ops:
                success = await repo.record_timeout(
                    request_id=op.request_id,
                    now=now,
                    error=_TIMEOUT_ERROR_PAYLOAD,
                )
                if success:
                    count += 1
                    if self._settings.callback_url:
                        op.callback_state = CallbackState.PENDING
                        op.next_callback_at = now

            await session.commit()

        if count > 0:
            logger.info("Transitioned %d expired operation(s) to timed_out", count)

        return count

    async def run_loop(self, shutdown_event: asyncio.Event) -> None:
        """Continuous polling timeout worker loop until shutdown signal is set."""
        poll_interval = self._settings.worker_poll_seconds
        while not shutdown_event.is_set():
            try:
                processed = await self.run_once()
                if processed == 0:
                    await asyncio.sleep(poll_interval)
            except asyncio.CancelledError:
                break
            except Exception:
                logger.exception("Error in TimeoutWorker loop")
                await asyncio.sleep(poll_interval)
