"""Demo phone-app auth router: register, OTP verify, login, logout.

Unauthenticated except ``/logout`` (gated by the caller's own session
Bearer token via :func:`app.auth.require_user_session`). Distinct from both
the External-API-Client Public surface and the Android-only Device surface:
this establishes the end-user identity that a phone-app client subsequently
attaches as ``user_id`` on every Public API call it makes.

There is no SMS provider wired into this prototype: the OTP code is logged
server-side (``logger.info``) instead of being sent by SMS. This keeps
register -> OTP verify -> login a real, persisted, HTTP round trip; only the
delivery channel for the code is a demo stand-in.
"""

from __future__ import annotations

import logging
from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession  # noqa: TC002

from app.auth import UserPrincipal, require_user_session
from app.database import get_db_session
from app.schemas.auth import (
    LoginRequest,
    LogoutData,
    OtpVerifyRequest,
    RegisterData,
    RegisterRequest,
    SessionData,
)
from app.schemas.common import OkResponse
from app.services.auth import AuthService

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])

logger = logging.getLogger(__name__)


@router.post("/register", response_model=OkResponse[RegisterData])
async def register(
    body: RegisterRequest,
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> OkResponse[RegisterData]:
    """Register a phone number + password account and issue a new OTP code."""
    service = AuthService(session)
    user, otp_code = await service.register(
        phone_number=body.phone_number,
        password=body.password,
        display_name=body.display_name,
    )
    if session is not None:
        await session.commit()

    logger.info("Demo OTP for %s: %s", user.phone_number, otp_code)

    data = RegisterData(
        user_id=user.id,
        public_user_id=user.public_user_id,
        phone_number=user.phone_number,
    )
    return OkResponse(data=data)


@router.post("/otp/verify", response_model=OkResponse[SessionData])
async def verify_otp(
    body: OtpVerifyRequest,
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> OkResponse[SessionData]:
    """Verify a pending OTP code and issue a session on success."""
    service = AuthService(session)
    issued = await service.verify_otp(phone_number=body.phone_number, otp_code=body.otp_code)
    if session is not None:
        await session.commit()

    data = SessionData(
        access_token=issued.raw_token,
        user_id=issued.user.id,
        public_user_id=issued.user.public_user_id,
        phone_number=issued.user.phone_number,
        display_name=issued.user.display_name,
    )
    return OkResponse(data=data)


@router.post("/login", response_model=OkResponse[SessionData])
async def login(
    body: LoginRequest,
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> OkResponse[SessionData]:
    """Log in a verified phone number + password account."""
    service = AuthService(session)
    issued = await service.login(phone_number=body.phone_number, password=body.password)
    if session is not None:
        await session.commit()

    data = SessionData(
        access_token=issued.raw_token,
        user_id=issued.user.id,
        public_user_id=issued.user.public_user_id,
        phone_number=issued.user.phone_number,
        display_name=issued.user.display_name,
    )
    return OkResponse(data=data)


@router.post("/logout", response_model=OkResponse[LogoutData])
async def logout(
    principal: Annotated[UserPrincipal, Depends(require_user_session)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> OkResponse[LogoutData]:
    """Revoke every active session for the caller (logout-everywhere semantics)."""
    service = AuthService(session)
    await service.logout(user_id=principal.user_id)
    if session is not None:
        await session.commit()
    return OkResponse(data=LogoutData())
