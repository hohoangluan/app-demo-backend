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
import com.youreyes.app.ui.components.RowCard
import com.youreyes.app.ui.components.ScreenShell
import com.youreyes.app.ui.components.SectionLabel
import com.youreyes.app.ui.theme.accentColor
import com.youreyes.app.ui.theme.accentTextColor
import com.youreyes.app.ui.theme.borderColor
import com.youreyes.app.ui.theme.dangerColor
import com.youreyes.app.ui.theme.inkColor
import com.youreyes.app.ui.theme.mutedColor
import com.youreyes.app.ui.theme.shadowColor
import com.youreyes.app.ui.theme.successColor
import com.youreyes.app.ui.theme.surfaceColor
import com.youreyes.app.ui.theme.warningColor

data class SmartFeatureItem(
    val title: String,
    val subtitle: String,
    val category: String,
    val icon: ImageVector,
    val tone: FeatureTone,
)

/**
 * A colour role, not a colour. This list is a top-level `val`, so it cannot hold
 * a theme colour — those are resolved per composition and would be wrong the
 * moment high contrast is switched on. The row resolves the role when it draws.
 */
enum class FeatureTone { ACCENT, ACCENT_STRONG, SUCCESS, WARNING, DANGER, INK }

@Composable
private fun FeatureTone.color(): Color = when (this) {
    FeatureTone.ACCENT -> accentTextColor
    FeatureTone.ACCENT_STRONG -> accentColor
    FeatureTone.SUCCESS -> successColor
    FeatureTone.WARNING -> warningColor
    FeatureTone.DANGER -> dangerColor
    FeatureTone.INK -> inkColor
}

private val glassesFeatures = listOf(
    SmartFeatureItem(
        title = "Đặt Xe Thông Minh",
        subtitle = "Tự động gọi xe công nghệ theo điểm đến yêu cầu bằng giọng nói rảnh tay, không cần thao tác điện thoại.",
        category = "Di chuyển",
        icon = Icons.Default.LocationOn,
        tone = FeatureTone.ACCENT,
    ),
    SmartFeatureItem(
        title = "Điều Hướng Âm Thanh",
        subtitle = "Hướng dẫn chỉ đường rảnh tay chi tiết từng bước qua hệ thống âm thanh phản hồi trực tiếp trên kính.",
        category = "Di chuyển",
        icon = Icons.Default.LocationOn,
        tone = FeatureTone.ACCENT_STRONG,
    ),
    SmartFeatureItem(
        title = "Phát Nhạc & Thư Giãn",
        subtitle = "Thưởng thức các bài hát yêu thích, radio và podcast chất lượng cao phát trực tiếp qua kính.",
        category = "Giải trí",
        icon = Icons.Default.PlayArrow,
        tone = FeatureTone.ACCENT,
    ),
    SmartFeatureItem(
        title = "Đọc Chữ & Sách Báo",
        subtitle = "Quét và đọc thành tiếng tự động văn bản, tài liệu, sách báo, hợp đồng và bảng hiệu giao thông.",
        category = "Hỗ trợ đọc",
        icon = Icons.Default.Info,
        tone = FeatureTone.SUCCESS,
    ),
    SmartFeatureItem(
        title = "Mô Tả Cảnh Quan AI",
        subtitle = "Phân tích không gian thực tế xung quanh, nhận diện vật cản và diễn đạt sinh động bằng lời nói.",
        category = "Thị giác AI",
        icon = Icons.Default.Face,
        tone = FeatureTone.WARNING,
    ),
    SmartFeatureItem(
        title = "Tìm Đồ Vật Thất Lạc",
        subtitle = "Định vị và thông báo hướng tìm kiếm chính xác các đồ vật cá nhân như chìa khóa, ví tiền, gậy dò.",
        category = "Thị giác AI",
        icon = Icons.Default.Search,
        tone = FeatureTone.DANGER,
    ),
    SmartFeatureItem(
        title = "Trợ Lý Chatbot AI",
        subtitle = "Trò chuyện, hỏi đáp kiến thức, hỗ trợ lập kế hoạch và giải đáp thắc mắc thông minh 24/7.",
        category = "Trợ lý AI",
        icon = Icons.Default.Star,
        tone = FeatureTone.INK,
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
                iconTone = item.tone.color(),
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
                spotColor = shadowColor,
            ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "Tính Năng Kính Your Eyes",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Black,
                    color = inkColor,
                ),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Mọi tính năng được thực hiện hoàn toàn tự động rảnh tay trên Kính Thông Minh bằng công nghệ AI tiên tiến.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = mutedColor,
                ),
            )
        }
    }
}
