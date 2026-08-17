package com.youreyes.app.ui.features

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youreyes.app.ui.components.RowCard
import com.youreyes.app.ui.components.ScreenShell
import com.youreyes.app.ui.components.SectionLabel
import com.youreyes.app.ui.theme.YourEyesBlue
import com.youreyes.app.ui.theme.YourEyesBorder
import com.youreyes.app.ui.theme.YourEyesCyan
import com.youreyes.app.ui.theme.YourEyesDanger
import com.youreyes.app.ui.theme.YourEyesInk
import com.youreyes.app.ui.theme.YourEyesMuted
import com.youreyes.app.ui.theme.YourEyesNavy
import com.youreyes.app.ui.theme.YourEyesShadow
import com.youreyes.app.ui.theme.YourEyesSuccess
import com.youreyes.app.ui.theme.YourEyesTeal
import com.youreyes.app.ui.theme.YourEyesWarning

data class SmartFeatureItem(
    val title: String,
    val subtitle: String,
    val category: String,
    val icon: ImageVector,
    val tone: Color,
)

private val glassesFeatures = listOf(
    SmartFeatureItem(
        title = "🚖 Đặt Xe Thông Minh",
        subtitle = "Tự động gọi xe công nghệ theo điểm đến yêu cầu bằng giọng nói rảnh tay, không cần thao tác điện thoại.",
        category = "Di chuyển",
        icon = Icons.Default.LocationOn,
        tone = YourEyesBlue,
    ),
    SmartFeatureItem(
        title = "🗺️ Điều Hướng Âm Thanh",
        subtitle = "Hướng dẫn chỉ đường rảnh tay chi tiết từng bước qua hệ thống âm thanh phản hồi trực tiếp trên kính.",
        category = "Di chuyển",
        icon = Icons.Default.LocationOn,
        tone = YourEyesCyan,
    ),
    SmartFeatureItem(
        title = "🎵 Phát Nhạc & Thư Giãn",
        subtitle = "Thưởng thức các bài hát yêu thích, radio và podcast chất lượng cao phát trực tiếp qua kính.",
        category = "Giải trí",
        icon = Icons.Default.PlayArrow,
        tone = YourEyesTeal,
    ),
    SmartFeatureItem(
        title = "📖 Đọc Chữ & Sách Báo",
        subtitle = "Quét và đọc thành tiếng tự động văn bản, tài liệu, sách báo, hợp đồng và bảng hiệu giao thông.",
        category = "Hỗ trợ đọc",
        icon = Icons.Default.Info,
        tone = YourEyesSuccess,
    ),
    SmartFeatureItem(
        title = "👁️ Mô Tả Cảnh Quan AI",
        subtitle = "Phân tích không gian thực tế xung quanh, nhận diện vật cản và diễn đạt sinh động bằng lời nói.",
        category = "Thị giác AI",
        icon = Icons.Default.Face,
        tone = YourEyesWarning,
    ),
    SmartFeatureItem(
        title = "🔍 Tìm Đồ Vật Thất Lạc",
        subtitle = "Định vị và thông báo hướng tìm kiếm chính xác các đồ vật cá nhân như chìa khóa, ví tiền, gậy dò.",
        category = "Thị giác AI",
        icon = Icons.Default.Search,
        tone = YourEyesDanger,
    ),
    SmartFeatureItem(
        title = "🤖 Trợ Lý Chatbot AI",
        subtitle = "Trò chuyện, hỏi đáp kiến thức, hỗ trợ lập kế hoạch và giải đáp thắc mắc thông minh 24/7.",
        category = "Trợ lý AI",
        icon = Icons.Default.Star,
        tone = YourEyesNavy,
    ),
)

@Composable
fun FeaturesScreen(
    modifier: Modifier = Modifier,
) {
    ScreenShell(modifier = modifier) {
        // Header Banner
        FeaturesBanner()

        SectionLabel(text = "Tính Năng Cung Cấp Bởi Kính Thông Minh")

        glassesFeatures.forEach { item ->
            RowCard(
                title = item.title,
                subtitle = item.subtitle,
                icon = item.icon,
                iconTone = item.tone,
                onClick = null, // Features executed by glasses directly
            )
        }
    }
}

@Composable
private fun FeaturesBanner() {
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
                text = "Tính Năng Kính Your Eyes",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Black,
                    color = YourEyesInk,
                    fontSize = 19.sp,
                ),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Mọi tính năng được thực hiện hoàn toàn tự động rảnh tay trên Kính Thông Minh bằng công nghệ AI tiên tiến.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = YourEyesMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}
