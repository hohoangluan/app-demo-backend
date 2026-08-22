package com.youreyes.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youreyes.app.ui.components.GradientButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import com.youreyes.app.ui.components.RowCard
import com.youreyes.app.ui.devmode.DeveloperMode
import com.youreyes.app.ui.components.ScreenShell
import com.youreyes.app.ui.components.SectionLabel
import com.youreyes.app.ui.overview.OverviewUiState
import com.youreyes.app.ui.overview.OverviewViewModel
import com.youreyes.app.ui.preferences.PreferencesUiState
import com.youreyes.app.ui.preferences.PreferencesViewModel
import com.youreyes.app.ui.theme.accentColor
import com.youreyes.app.ui.theme.accentSoftColor
import com.youreyes.app.ui.theme.accentTextColor
import com.youreyes.app.ui.theme.borderColor
import com.youreyes.app.ui.theme.dangerColor
import com.youreyes.app.ui.theme.inkColor
import com.youreyes.app.ui.theme.mutedColor
import com.youreyes.app.ui.theme.shadowColor
import com.youreyes.app.ui.theme.successColor
import com.youreyes.app.ui.theme.surfaceColor

@Composable
fun ProfileRoute(
    modifier: Modifier = Modifier,
    onNavigateToDevTest: () -> Unit = {},
    onNavigateToAuth: () -> Unit = {},
    onNavigateToSupport: () -> Unit = {},
    onNavigateToDisplay: () -> Unit = {},
    onNavigateToGlasses: () -> Unit = {},
) {
    val overviewViewModel: OverviewViewModel = viewModel()
    val state by overviewViewModel.uiState.collectAsState()
    // Picks up a login that happened on the Tài Khoản tab (see
    // OverviewViewModel.refreshUserId doc) so the user_id shown/used here below
    // reflects the real logged-in identity without needing an app restart.
    LaunchedEffect(Unit) { overviewViewModel.refreshUserId() }

    val developerMode by DeveloperMode.enabled.collectAsState()

    val preferencesViewModel: PreferencesViewModel = viewModel()
    val preferencesState by preferencesViewModel.uiState.collectAsState()
    // Same reason as above: a login that happened after this ViewModel was first
    // constructed would otherwise leave canEdit stuck false.
    LaunchedEffect(Unit) { preferencesViewModel.refresh() }

    ProfileScreen(
        state = state,
        onServerUrlChange = overviewViewModel::onServerUrlChange,
        onBearerTokenChange = overviewViewModel::onBearerTokenChange,
        onUserIdChange = overviewViewModel::onUserIdChange,
        onDeviceIdChange = overviewViewModel::onDeviceIdChange,
        onEmergencyContactChange = overviewViewModel::onEmergencyContactChange,
        onGenerateNewIds = overviewViewModel::generateNewIds,
        onRegisterClick = overviewViewModel::register,
        onNavigateToDevTest = onNavigateToDevTest,
        onNavigateToAuth = onNavigateToAuth,
        onNavigateToSupport = onNavigateToSupport,
        onNavigateToDisplay = onNavigateToDisplay,
        onNavigateToGlasses = onNavigateToGlasses,
        developerMode = developerMode,
        preferencesState = preferencesState,
        onFontSizeChange = preferencesViewModel::onFontSizeChange,
        onVoiceChange = preferencesViewModel::onVoiceChange,
        onHighContrastChange = preferencesViewModel::onHighContrastChange,
        onHapticsChange = preferencesViewModel::onHapticsChange,
        modifier = modifier,
    )
}

@Composable
fun ProfileScreen(
    state: OverviewUiState,
    onServerUrlChange: (String) -> Unit = {},
    onBearerTokenChange: (String) -> Unit = {},
    onUserIdChange: (String) -> Unit = {},
    onDeviceIdChange: (String) -> Unit = {},
    onEmergencyContactChange: (String) -> Unit = {},
    onGenerateNewIds: () -> Unit = {},
    onRegisterClick: () -> Unit = {},
    onNavigateToDevTest: () -> Unit = {},
    onNavigateToAuth: () -> Unit = {},
    onNavigateToSupport: () -> Unit = {},
    onNavigateToDisplay: () -> Unit = {},
    onNavigateToGlasses: () -> Unit = {},
    developerMode: Boolean = false,
    preferencesState: PreferencesUiState = PreferencesUiState(),
    onFontSizeChange: (String) -> Unit = {},
    onVoiceChange: (String) -> Unit = {},
    onHighContrastChange: (Boolean) -> Unit = {},
    onHapticsChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    ScreenShell(modifier = modifier) {
        // User Header Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 6.dp,
                    shape = RoundedCornerShape(22.dp),
                    spotColor = shadowColor,
                ),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(accentSoftColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(30.dp),
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Người dùng Your Eyes",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            color = inkColor,
                        ),
                    )
                    Text(
                        text = "ID: ${state.userId} | Serial: ${state.deviceId}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = mutedColor,
                        ),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        // Text size and contrast lead the list: they are what makes everything below
        // them readable, so they are the first thing someone who cannot read the
        // screen has to be able to find. No login required, unlike the rest.
        RowCard(
            title = "Hiển thị",
            subtitle = "Cỡ chữ và tương phản cao. Áp dụng ngay cho toàn bộ ứng dụng.",
            icon = Icons.Default.Contrast,
            onClick = onNavigateToDisplay,
        )

        RowCard(
            title = "Kính Your Eyes",
            subtitle = "Liên kết hoặc hủy liên kết cặp kính đang dùng.",
            icon = Icons.Default.Visibility,
            onClick = onNavigateToGlasses,
        )

        // Real per-user login (apps/backend/src/app/api/auth.py) — separate from the
        // user_id/device_id demo fields below, but a successful login here replaces
        // the user_id those fields show/send (see AuthViewModel's class doc).
        RowCard(
            title = "Tài Khoản Đăng Nhập",
            subtitle = "Đăng ký/đăng nhập tài khoản thật bằng số điện thoại — thay cho việc tự gõ user_id bên dưới.",
            icon = Icons.Default.AccountCircle,
            iconTone = accentColor,
            onClick = onNavigateToAuth,
        )

        RowCard(
            title = "Hỗ Trợ & Góp Ý",
            subtitle = "Gửi phản hồi hoặc yêu cầu hỗ trợ tới đội ngũ Your Eyes (cần đăng nhập).",
            icon = Icons.Default.MailOutline,
            iconTone = accentTextColor,
            onClick = onNavigateToSupport,
        )

        // Section: Config Emergency Contact (1 Single Contact)
        SectionLabel(text = "Cấu Hình Số Khẩn Cấp (1 Người Thân)")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Số điện thoại người thân nhận cuộc gọi khẩn cấp khi bấm nút SOS 3 lần:",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = mutedColor,
                    ),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.emergencyContact,
                    onValueChange = onEmergencyContactChange,
                    label = { Text("Số ĐT người thân (ví dụ: 0901234567)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Accessibility Settings — real GET/PUT /preferences (PreferencesViewModel),
        // not local-only state; disabled until logged in since the API requires a
        // real user session (see ProfileRoute / PreferencesViewModel.canEdit).
        SectionLabel(text = "Cài Đặt Trợ Năng")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!preferencesState.isLoggedIn) {
                    Text(
                        text = "Đăng nhập ở mục \"Tài Khoản Đăng Nhập\" bên trên để lưu cài đặt trợ năng.",
                        style = MaterialTheme.typography.bodySmall.copy(color = mutedColor),
                    )
                }

                // Cỡ chữ
                Column {
                    Text(
                        text = "Cỡ chữ hiển thị",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = inkColor,
                        ),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PreferencesUiState.FONT_SIZE_OPTIONS.forEach { opt ->
                            ChipButton(
                                text = opt,
                                isSelected = preferencesState.fontSizeOption == opt,
                                onClick = { onFontSizeChange(opt) },
                                enabled = preferencesState.canEdit,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // Giọng đọc
                Column {
                    Text(
                        text = "Giọng đọc phản hồi Kính",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = inkColor,
                        ),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PreferencesUiState.VOICE_OPTIONS.forEach { opt ->
                            ChipButton(
                                text = opt,
                                isSelected = preferencesState.voiceOption == opt,
                                onClick = { onVoiceChange(opt) },
                                enabled = preferencesState.canEdit,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // Tương phản cao
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Độ tương phản cao",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = inkColor,
                        ),
                    )
                    Switch(
                        checked = preferencesState.highContrast,
                        onCheckedChange = onHighContrastChange,
                        enabled = preferencesState.canEdit,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = successColor),
                    )
                }

                // Phản hồi rung
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Phản hồi rung chạm (Haptics)",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = inkColor,
                        ),
                    )
                    Switch(
                        checked = preferencesState.hapticsEnabled,
                        onCheckedChange = onHapticsChange,
                        enabled = preferencesState.canEdit,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = successColor),
                    )
                }

                if (preferencesState.message.isNotBlank()) {
                    Text(
                        text = preferencesState.message,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (preferencesState.isError) dangerColor else successColor,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
        }

        // Server URL & Registration Config
        // Both developer sections live behind DeveloperMode: they can repoint the
        // app at another server, break its pairing, or place a real phone call,
        // and none of that is recoverable by someone who cannot read what changed.
        // Seven taps on the version row below brings them back.
        if (developerMode) {
            SectionLabel(text = "Cấu Hình Server Backend & Đăng Ký")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = state.serverUrl,
                        onValueChange = onServerUrlChange,
                        label = { Text("Server URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.bearerToken,
                        onValueChange = onBearerTokenChange,
                        label = { Text("Device Bearer Token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = state.userId,
                            onValueChange = onUserIdChange,
                            label = { Text("user_id") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.deviceId,
                            onValueChange = onDeviceIdChange,
                            label = { Text("device_id") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    OutlinedButton(
                        onClick = onGenerateNewIds,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("🎲 Sinh user_id / device_id ngẫu nhiên")
                    }

                    GradientButton(
                        text = if (state.isLoading) "Đang lưu cấu hình..." else "Lưu Cấu Hình & Kết Nối Server",
                        onClick = onRegisterClick,
                        enabled = !state.isLoading,
                    )

                    if (state.registrationMessage.isNotBlank()) {
                        Text(
                            text = state.registrationMessage,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (state.isRegistered) successColor else dangerColor,
                                fontWeight = FontWeight.Bold,
                            ),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // Developer Tools
            SectionLabel(text = "Công Cụ Cho Nhà Phát Triển (Developer)")

            RowCard(
                title = "Thử nghiệm 9 Native Action Handlers",
                subtitle = "Chạy kiểm thử local các action: YouTube Music, Google Maps, Camera, Calling...",
                icon = Icons.Default.Build,
                iconTone = accentColor,
                onClick = onNavigateToDevTest,
            )
        }

        VersionRow(onRevealDeveloperMode = { DeveloperMode.setEnabled(true) })
    }
}

@Composable
private fun ChipButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                when {
                    !enabled -> accentSoftColor.copy(alpha = 0.5f)
                    isSelected -> accentColor
                    else -> accentSoftColor
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Bold,
                color = if (isSelected && enabled) Color.White else accentTextColor.copy(alpha = if (enabled) 1f else 0.6f),
            ),
        )
    }
}

/**
 * The version line, and the way back to the developer sections.
 *
 * Seven taps, the same gesture Android uses on its own build number, chosen for
 * the same reason: it cannot be reached by accident, and everyone on the team
 * already knows it. Below four taps it stays silent; from the fifth it counts
 * down out loud, which is also what makes it discoverable to a screen-reader
 * user who has been told the gesture exists.
 */
@Composable
private fun VersionRow(onRevealDeveloperMode: () -> Unit) {
    val developerMode by DeveloperMode.enabled.collectAsState()
    var taps by remember { mutableIntStateOf(0) }

    // Read from the installed package rather than BuildConfig: the build script
    // does not generate BuildConfig, and enabling it there would mean editing a
    // file another agent is actively working in.
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    val remaining = DeveloperMode.TAPS_TO_ENABLE - taps
    val hint = when {
        developerMode -> "Chế độ nhà phát triển đang bật. Chạm để tắt."
        taps in 4 until DeveloperMode.TAPS_TO_ENABLE -> "Còn $remaining lần chạm nữa."
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button) {
                if (developerMode) {
                    DeveloperMode.setEnabled(false)
                    taps = 0
                } else {
                    taps++
                    if (taps >= DeveloperMode.TAPS_TO_ENABLE) {
                        onRevealDeveloperMode()
                        taps = 0
                    }
                }
            }
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Your Eyes — phiên bản $versionName",
            style = MaterialTheme.typography.bodyMedium,
            color = mutedColor,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = accentTextColor,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
