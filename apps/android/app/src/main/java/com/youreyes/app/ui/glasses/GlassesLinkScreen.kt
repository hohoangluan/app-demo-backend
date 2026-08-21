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
import com.youreyes.app.ui.theme.YourEyesBorder
import com.youreyes.app.ui.theme.YourEyesCyan
import com.youreyes.app.ui.theme.YourEyesDanger
import com.youreyes.app.ui.theme.YourEyesInk
import com.youreyes.app.ui.theme.YourEyesMuted
import com.youreyes.app.ui.theme.YourEyesShadow
import com.youreyes.app.ui.theme.YourEyesSuccess

@Composable
fun GlassesLinkRoute(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val viewModel: GlassesLinkViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshUserId() }

    GlassesLinkScreen(
        state = state,
        onGlassesDeviceIdChange = viewModel::onGlassesDeviceIdChange,
        onLinkClick = viewModel::link,
        onUnlinkClick = viewModel::unlink,
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
) {
    ScreenShell(modifier = modifier, title = "Pairing Kính") {
        // Pairing Hero Banner
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 6.dp,
                    shape = RoundedCornerShape(22.dp),
                    spotColor = YourEyesShadow,
                ),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = androidx.compose.foundation.BorderStroke(1.dp, YourEyesBorder),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Liên Kết Kính Your Eyes",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        color = YourEyesInk,
                        fontSize = 19.sp,
                    ),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Nhập số Serial in ở gọng kính để liên kết kính với tài khoản của bạn " +
                        "(Tài khoản: ${state.userId.ifBlank { "chưa đăng ký" }}).",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = YourEyesMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                )
            }
        }

        PairingStatusCard(pairedDeviceId = state.pairedDeviceId)

        SectionLabel(text = "Nhập Mã Serial Kính")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = androidx.compose.foundation.BorderStroke(1.dp, YourEyesBorder),
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
                            color = if (state.message.startsWith("❌")) YourEyesDanger else YourEyesSuccess,
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
            iconTone = YourEyesCyan,
        )
    }
}

@Composable
private fun PairingStatusCard(pairedDeviceId: String) {
    val isPaired = pairedDeviceId.isNotBlank()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, YourEyesBorder),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isPaired) YourEyesSuccess else YourEyesMuted),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = if (isPaired) "Đang liên kết" else "Chưa liên kết kính nào",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = YourEyesInk, fontSize = 14.sp),
                )
                if (isPaired) {
                    Text(
                        text = "Mã kính: $pairedDeviceId",
                        style = MaterialTheme.typography.bodySmall.copy(color = YourEyesMuted, fontSize = 12.sp),
                    )
                }
            }
        }
    }
}
