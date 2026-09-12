"""Accessibility preference API schemas: read and update the caller's settings."""

from typing import Literal

from pydantic import BaseModel, ConfigDict

FontSizeOption = Literal["Nhỏ", "Vừa", "To"]
VoiceOption = Literal["Giọng Nữ", "Giọng Nam"]
AnnounceCallerOption = Literal["name", "number_only", "ring_only"]


class PreferencesData(BaseModel):
    """The caller's current accessibility preferences."""

    model_config = ConfigDict(extra="forbid")

    font_size_option: FontSizeOption
    voice_option: VoiceOption
    high_contrast: bool
    haptics_enabled: bool
    announce_caller: AnnounceCallerOption


class UpdatePreferencesRequest(BaseModel):
    """Overwrite the caller's accessibility preferences."""

    model_config = ConfigDict(extra="forbid")

    font_size_option: FontSizeOption
    voice_option: VoiceOption
    high_contrast: bool
    haptics_enabled: bool
    announce_caller: AnnounceCallerOption
