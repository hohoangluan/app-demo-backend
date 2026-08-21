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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youreyes.app.ui.theme.YourEyesBorder
import com.youreyes.app.ui.theme.YourEyesButtonGradientEnd
import com.youreyes.app.ui.theme.YourEyesButtonGradientStart
import com.youreyes.app.ui.theme.YourEyesCyan
import com.youreyes.app.ui.theme.YourEyesDanger
import com.youreyes.app.ui.theme.YourEyesGradientEnd
import com.youreyes.app.ui.theme.YourEyesGradientMiddle
import com.youreyes.app.ui.theme.YourEyesGradientStart
import com.youreyes.app.ui.theme.YourEyesInk
import com.youreyes.app.ui.theme.YourEyesMintSoft
import com.youreyes.app.ui.theme.YourEyesMuted
import com.youreyes.app.ui.theme.YourEyesNavy
import com.youreyes.app.ui.theme.YourEyesShadow
import com.youreyes.app.ui.theme.YourEyesWarning

@Composable
fun ScreenShell(
    modifier: Modifier = Modifier,
    title: String? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val backgroundBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                YourEyesGradientStart,
                YourEyesGradientMiddle,
                YourEyesGradientEnd,
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (scrollable) {
                        Modifier.verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!title.isNullOrBlank()) {
                HeaderTitle(title = title)
            }
            content()
        }
    }
}

@Composable
fun HeaderTitle(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Black,
                color = YourEyesInk,
                fontSize = 17.sp,
            ),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.Black,
            color = YourEyesInk,
            fontSize = 15.sp,
        ),
        modifier = modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val gradientBrush = if (enabled) {
        Brush.horizontalGradient(
            colors = listOf(YourEyesButtonGradientStart, YourEyesButtonGradientEnd)
        )
    } else {
        Brush.horizontalGradient(
            colors = listOf(YourEyesMuted, YourEyesMuted)
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(999.dp),
                spotColor = YourEyesShadow,
            )
            .clip(RoundedCornerShape(999.dp))
            .background(gradientBrush)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
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
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    fontSize = 15.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun RowCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconTone: Color = YourEyesCyan,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
                spotColor = YourEyesShadow,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, YourEyesBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconTone.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTone,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = YourEyesInk,
                        fontSize = 14.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = YourEyesMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (trailing != null) {
                trailing()
            } else if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = YourEyesMuted,
                    modifier = Modifier.size(20.dp),
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
    tone: Color = YourEyesCyan,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(YourEyesMintSoft)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Black,
                    color = tone,
                    fontSize = 18.sp,
                ),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = YourEyesMuted,
                    fontSize = 11.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Banner flagging a screen (or part of one) as a UI mock with no real backend behind
 * it yet — e.g. Dịch Thuật/Biên Bản Họp, built ahead of a `translate`/`meeting_record`
 * action existing in the Public API contract. Keeps a demo screen from looking
 * indistinguishable from a working one.
 */
@Composable
fun DemoBanner(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(YourEyesWarning.copy(alpha = 0.15f))
            .padding(12.dp),
    ) {
        Text(
            text = "🧪 $text",
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Bold,
                color = YourEyesWarning,
                fontSize = 12.sp,
            ),
        )
    }
}

/**
 * Emergency SOS Button requiring 3 rapid taps (within 2 seconds) to activate emergency call.
 */
@Composable
fun SosButton(
    onSosTriggered: () -> Unit,
    modifier: Modifier = Modifier,
    emergencyPhone: String = "",
) {
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }

    fun handleTap() {
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastTapTime > 2000) {
            tapCount = 1
        } else {
            tapCount++
        }
        lastTapTime = currentTime

        if (tapCount >= 3) {
            tapCount = 0
            onSosTriggered()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = CircleShape,
                    spotColor = YourEyesDanger,
                )
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFF5252),
                            YourEyesDanger,
                            Color(0xFFC62828),
                        )
                    )
                )
                .border(6.dp, Color(0xFFFFCDD2), CircleShape)
                .clickable(onClick = { handleTap() }),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "SOS",
                    tint = Color.White,
                    modifier = Modifier.size(46.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "SOS",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        fontSize = 32.sp,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = if (tapCount > 0) {
                "Đã nhấn $tapCount/3 lần! Nhấn tiếp để gọi..."
            } else {
                "Nhấn 3 lần liên tiếp để gọi khẩn cấp"
            },
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                color = if (tapCount > 0) YourEyesDanger else YourEyesNavy,
                fontSize = 13.sp,
            ),
            textAlign = TextAlign.Center,
        )

        if (emergencyPhone.isNotBlank()) {
            Text(
                text = "Số khẩn cấp: $emergencyPhone",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = YourEyesMuted,
                    fontSize = 12.sp,
                ),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
