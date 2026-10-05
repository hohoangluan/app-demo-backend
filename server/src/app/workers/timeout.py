"""Timeout worker: operations without a report by ``expires_at`` become ``timed_out``.

The conditional update in the repository loses cleanly to a report that
arrives first, so a request is never both reported and timed out.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime
from typing import TYPE_CHECKING

from app.repositories.operation import OperationRepository

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

    from app.config import Settings

logger = logging.getLogger(__name__)

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
        """Time out one batch of expired operations; return how many changed."""
        now = datetime.now(UTC)
        async with self._session_factory() as session:
            repo = OperationRepository(session)
            expired = await repo.list_expired(now=now, limit=self._settings.worker_batch_size)
            count = 0
            for operation in expired:
                if await repo.record_timeout(
                    request_id=operation.request_id,
                    now=now,
                    error=_TIMEOUT_ERROR_PAYLOAD,
                    schedule_callback=self._settings.callback_url is not None,
                ):
                    count += 1
            await session.commit()

        if count:
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
