package com.youreyes.app.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.youreyes.app.ui.theme.AppTheme

/**
 * Shared building blocks.
 *
 * Three rules hold across this file, all of them consequences of who uses the app.
 *
 * **No component sets `fontSize`.** Every size comes from a `MaterialTheme.typography`
 * role, which is built from the user's "Cỡ chữ" preference (see `ui/theme/Type.kt`).
 * A hardcoded `fontSize` silently opts that component out of the setting, which is
 * how the previous version ended up with a font-size preference that changed nothing.
 *
 * **No component names a raw colour token.** They ask `AppTheme.colors` for a role,
 * so high-contrast mode swaps the palette without any of them knowing.
 *
 * **Every tappable thing is at least 56dp.** Android's own floor is 48dp; this
 * audience gets more, because a missed tap costs a blind user far more than the
 * screen space costs a sighted one.
 */

/** Minimum height for anything tappable. Deliberately above Android's 48dp floor. */
val MinTouchTarget = 56.dp

@Composable
fun ScreenShell(
    modifier: Modifier = Modifier,
    title: String? = null,
    scrollable: Boolean = true,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AppTheme.colors

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.ground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier,
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!title.isNullOrBlank() || onBack != null) {
                ScreenHeader(title = title.orEmpty(), onBack = onBack)
            }
            content()
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Screen header. Left-aligned rather than centred: a screen title is the first
 * thing a sighted low-vision user hunts for, and the left edge is where every
 * other line of the screen starts, so it takes no searching to find.
 */
@Composable
private fun ScreenHeader(title: String, onBack: (() -> Unit)?) {
    val colors = AppTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(MinTouchTarget)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
                    .clickable(
                        onClickLabel = "Quay lại màn hình trước",
                        role = Role.Button,
                        onClick = onBack,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Quay lại",
                    tint = colors.ink,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }

        if (title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

@Composable
fun HeaderTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        color = AppTheme.colors.ink,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() },
    )
}

/**
 * Section heading. Marked as a heading for TalkBack, which lets someone jump
 * between sections instead of swiping through every control in between — the
 * difference between three gestures and thirty on the Hồ sơ screen.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = AppTheme.colors.ink,
        modifier = modifier
            .padding(top = 8.dp)
            .semantics { heading() },
    )
}

/**
 * The primary action of a screen. There should be one.
 *
 * [onClickLabel] is what TalkBack reads after "double tap to" — write it as the
 * verb phrase for what happens ("gửi yêu cầu hỗ trợ"), not as a restatement of
 * the button text.
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClickLabel: String? = null,
) {
    val colors = AppTheme.colors
    val background = if (enabled) {
        colors.primaryButton
    } else {
        Brush.horizontalGradient(listOf(colors.surfaceSunken, colors.surfaceSunken))
    }
    val contentColor = if (enabled) colors.onAccent else colors.inkDisabled

    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .shadow(
                elevation = if (enabled && !colors.isHighContrast) 6.dp else 0.dp,
                shape = RoundedCornerShape(20.dp),
                spotColor = colors.accent,
            )
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .then(
                if (colors.isHighContrast && enabled) {
                    Modifier.border(2.dp, colors.ink, RoundedCornerShape(20.dp))
                } else {
                    Modifier
                },
            )
            .clickable(
                enabled = enabled,
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A navigable row.
 *
 * The whole card is one focus stop for TalkBack, announcing title then subtitle,
 * rather than two separate stops with a chevron in between that reads as a third.
 * The subtitle is worth hearing — it is the only place the row says what it does.
 */
@Composable
fun RowCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconTone: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val tone = iconTone ?: colors.accentText
    val toneOnHighContrast = if (colors.isHighContrast) colors.accent else tone

    Card(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 76.dp)
            .shadow(
                elevation = if (colors.isHighContrast) 0.dp else 4.dp,
                shape = RoundedCornerShape(20.dp),
                spotColor = colors.accent,
            )
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(role = Role.Button, onClick = onClick)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "$title. $subtitle"
                        }
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = androidx.compose.foundation.BorderStroke(
            if (colors.isHighContrast) 2.dp else 1.dp,
            colors.border,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (colors.isHighContrast) {
                            colors.surfaceSunken
                        } else {
                            toneOnHighContrast.copy(alpha = 0.12f)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = toneOnHighContrast,
                    modifier = Modifier.size(28.dp),
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (trailing != null) {
                Spacer(modifier = Modifier.width(12.dp))
                trailing()
            } else if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

@Composable
fun MiniStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tone: Color? = null,
) {
    val colors = AppTheme.colors
    val valueColor = tone ?: colors.accentText

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (colors.isHighContrast) colors.surfaceSunken else colors.accentSoft)
            .padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = if (colors.isHighContrast) colors.ink else valueColor,
                textAlign = TextAlign.Center,
                // A stat is a short figure. Anything long enough to wrap is not a
                // stat and should not have been passed here; clip it rather than
                // let it break mid-token across two lines.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Status of one thing, in a word or two. Colour is never the only signal — the text says it too. */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.NEUTRAL,
) {
    val colors = AppTheme.colors
    val (fill, ink) = when (tone) {
        StatusTone.SUCCESS -> colors.successSoft to colors.success
        StatusTone.WARNING -> colors.warningSoft to colors.warning
        StatusTone.DANGER -> colors.dangerSoft to colors.danger
        StatusTone.NEUTRAL -> colors.surfaceSunken to colors.inkSecondary
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(fill)
            .then(
                if (colors.isHighContrast) {
                    Modifier.border(2.dp, ink, RoundedCornerShape(999.dp))
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = ink,
        )
    }
}

enum class StatusTone { NEUTRAL, SUCCESS, WARNING, DANGER }

/**
 * Feedback after an action. Announced to TalkBack as soon as it appears —
 * without the live region, a blind user submits a form and hears nothing back.
 */
@Composable
fun InlineMessage(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    if (text.isBlank()) return
    val colors = AppTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isError) colors.dangerSoft else colors.successSoft)
            .padding(16.dp)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (isError) Icons.Default.Warning else Icons.Default.Info,
            contentDescription = null,
            tint = if (isError) colors.danger else colors.success,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) colors.danger else colors.success,
        )
    }
}

/**
 * An empty list is an instruction, not an apology. [message] should say what to
 * do to make something appear here.
 */
@Composable
fun EmptyState(
    title: String,
    message: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .padding(28.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.inkSecondary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Flags a screen as a UI mock with no backend behind it — Dịch Thuật and Biên
 * Bản Họp, built ahead of a `translate`/`meeting_record` action existing in the
 * Public API contract.
 *
 * The icon is decorative and the text carries the whole meaning, because the
 * previous version put a 🧪 emoji inside the string and TalkBack reads that
 * aloud as "test tube" before the sentence starts.
 */
@Composable
fun DemoBanner(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.warningSoft)
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Default.Science,
            contentDescription = null,
            tint = colors.warning,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.warning,
        )
    }
}

/**
 * Emergency call. Three taps within two seconds.
 *
 * The three-tap guard is there so a phone in a pocket cannot place an emergency
 * call, and it stays. But it is unusable with a screen reader — TalkBack
 * consumes taps for its own explore-by-touch, so the counter never reaches
 * three. The button therefore also carries a custom accessibility action,
 * "Gọi khẩn cấp ngay", which reaches the same call in one deliberate step:
 * TalkBack users open the actions menu and choose it, which is intentional
 * enough to serve the same purpose the tap count serves for everyone else.
 */
@Composable
fun SosButton(
    onSosTriggered: () -> Unit,
    modifier: Modifier = Modifier,
    emergencyPhone: String = "",
) {
    val colors = AppTheme.colors
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }

    fun handleTap() {
        val currentTime = SystemClock.elapsedRealtime()
        tapCount = if (currentTime - lastTapTime > 2000) 1 else tapCount + 1
        lastTapTime = currentTime

        if (tapCount >= 3) {
            tapCount = 0
            onSosTriggered()
        }
    }

    val progressText = if (tapCount > 0) {
        "Đã nhấn $tapCount trên 3 lần. Nhấn tiếp để gọi."
    } else {
        "Nhấn 3 lần liên tiếp để gọi khẩn cấp"
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .shadow(
                    elevation = if (colors.isHighContrast) 0.dp else 12.dp,
                    shape = CircleShape,
                    spotColor = colors.danger,
                )
                .clip(CircleShape)
                .background(
                    if (colors.isHighContrast) {
                        Brush.radialGradient(listOf(colors.danger, colors.danger))
                    } else {
                        Brush.radialGradient(
                            listOf(Color(0xFFE04B3F), Color(0xFFC0271F), Color(0xFF8E1B15)),
                        )
                    },
                )
                .border(
                    width = 6.dp,
                    color = if (colors.isHighContrast) colors.ink else Color(0xFFFFD9D5),
                    shape = CircleShape,
                )
                .clickable(onClick = { handleTap() })
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = "Nút gọi khẩn cấp SOS. $progressText"
                    customActions = listOf(
                        CustomAccessibilityAction("Gọi khẩn cấp ngay") {
                            onSosTriggered()
                            true
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (colors.isHighContrast) colors.onAccent else Color.White,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "SOS",
                    style = MaterialTheme.typography.displaySmall,
                    color = if (colors.isHighContrast) colors.onAccent else Color.White,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = progressText,
            style = MaterialTheme.typography.bodyLarge,
            color = if (tapCount > 0) colors.danger else colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )

        if (emergencyPhone.isNotBlank()) {
            Text(
                text = "Số khẩn cấp: $emergencyPhone",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
