package com.youreyes.app.ui.glasses

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.youreyes.app.ui.components.RowCard
import com.youreyes.app.ui.components.ScreenShell
import com.youreyes.app.ui.components.SectionLabel
import com.youreyes.app.ui.theme.accentColor
import com.youreyes.app.ui.theme.borderColor
import com.youreyes.app.ui.theme.dangerColor
import com.youreyes.app.ui.theme.inkColor
import com.youreyes.app.ui.theme.mutedColor
import com.youreyes.app.ui.theme.shadowColor
import com.youreyes.app.ui.theme.successColor
import com.youreyes.app.ui.theme.surfaceColor

@Composable
fun GlassesLinkRoute(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val viewModel: GlassesLinkViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshUserId() }

    GlassesLinkScreen(
        state = state,
        onGlassesDeviceIdChange = viewModel::onGlassesDeviceIdChange,
        onLinkClick = viewModel::link,
        onUnlinkClick = viewModel::unlink,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun GlassesLinkScreen(
    state: GlassesLinkUiState = GlassesLinkUiState(),
    onGlassesDeviceIdChange: (String) -> Unit = {},
    onLinkClick: () -> Unit = {},
    onUnlinkClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    ScreenShell(modifier = modifier, title = "Kính Your Eyes", onBack = onBack) {
        // Pairing Hero Banner
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
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Liên Kết Kính Your Eyes",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        color = inkColor,
                    ),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Nhập số Serial in ở gọng kính để liên kết kính với tài khoản của bạn " +
                        "(Tài khoản: ${state.userId.ifBlank { "chưa đăng ký" }}).",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = mutedColor,
                    ),
                )
            }
        }

        PairingStatusCard(pairedDeviceId = state.pairedDeviceId)

        SectionLabel(text = "Nhập Mã Serial Kính")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.glassesDeviceId,
                    onValueChange = onGlassesDeviceIdChange,
                    label = { Text("Mã kính (VD: YE-2A4B-9F70 hoặc GLASSES-123)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                GradientButton(
                    text = if (state.isLoading) "Đang kết nối..." else "Xác Nhận Liên Kết Kính",
                    onClick = onLinkClick,
                    enabled = state.canSubmit && !state.isLoading,
                )

                OutlinedButton(
                    onClick = onUnlinkClick,
                    enabled = state.canUnlink,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Hủy Liên Kết Kính Hiện Tại")
                }

                if (state.message.isNotBlank()) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (state.message.startsWith("❌")) dangerColor else successColor,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
        }

        SectionLabel(text = "Hướng Dẫn Pairing")

        RowCard(
            title = "Tìm mã trên kính",
            subtitle = "Mã kính được in laser ở mặt trong gọng kính phải, hoặc do đội cấp kính cung cấp.",
            icon = Icons.Default.Info,
            iconTone = accentColor,
        )
    }
}

@Composable
private fun PairingStatusCard(pairedDeviceId: String) {
    val isPaired = pairedDeviceId.isNotBlank()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isPaired) successColor else mutedColor),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = if (isPaired) "Đang liên kết" else "Chưa liên kết kính nào",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = inkColor),
                )
                if (isPaired) {
                    Text(
                        text = "Mã kính: $pairedDeviceId",
                        style = MaterialTheme.typography.bodySmall.copy(color = mutedColor),
                    )
                }
            }
        }
    }
}
