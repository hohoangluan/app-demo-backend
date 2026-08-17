"""Internal glasses-pairing API schemas: link and unlink."""

from typing import Literal

from pydantic import BaseModel, ConfigDict

from app.schemas.common import TrimmedNonEmptyStr


class GlassesLinkRequest(BaseModel):
    """Pair a glasses device_id to the given app user_id."""

    model_config = ConfigDict(extra="forbid")

    user_id: TrimmedNonEmptyStr
    device_id: TrimmedNonEmptyStr


class GlassesLinkData(BaseModel):
    """Result of a successful glasses pairing."""

    model_config = ConfigDict(extra="forbid")

    device_id: str
    linked: Literal[True] = True


class GlassesUnlinkRequest(BaseModel):
    """Unpair whichever glasses device_id is currently active for user_id."""

    model_config = ConfigDict(extra="forbid")

    user_id: TrimmedNonEmptyStr


class GlassesUnlinkData(BaseModel):
    """Result of an unlink attempt: whether an active pairing was cleared."""

    model_config = ConfigDict(extra="forbid")

    unlinked: bool
