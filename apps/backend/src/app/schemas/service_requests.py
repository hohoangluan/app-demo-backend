"""Public function request schemas sourced from the published contract."""

from enum import StrEnum
from typing import Annotated, Self
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.schemas.common import Latitude, Longitude, TrimmedNonEmptyStr


class ServiceRequest(BaseModel):
    """Fields shared by every Public function request.

    ``device_id`` identifies the glasses device the External API Client
    (e.g. a "kính" server) is acting on behalf of, not the Android app's own
    installation id (see ``app/models/glasses_device.py``). The Public
    Service API resolves it to an internal ``user_id`` via
    ``GlassesDeviceRepository`` before accepting the operation.
    """

    model_config = ConfigDict(extra="forbid")

    device_id: TrimmedNonEmptyStr
    request_id: UUID


VolumeLevel = Annotated[int, Field(ge=0, le=100)]


class CurrentLocation(BaseModel):
    """Current device location with contract-defined coordinate bounds."""

    model_config = ConfigDict(extra="forbid")

    lat: Latitude
    lng: Longitude


class Destination(BaseModel):
    """Destination represented by an address, a coordinate pair, or both."""

    model_config = ConfigDict(extra="forbid")

    address: str | None = None
    lat: Latitude | None = None
    lng: Longitude | None = None

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

    quote_id: TrimmedNonEmptyStr
    confirm: bool


class MusicPlayRequest(ServiceRequest):
    """Play a song, optionally setting its initial volume."""

    song: TrimmedNonEmptyStr
    volume: VolumeLevel | None = None
    spotify_uri: str | None = None


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

    navigation_id: TrimmedNonEmptyStr


class EmergencyCallRequest(ServiceRequest):
    """Start the configured emergency call flow."""

    number: str | None = None


class ContactCallRequest(ServiceRequest):
    """Call exactly one contact selected by name."""

    name: TrimmedNonEmptyStr
    contact_id: str | None = None


class LocationGetRequest(ServiceRequest):
    """Request the device's current location."""


class CapabilitiesGetRequest(ServiceRequest):
    """Request the Android client's fixed capability snapshot."""


class CallAnswerRequest(ServiceRequest):
    """Answer the currently ringing phone call."""


class CallRejectRequest(ServiceRequest):
    """Reject the currently ringing phone call."""


class QuotesSpeakRequest(ServiceRequest):
    """Speak a quote or custom text."""

    text: TrimmedNonEmptyStr
    quote_id: str | None = None


class MediaPlayRequest(ServiceRequest):
    """Play a media track or song."""

    song: TrimmedNonEmptyStr
    media_id: str | None = None


class CameraCaptureRequest(ServiceRequest):
    """Capture a photo or video."""

    mode: str = "photo"


class DisplayShowRequest(ServiceRequest):
    """Show a message on the device display."""

    message: TrimmedNonEmptyStr


class SystemSettingsRequest(ServiceRequest):
    """Update a system setting on the device."""

    setting_key: TrimmedNonEmptyStr
    value: str | None = None
