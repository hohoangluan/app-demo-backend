package com.youreyes.app.network

/** Body of `POST /api/v1/device/register`. */
data class DeviceRegisterPayload(
    val userId: String,
    val deviceId: String,
    val pushToken: String,
    val platform: String = "android",
)

/** Caller identity without the full phone number (privacy boundary for glasses). */
data class IncomingCallerPayload(
    val contactId: String? = null,
    val name: String? = null,
    val numberTail: String? = null,
    val duplicateName: Boolean = false,
)

/** Body of `POST /api/v1/device/event`. */
data class DeviceEventPayload(
    val deviceId: String,
    val type: String,
    val caller: IncomingCallerPayload? = null,
)

/** Pairs a glasses `deviceId` (not this phone's id) to the app user. */
data class GlassesLinkPayload(
    val userId: String,
    val deviceId: String,
)

data class AuthRegisterPayload(
    val phoneNumber: String,
    val password: String,
    val displayName: String? = null,
)

data class AuthOtpVerifyPayload(
    val phoneNumber: String,
    val otpCode: String,
)

data class AuthLoginPayload(
    val phoneNumber: String,
    val password: String,
)

/** Result of `/auth/register`: the account exists but still needs OTP verification. */
data class AuthRegisterResult(
    val userId: String,
    val publicUserId: String,
    val phoneNumber: String,
)

/** A login session; the raw token is returned exactly once. */
data class AuthSession(
    val accessToken: String,
    val userId: String,
    val publicUserId: String,
    val phoneNumber: String,
    val displayName: String?,
)

/**
 * Accessibility preferences. The server only accepts these exact values:
 * font size `"Nhỏ" | "Vừa" | "To"`, voice `"Giọng Nữ" | "Giọng Nam"`.
 */
data class PreferencesPayload(
    val fontSizeOption: String,
    val voiceOption: String,
    val highContrast: Boolean,
    val hapticsEnabled: Boolean,
)

data class SupportTicketResult(
    val id: String,
    val category: String,
    val createdAt: String,
)
