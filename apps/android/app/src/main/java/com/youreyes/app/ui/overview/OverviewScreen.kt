package com.youreyes.app.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youreyes.app.ui.components.MiniStat
import com.youreyes.app.ui.components.RowCard
import com.youreyes.app.ui.components.ScreenShell
import com.youreyes.app.ui.components.SectionLabel
import com.youreyes.app.ui.theme.YourEyesBorder
import com.youreyes.app.ui.theme.YourEyesButtonGradientEnd
import com.youreyes.app.ui.theme.YourEyesButtonGradientStart
import com.youreyes.app.ui.theme.YourEyesCyan
import com.youreyes.app.ui.theme.YourEyesInk
import com.youreyes.app.ui.theme.YourEyesMintSoft
import com.youreyes.app.ui.theme.YourEyesMuted
import com.youreyes.app.ui.theme.YourEyesNavy
import com.youreyes.app.ui.theme.YourEyesShadow
import com.youreyes.app.ui.theme.YourEyesSuccess
import com.youreyes.app.ui.theme.YourEyesTeal

@Composable
fun OverviewRoute(
    modifier: Modifier = Modifier,
    onNavigateToFeatures: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
) {
    val viewModel: OverviewViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    OverviewScreen(
        state = state,
        onNavigateToFeatures = onNavigateToFeatures,
        onNavigateToProfile = onNavigateToProfile,
        modifier = modifier,
    )
}

@Composable
fun OverviewScreen(
    state: OverviewUiState,
    onNavigateToFeatures: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    ScreenShell(modifier = modifier) {
        // Logo & Welcome Banner
        HeroBanner()

        // Feature Badges
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FeatureChip(
                label = "AI đồng hành",
                icon = Icons.Default.Star,
                modifier = Modifier.weight(1f),
            )
            FeatureChip(
                label = "Voice Support",
                icon = Icons.Default.Notifications,
                tone = YourEyesSuccess,
                modifier = Modifier.weight(1f),
            )
        }

        // Glasses Status Card
        DeviceStatusCard(
            deviceId = state.deviceId,
            userId = state.userId,
            isRegistered = state.isRegistered,
            onConfigureClick = onNavigateToProfile,
        )

        // Mini Stats Summary
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MiniStat(
                label = "Tài khoản",
                value = state.userId,
                tone = YourEyesCyan,
                modifier = Modifier.weight(1f),
            )
            MiniStat(
                label = "Trạng thái",
                value = if (state.isRegistered) "Sẵn sàng" else "Chưa nối",
                tone = if (state.isRegistered) YourEyesSuccess else YourEyesTeal,
                modifier = Modifier.weight(1f),
            )
            MiniStat(
                label = "Bảo mật",
                value = "OK",
                tone = YourEyesSuccess,
                modifier = Modifier.weight(1f),
            )
        }

        SectionLabel(text = "Trợ lý AI Kính Thông Minh")

        RowCard(
            title = "Khám phá Tính Năng Kính",
            subtitle = "Đặt xe, Đọc chữ, Điều hướng, Mô tả cảnh & Trợ lý Chatbot AI",
            icon = Icons.Default.Star,
            iconTone = YourEyesCyan,
            onClick = onNavigateToFeatures,
        )

        RowCard(
            title = "Hệ thống An Toàn & SOS",
            subtitle = "Kích hoạt báo động khẩn cấp & liên hệ người thân rảnh tay",
            icon = Icons.Default.Favorite,
            iconTone = YourEyesTeal,
            onClick = onNavigateToProfile,
        )
    }
}

@Composable
private fun HeroBanner() {
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
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(id = com.youreyes.app.R.drawable.your_eyes_logo),
                contentDescription = "YOUR EYES",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Chào mừng đến với Your Eyes",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Black,
                    color = YourEyesInk,
                    fontSize = 20.sp,
                ),
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Trợ lý AI thông minh giúp nghe - nhận biết - hỗ trợ người khiếm thị rảnh tay trong cuộc sống hằng ngày.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = YourEyesMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}

@Composable
private fun FeatureChip(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: Color = YourEyesCyan,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White)
            .border(1.dp, YourEyesBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = YourEyesNavy,
                    fontSize = 12.sp,
                ),
            )
        }
    }
}

@Composable
private fun DeviceStatusCard(
    deviceId: String,
    userId: String,
    isRegistered: Boolean,
    onConfigureClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(18.dp),
                spotColor = YourEyesShadow,
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, YourEyesBorder),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Kính Your Eyes",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            color = YourEyesInk,
                            fontSize = 16.sp,
                        ),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isRegistered) YourEyesSuccess else YourEyesMuted)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isRegistered) "● Đã kết nối với backend" else "● Chưa kết nối",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isRegistered) YourEyesSuccess else YourEyesMuted,
                                fontSize = 12.sp,
                            ),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(YourEyesButtonGradientStart, YourEyesButtonGradientEnd)
                            )
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "Serial: $deviceId",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 11.sp,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "ID Tài khoản: $userId",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = YourEyesMuted,
                        fontSize = 12.sp,
                    ),
                )
                Text(
                    text = "Phiên bản: v2.1.4",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = YourEyesMuted,
                        fontSize = 12.sp,
                    ),
                )
            }
        }
    }
}
