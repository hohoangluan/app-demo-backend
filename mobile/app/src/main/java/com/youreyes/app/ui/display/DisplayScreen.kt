package com.youreyes.app.ui.display

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.youreyes.app.ui.components.MinTouchTarget
import com.youreyes.app.ui.components.ScreenShell
import com.youreyes.app.ui.components.SectionLabel
import com.youreyes.app.ui.theme.AppTheme
import com.youreyes.app.ui.theme.DisplaySettings
import com.youreyes.app.ui.theme.TypeScale

/**
 * Hiển thị — text size and contrast.
 *
 * These two settings already existed. `PreferencesUiState` carried
 * `fontSizeOption` and `highContrast`, the backend's preferences schema stored
 * them, and the app sent the user's answer to the server and then rendered
 * identically no matter what they picked. This screen is where they start
 * meaning something, and it is deliberately the one screen that demonstrates
 * itself: every control on it redraws at the size and contrast being chosen, on
 * the frame it is chosen.
 *
 * It is reachable without logging in. Someone who cannot read the login form is
 * exactly the person who needs this first.
 */
@Composable
fun DisplayRoute(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val fontSizeOption by DisplaySettings.fontSizeOption.collectAsState()
    val highContrast by DisplaySettings.highContrast.collectAsState()

    DisplayScreen(
        fontSizeOption = fontSizeOption,
        highContrast = highContrast,
        onFontSizeChange = DisplaySettings::setFontSizeOption,
        onHighContrastChange = DisplaySettings::setHighContrast,
        modifier = modifier,
        onBack = onBack,
    )
}

@Composable
fun DisplayScreen(
    fontSizeOption: String,
    highContrast: Boolean,
    onFontSizeChange: (String) -> Unit,
    onHighContrastChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors

    ScreenShell(modifier = modifier, title = "Hiển thị", onBack = onBack) {
        Text(
            text = "Thay đổi ở đây áp dụng ngay cho toàn bộ ứng dụng. " +
                "Cài đặt được lưu trên máy, và đồng bộ lên tài khoản khi bạn đăng nhập.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.inkSecondary,
        )

        SectionLabel(text = "Cỡ chữ")

        FontSizeOptions(
            selected = fontSizeOption,
            onSelect = onFontSizeChange,
        )

        SectionLabel(text = "Tương phản cao")

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(
                    width = if (colors.isHighContrast) 2.dp else 1.dp,
                    color = colors.border,
                    shape = RoundedCornerShape(20.dp),
                )
                .defaultMinSize(minHeight = 76.dp)
                .clickable(
                    role = Role.Switch,
                    onClick = { onHighContrastChange(!highContrast) },
                )
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        "Tương phản cao. Nền đen, chữ trắng và vàng, bỏ mọi màu nhạt."
                    stateDescription = if (highContrast) "đang bật" else "đang tắt"
                }
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (colors.isHighContrast) colors.surfaceSunken
                        else colors.accentText.copy(alpha = 0.12f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Contrast,
                    contentDescription = null,
                    tint = if (colors.isHighContrast) colors.accent else colors.accentText,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Tương phản cao",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                )
                Text(
                    text = "Nền đen, chữ trắng và vàng. Bỏ mọi màu nhạt và đổ bóng.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = highContrast,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accentText,
                    uncheckedTrackColor = colors.surfaceSunken,
                    uncheckedBorderColor = colors.border,
                ),
            )
        }

        SectionLabel(text = "Xem thử")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(
                    width = if (colors.isHighContrast) 2.dp else 1.dp,
                    color = colors.border,
                    shape = RoundedCornerShape(20.dp),
                )
                .padding(18.dp),
        ) {
            Text(
                text = "Kính đã kết nối",
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Đây là cỡ chữ và màu sắc bạn sẽ thấy trên mọi màn hình. " +
                    "Nếu dòng này còn khó đọc, hãy chọn cỡ chữ To và bật Tương phản cao.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.inkSecondary,
            )
        }
    }
}

/**
 * The three sizes, each rendered at its own scale so the choice is legible from
 * the choice itself rather than from the word "Nhỏ". A chip row would fit on one
 * line; it would also make all three options the same size, which defeats the
 * purpose of the control.
 */
@Composable
private fun FontSizeOptions(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val colors = AppTheme.colors
    val options = listOf(
        TypeScale.SMALL to "Chữ nhỏ, thấy được nhiều nội dung hơn",
        TypeScale.MEDIUM to "Cỡ mặc định",
        TypeScale.LARGE to "Chữ lớn, dễ đọc nhất",
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        options.forEach { (option, description) ->
            val isSelected = option == selected
            val previewScale = TypeScale.multiplierFor(option)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isSelected) colors.accentSoft else colors.surface)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) colors.accentText else colors.border,
                        shape = RoundedCornerShape(20.dp),
                    )
                    .defaultMinSize(minHeight = MinTouchTarget)
                    .clickable(role = Role.RadioButton, onClick = { onSelect(option) })
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Cỡ chữ $option. $description"
                        stateDescription = if (isSelected) "đang chọn" else "chưa chọn"
                    }
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = MaterialTheme.typography.titleLarge.fontSize * previewScale,
                        ),
                        color = colors.ink,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (isSelected) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = colors.accentText,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }
}
