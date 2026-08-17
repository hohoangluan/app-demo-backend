"""Accessibility preferences router: read and update the caller's own settings."""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession  # noqa: TC002

from app.auth import UserPrincipal, require_user_session
from app.database import get_db_session
from app.repositories.user import UserRepository
from app.schemas.common import OkResponse
from app.schemas.preferences import PreferencesData, UpdatePreferencesRequest

router = APIRouter(prefix="/api/v1/preferences", tags=["preferences"])


@router.get("", response_model=OkResponse[PreferencesData])
async def get_preferences(
    principal: Annotated[UserPrincipal, Depends(require_user_session)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> OkResponse[PreferencesData]:
    """Return the caller's current accessibility preferences."""
    repo = UserRepository(session)
    user = await repo.get_by_id(principal.user_id)
    if user is None:
        message = "Authenticated session references a missing user row"
        raise RuntimeError(message)

    data = PreferencesData(
        font_size_option=user.font_size_option,  # type: ignore[arg-type]
        voice_option=user.voice_option,  # type: ignore[arg-type]
        high_contrast=user.high_contrast,
        haptics_enabled=user.haptics_enabled,
    )
    return OkResponse(data=data)


@router.put("", response_model=OkResponse[PreferencesData])
async def update_preferences(
    body: UpdatePreferencesRequest,
    principal: Annotated[UserPrincipal, Depends(require_user_session)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> OkResponse[PreferencesData]:
    """Overwrite the caller's accessibility preferences."""
    repo = UserRepository(session)
    user = await repo.update_preferences(
        principal.user_id,
        font_size_option=body.font_size_option,
        voice_option=body.voice_option,
        high_contrast=body.high_contrast,
        haptics_enabled=body.haptics_enabled,
    )
    if user is None:
        message = "Authenticated session references a missing user row"
        raise RuntimeError(message)
    if session is not None:
        await session.commit()

    data = PreferencesData(
        font_size_option=user.font_size_option,  # type: ignore[arg-type]
        voice_option=user.voice_option,  # type: ignore[arg-type]
        high_contrast=user.high_contrast,
        haptics_enabled=user.haptics_enabled,
    )
    return OkResponse(data=data)
