package com.youreyes.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.youreyes.app.R

/**
 * Typography for Your Eyes.
 *
 * Two decisions carry this file.
 *
 * **The face.** Be Vietnam Pro, drawn by a Vietnamese foundry for Vietnamese
 * text. Roboto — the Compose default this app was using — sets Vietnamese
 * stacked diacritics (ế, ộ, ữ) tightly at heavy weights, and the app's headings
 * were all FontWeight.Black.
 *
 * Bundled in `res/font` rather than fetched through Google's downloadable-font
 * provider. Downloadable fonts need Play Services present and a successful
 * fetch, and fall back silently when either is missing — for an app whose users
 * cannot see that the text came back wrong, ~550KB of APK is the cheaper cost.
 * Licence: SIL OFL 1.1, `mobile/THIRD_PARTY_LICENSES/BeVietnamPro-OFL.txt`.
 *
 * Only four weights ship. FontWeight.Medium and FontWeight.Black are resolved by
 * Compose to the nearest bundled weight rather than synthesised.
 *
 * **The scale.** Sizes are multiplied by [LocalTypeScale], which is driven by
 * the user's own "Cỡ chữ" preference — the Nhỏ/Vừa/To setting the app already
 * stores on the server but, until now, never applied to itself. Nothing here is
 * below 15sp; the previous scale ran 11–17sp, with every subtitle at 12sp.
 *
 * This multiplier sits on top of the OS font-size setting rather than replacing
 * it. Compose sp units already scale with the system setting, so a user who has
 * turned Android's own font size up and then picks "To" here gets both.
 */

val YourEyesFontFamily = FontFamily(
    Font(R.font.be_vietnam_pro_regular, FontWeight.Normal),
    Font(R.font.be_vietnam_pro_semibold, FontWeight.SemiBold),
    Font(R.font.be_vietnam_pro_bold, FontWeight.Bold),
    Font(R.font.be_vietnam_pro_extrabold, FontWeight.ExtraBold),
)

/**
 * How much room a line gets relative to its size. Vietnamese needs more than the
 * Latin default: a lowercase letter can carry a tone mark above a vowel mark
 * (ế, ữ), and at 1.2× leading those marks clip into the line above.
 */
private const val LINE_HEIGHT_RATIO = 1.45f

/** The three steps behind "Cỡ chữ". Values are what the backend's Literal accepts. */
object TypeScale {
    const val SMALL = "Nhỏ"
    const val MEDIUM = "Vừa"
    const val LARGE = "To"

    /**
     * "To" is a 28% jump rather than the usual 15% because the step exists for
     * someone who cannot read the default, not for someone who prefers it bigger.
     */
    fun multiplierFor(option: String): Float = when (option) {
        SMALL -> 0.88f
        LARGE -> 1.28f
        else -> 1.0f
    }
}

private fun style(
    sizeSp: Float,
    weight: FontWeight,
    scale: Float,
    letterSpacingSp: Float = 0f,
): TextStyle {
    val size = (sizeSp * scale).sp
    return TextStyle(
        fontFamily = YourEyesFontFamily,
        fontWeight = weight,
        fontSize = size,
        lineHeight = (sizeSp * scale * LINE_HEIGHT_RATIO).sp,
        letterSpacing = (letterSpacingSp * scale).sp,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )
}

/**
 * Builds the Material typography at [scale].
 *
 * Role mapping used across the app, at scale 1.0:
 *
 *     displaySmall  30sp ExtraBold  one hero figure per screen
 *     headlineSmall 24sp Bold       screen title
 *     titleLarge    20sp Bold       section heading
 *     titleMedium   18sp SemiBold   card title
 *     bodyLarge     17sp Normal     default reading size
 *     bodyMedium    16sp Normal     secondary body
 *     bodySmall     15sp Normal     caption — the floor, nothing goes under this
 *     labelLarge    16sp Bold       button lettering
 *     labelMedium   14sp SemiBold   bottom-nav label
 */
fun appTypography(scale: Float): Typography = Typography(
    displaySmall = style(30f, FontWeight.ExtraBold, scale, letterSpacingSp = -0.5f),
    headlineSmall = style(24f, FontWeight.Bold, scale, letterSpacingSp = -0.2f),
    titleLarge = style(20f, FontWeight.Bold, scale),
    titleMedium = style(18f, FontWeight.SemiBold, scale),
    titleSmall = style(17f, FontWeight.SemiBold, scale),
    bodyLarge = style(17f, FontWeight.Normal, scale),
    bodyMedium = style(16f, FontWeight.Normal, scale),
    bodySmall = style(15f, FontWeight.Normal, scale),
    labelLarge = style(16f, FontWeight.Bold, scale),
    labelMedium = style(14f, FontWeight.SemiBold, scale),
    labelSmall = style(14f, FontWeight.Medium, scale),
)
