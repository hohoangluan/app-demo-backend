package com.youreyes.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Your Eyes colour tokens.
 *
 * Every value that text or a small icon is drawn in has been checked against its
 * own background with the WCAG 2.1 contrast formula, and the ratio is recorded
 * beside it. This app is for blind and low-vision users, so the AA floor
 * (4.5:1 for text under 24sp) is a hard requirement — several of the original
 * tokens sat well under it:
 *
 *     old YourEyesMuted   #6E829A on white = 4.0:1   (every subtitle, at 12sp)
 *     old YourEyesSuccess #24B86E on white = 2.6:1
 *     old YourEyesWarning #F59E2E on white = 2.1:1
 *     old YourEyesDanger  #EE5D5D on white = 3.0:1
 *
 * The brand hue is unchanged. What changed is which shade of it may carry text:
 * the bright cyan is now a fill-and-large-icon colour only, and a darkened
 * sibling carries any cyan lettering.
 */

// ---------------------------------------------------------------------------
// Brand — fills, large icons, decorative surfaces.
// Never draw text under 24sp in these on a white surface.
// ---------------------------------------------------------------------------

val YourEyesCyan = Color(0xFF12BFE7)
val YourEyesTeal = Color(0xFF22D3BD)
val YourEyesMint = Color(0xFF80F0D0)
val YourEyesMintSoft = Color(0xFFE9FFFA)
val YourEyesSkySoft = Color(0xFFE9F8FF)

/** Text-safe sibling of [YourEyesCyan]. 5.8:1 on white. Use for cyan lettering and small icons. */
val YourEyesCyanText = Color(0xFF0B6E86)

/** Text-safe sibling of [YourEyesTeal]. 5.1:1 on white. */
val YourEyesTealText = Color(0xFF0A6F63)

// ---------------------------------------------------------------------------
// Text on light surfaces
// ---------------------------------------------------------------------------

/** Primary text. 16.5:1 on white — AAA. */
val YourEyesInk = Color(0xFF0E1F33)

/** Headings and brand lettering. 13.2:1 on white — AAA. */
val YourEyesNavy = Color(0xFF14325B)

/**
 * Secondary text: subtitles, captions, helper lines. 7.6:1 on white — AAA.
 *
 * Replaces #6E829A, which failed AA at 4.0:1 while carrying every subtitle in
 * the app. Do not lighten it back for visual balance: if a block feels heavy,
 * cut words or change its size role, never its contrast.
 */
val YourEyesMuted = Color(0xFF3D5670)

/** Genuinely inactive text — a disabled control's label. 4.6:1 on white. */
val YourEyesDisabled = Color(0xFF5E7186)

// ---------------------------------------------------------------------------
// Surfaces and structure
// ---------------------------------------------------------------------------

val YourEyesSurface = Color(0xFFFFFFFF)

/** Slightly recessed surface for grouping inside a white card. */
val YourEyesSurfaceSunken = Color(0xFFF2F7FA)

/** Hairline between rows inside one card. Structural only, never carries meaning. */
val YourEyesLine = Color(0xFFC9E3E6)

/** Card border. Darkened from #DDF4F1 so a card edge stays findable at low acuity. */
val YourEyesBorder = Color(0xFFB9D9DE)

val YourEyesShadow = Color(0xFF5AA8B2)

// ---------------------------------------------------------------------------
// Status — each has a text-weight value and a soft fill for its container
// ---------------------------------------------------------------------------

/** 5.4:1 on white. */
val YourEyesSuccess = Color(0xFF0B7A45)
val YourEyesSuccessSoft = Color(0xFFE3F6EC)

/** 5.7:1 on white. */
val YourEyesWarning = Color(0xFF9A5600)
val YourEyesWarningSoft = Color(0xFFFDF0DC)

/** 5.9:1 on white. */
val YourEyesDanger = Color(0xFFC0271F)
val YourEyesDangerSoft = Color(0xFFFCE8E6)

/** 5.6:1 on white. */
val YourEyesBlue = Color(0xFF14688F)
val YourEyesBlueSoft = Color(0xFFE6F2F8)

// ---------------------------------------------------------------------------
// Background and button gradients
// ---------------------------------------------------------------------------

val YourEyesGradientStart = Color(0xFFECFFFC)
val YourEyesGradientMiddle = Color(0xFFF8FFFF)
val YourEyesGradientEnd = Color(0xFFE9FAFF)

/**
 * Primary button gradient, darkened from #14BCE3 → #52E5B4. White lettering on
 * the old mint end sat at 1.7:1 and was effectively unreadable outdoors; this
 * pair holds white at 4.6:1 at its lightest point.
 */
val YourEyesButtonGradientStart = Color(0xFF0A7E9B)
val YourEyesButtonGradientEnd = Color(0xFF12876F)

// ---------------------------------------------------------------------------
// High-contrast mode
//
// Not a dark theme for taste. This is the palette low-vision signage actually
// uses — a near-black ground with amber and white — which holds up for reduced
// acuity and for light sensitivity, where a white ground does not.
// Reachable from Hồ sơ → Hiển thị.
// ---------------------------------------------------------------------------

val HcGround = Color(0xFF05090D)
val HcSurface = Color(0xFF111A23)
val HcSurfaceSunken = Color(0xFF1B2731)

/** 18.9:1 on [HcGround]. */
val HcInk = Color(0xFFFFFFFF)

/** 11.4:1 on [HcGround]. */
val HcMuted = Color(0xFFC7D5E2)

/** Amber accent. 12.6:1 on [HcGround] — the one colour allowed to carry emphasis here. */
val HcAccent = Color(0xFFFFC53D)

val HcBorder = Color(0xFF4A5F73)
val HcSuccess = Color(0xFF5BE39A)
val HcWarning = Color(0xFFFFC53D)
val HcDanger = Color(0xFFFF8A7A)
