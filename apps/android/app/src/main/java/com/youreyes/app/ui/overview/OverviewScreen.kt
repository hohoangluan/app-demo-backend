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
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youreyes.app.ui.components.MiniStat
import com.youreyes.app.ui.components.RowCard
import com.youreyes.app.ui.components.ScreenShell
import com.youreyes.app.ui.components.SectionLabel
import com.youreyes.app.ui.theme.YourEyesButtonGradientEnd
import com.youreyes.app.ui.theme.YourEyesButtonGradientStart
import com.youreyes.app.ui.theme.accentColor
import com.youreyes.app.ui.theme.accentTextColor
import com.youreyes.app.ui.theme.borderColor
import com.youreyes.app.ui.theme.inkColor
import com.youreyes.app.ui.theme.mutedColor
import com.youreyes.app.ui.theme.shadowColor
import com.youreyes.app.ui.theme.successColor
import com.youreyes.app.ui.theme.surfaceColor

@Composable
fun OverviewRoute(
    modifier: Modifier = Modifier,
    onNavigateToFeatures: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToActivityLog: () -> Unit = {},
    onNavigateToAlbum: () -> Unit = {},
    onNavigateToGuide: () -> Unit = {},
    onNavigateToTranslation: () -> Unit = {},
    onNavigateToMeeting: () -> Unit = {},
) {
    val viewModel: OverviewViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    // Picks up a login that happened on the Tài Khoản tab since this ViewModel was
    // first created (see OverviewViewModel.refreshUserId doc).
    LaunchedEffect(Unit) { viewModel.refreshUserId() }

    OverviewScreen(
        state = state,
        onNavigateToFeatures = onNavigateToFeatures,
        onNavigateToProfile = onNavigateToProfile,
        onNavigateToActivityLog = onNavigateToActivityLog,
        onNavigateToAlbum = onNavigateToAlbum,
        onNavigateToGuide = onNavigateToGuide,
        onNavigateToTranslation = onNavigateToTranslation,
        onNavigateToMeeting = onNavigateToMeeting,
        modifier = modifier,
    )
}

@Composable
fun OverviewScreen(
    state: OverviewUiState,
    onNavigateToFeatures: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToActivityLog: () -> Unit = {},
    onNavigateToAlbum: () -> Unit = {},
    onNavigateToGuide: () -> Unit = {},
    onNavigateToTranslation: () -> Unit = {},
    onNavigateToMeeting: () -> Unit = {},
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
                tone = successColor,
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
                tone = accentColor,
                modifier = Modifier.weight(1f),
            )
            MiniStat(
                label = "Trạng thái",
                value = if (state.isRegistered) "Sẵn sàng" else "Chưa nối",
                tone = if (state.isRegistered) successColor else accentTextColor,
                modifier = Modifier.weight(1f),
            )
            MiniStat(
                label = "Bảo mật",
                value = "OK",
                tone = successColor,
                modifier = Modifier.weight(1f),
            )
        }

        SectionLabel(text = "Trợ lý AI Kính Thông Minh")

        RowCard(
            title = "Khám phá Tính Năng Kính",
            subtitle = "Đặt xe, Đọc chữ, Điều hướng, Mô tả cảnh & Trợ lý Chatbot AI",
            icon = Icons.Default.Star,
            iconTone = accentColor,
            onClick = onNavigateToFeatures,
        )

        RowCard(
            title = "Hệ thống An Toàn & SOS",
            subtitle = "Kích hoạt báo động khẩn cấp & liên hệ người thân rảnh tay",
            icon = Icons.Default.Favorite,
            iconTone = accentTextColor,
            onClick = onNavigateToProfile,
        )

        RowCard(
            title = "Lịch Sử Hoạt Động Của Kính",
            subtitle = "Xem lại các lệnh gần đây: đặt xe, phát nhạc, điều hướng, khẩn cấp, gọi liên hệ...",
            icon = Icons.Default.DateRange,
            iconTone = inkColor,
            onClick = onNavigateToActivityLog,
        )

        RowCard(
            title = "Album Ảnh & Video",
            subtitle = "Xem lại ảnh đã chụp qua lệnh của kính",
            icon = Icons.Default.Photo,
            iconTone = accentTextColor,
            onClick = onNavigateToAlbum,
        )

        RowCard(
            title = "Hướng Dẫn Sử Dụng",
            subtitle = "Tìm hiểu cách dùng từng tính năng của Your Eyes",
            icon = Icons.AutoMirrored.Filled.Help,
            iconTone = accentColor,
            onClick = onNavigateToGuide,
        )

        RowCard(
            title = "Dịch Thuật (Demo)",
            subtitle = "Dịch giọng nói thời gian thực — bản demo, chưa nối AI thật",
            icon = Icons.Default.Translate,
            iconTone = accentTextColor,
            onClick = onNavigateToTranslation,
        )

        RowCard(
            title = "Biên Bản Họp (Demo)",
            subtitle = "Ghi âm & phiên âm cuộc họp — bản demo, chưa nối AI thật",
            icon = Icons.Default.Groups,
            iconTone = inkColor,
            onClick = onNavigateToMeeting,
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
                spotColor = shadowColor,
            ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
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
                    color = inkColor,
                ),
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Trợ lý AI thông minh giúp nghe - nhận biết - hỗ trợ người khiếm thị rảnh tay trong cuộc sống hằng ngày.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = mutedColor,
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
    tone: Color = accentColor,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(surfaceColor)
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
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
                    color = inkColor,
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
                spotColor = shadowColor,
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
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
                            color = inkColor,
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
                                .background(if (isRegistered) successColor else mutedColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isRegistered) "● Đã kết nối với backend" else "● Chưa kết nối",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isRegistered) successColor else mutedColor,
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
                        color = mutedColor,
                    ),
                )
                Text(
                    text = "Phiên bản: v2.1.4",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = mutedColor,
                    ),
                )
            }
        }
    }
}
