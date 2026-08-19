package com.youreyes.app.ui.glasses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
        modifier = modifier,
    )
}

@Composable
fun GlassesLinkScreen(
    state: GlassesLinkUiState = GlassesLinkUiState(),
    onGlassesDeviceIdChange: (String) -> Unit = {},
    onLinkClick: () -> Unit = {},
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
                    label = { Text("Số serial kính (Ví dụ: YE-2A4B-9F70)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                GradientButton(
                    text = if (state.isLoading) "Đang kết nối..." else "Xác Nhận Liên Kết Kính",
                    onClick = onLinkClick,
                    enabled = state.canSubmit && !state.isLoading,
                )

                if (state.message.isNotBlank()) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = YourEyesSuccess,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
        }

        SectionLabel(text = "Hướng Dẫn Pairing")

        RowCard(
            title = "Tìm mã Serial trên kính",
            subtitle = "Mã Serial gồm 10 ký tự được in laser sắc nét ở mặt trong gọng kính phải.",
            icon = Icons.Default.Info,
            iconTone = YourEyesCyan,
        )
    }
}
