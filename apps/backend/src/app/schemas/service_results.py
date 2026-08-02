"""Public function result schemas sourced from the published contract."""

from datetime import datetime
from enum import StrEnum
from typing import Literal, Self

from pydantic import BaseModel, model_validator


class PriceEstimate(BaseModel):
    """Provider ride-price estimate."""

    currency: str
    amount: float


class RideQuoteResult(BaseModel):
    """Final ride quote result."""

    quote_id: str
    product_type: str
    price_estimate: PriceEstimate
    eta_minutes: int
    expires_at: datetime


class RideStatus(StrEnum):
    """Contract-defined ride confirmation states."""

    REQUESTED = "requested"
    CANCELLED = "cancelled"


class RideConfirmResult(BaseModel):
    """Final result for confirming or cancelling a ride quote."""

    quote_id: str
    ride_id: str | None
    ride_status: RideStatus

    @model_validator(mode="after")
    def validate_ride_id_for_status(self) -> Self:
        """Match ride ID presence to requested versus cancelled status."""
        if self.ride_status is RideStatus.REQUESTED and self.ride_id is None:
            message = "ride_id is required when ride_status is requested"
            raise ValueError(message)
        if self.ride_status is RideStatus.CANCELLED and self.ride_id is not None:
            message = "ride_id must be null when ride_status is cancelled"
            raise ValueError(message)
        return self


class MusicPlayResult(BaseModel):
    """Final result after starting music playback."""

    track_id: str
    title: str
    artist: str
    playback_state: Literal["playing"]
    volume: int


class MusicStopResult(BaseModel):
    """Final result after stopping music playback."""

    playback_state: Literal["stopped"]


class MusicVolumeResult(BaseModel):
    """Final result after changing device volume."""

    volume_state: Literal["changed"]
    level: int


class NavigationDestination(BaseModel):
    """Provider-normalized navigation destination."""

    address: str | None
    lat: float
    lng: float


class NavigationStartResult(BaseModel):
    """Final result after starting walking guidance."""

    navigation_id: str
    navigation_state: Literal["navigating"]
    travel_mode: Literal["walking"]
    destination: NavigationDestination


class NavigationStopResult(BaseModel):
    """Final result after stopping guidance."""

    navigation_id: str
    navigation_state: Literal["stopped"]


class EmergencyCycle(StrEnum):
    """Contract-defined stages in the emergency calling cycle."""

    CALLING_5MIN = "calling_5min"
    PAUSED_10MIN = "paused_10min"
    STOPPED = "stopped"


class EmergencyCallResult(BaseModel):
    """Emergency result without constraining the unresolved state enum."""

    emergency_state: str
    attempt: int
    answered: bool
    cycle: EmergencyCycle
    contact: str
    sms_sent: bool


class ContactCallResult(BaseModel):
    """Final result after calling one uniquely matched contact."""

    call_state: Literal["calling"]
    contact_name: str
    phone_number: str


type ActionResult = (
    RideQuoteResult
    | RideConfirmResult
    | MusicPlayResult
    | MusicStopResult
    | MusicVolumeResult
    | NavigationStartResult
    | NavigationStopResult
    | EmergencyCallResult
    | ContactCallResult
)
