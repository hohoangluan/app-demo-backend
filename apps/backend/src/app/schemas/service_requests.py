"""Public function request schemas sourced from the published contract."""

from enum import StrEnum
from typing import Annotated, Self
from uuid import UUID

from pydantic import BaseModel, Field, model_validator


class ServiceRequest(BaseModel):
    """Fields shared by every Public function request."""

    user_id: str
    request_id: UUID


Latitude = Annotated[float, Field(ge=-90, le=90)]
Longitude = Annotated[float, Field(ge=-180, le=180)]
VolumeLevel = Annotated[int, Field(ge=0, le=100)]


class CurrentLocation(BaseModel):
    """Current device location with contract-defined coordinate bounds."""

    lat: Latitude
    lng: Longitude


class Destination(BaseModel):
    """Destination represented by an address, a coordinate pair, or both."""

    address: str | None = None
    lat: float | None = None
    lng: float | None = None

    @model_validator(mode="after")
    def validate_destination_shape(self) -> Self:
        """Require an address or a complete latitude/longitude pair."""
        has_latitude = self.lat is not None
        has_longitude = self.lng is not None
        if has_latitude != has_longitude:
            message = "destination lat and lng must be provided together"
            raise ValueError(message)
        if self.address is None and not has_latitude:
            message = "destination requires address or lat/lng"
            raise ValueError(message)
        return self


class RideQuoteRequest(ServiceRequest):
    """Request a ride quote from the target device."""

    current_location: CurrentLocation
    destination: Destination


class RideConfirmRequest(ServiceRequest):
    """Confirm or cancel a previously returned ride quote."""

    quote_id: str
    confirm: bool


class MusicPlayRequest(ServiceRequest):
    """Play a song, optionally setting its initial volume."""

    song: str
    volume: VolumeLevel | None = None


class MusicStopRequest(ServiceRequest):
    """Stop active music playback."""


class VolumeDirection(StrEnum):
    """Relative volume adjustment directions."""

    UP = "up"
    DOWN = "down"


class MusicVolumeRequest(ServiceRequest):
    """Change volume by one relative direction or one absolute level."""

    direction: VolumeDirection | None = None
    level: VolumeLevel | None = None

    @model_validator(mode="after")
    def require_exactly_one_volume_control(self) -> Self:
        """Require exactly one of direction and level."""
        if (self.direction is None) == (self.level is None):
            message = "exactly one of direction or level is required"
            raise ValueError(message)
        return self


class NavigationStartRequest(ServiceRequest):
    """Start walking navigation to a destination."""

    destination: Destination


class NavigationStopRequest(ServiceRequest):
    """Stop a navigation session."""

    navigation_id: str


class EmergencyCallRequest(ServiceRequest):
    """Start the configured emergency call flow."""


class ContactCallRequest(ServiceRequest):
    """Call exactly one contact selected by name."""

    name: str = Field(min_length=1)
