package com.youreyes.app.ui.community

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youreyes.app.ui.components.RowCard
import com.youreyes.app.ui.components.ScreenShell
import com.youreyes.app.ui.components.SectionLabel
import com.youreyes.app.ui.theme.accentColor
import com.youreyes.app.ui.theme.accentTextColor
import com.youreyes.app.ui.theme.borderColor
import com.youreyes.app.ui.theme.inkColor
import com.youreyes.app.ui.theme.mutedColor
import com.youreyes.app.ui.theme.shadowColor
import com.youreyes.app.ui.theme.successColor
import com.youreyes.app.ui.theme.surfaceColor

@Composable
fun CommunityScreen(
    modifier: Modifier = Modifier,
) {
    ScreenShell(modifier = modifier) {
        // Community Header Banner
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
                    text = "Cộng Đồng Your Eyes",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        color = inkColor,
                    ),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Mạng lưới kết nối, chia sẻ kinh nghiệm và hỗ trợ địa điểm rảnh tay cho cộng đồng người khiếm thị Việt Nam.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = mutedColor,
                    ),
                )
            }
        }

        SectionLabel(text = "Địa Điểm Thân Thiện & Hoạt Động")

        RowCard(
            title = "Bản đồ tuyến đường rảnh tay",
            subtitle = "Danh sách vỉa hè, xe buýt và công viên đã được xác thực an toàn bởi cộng đồng.",
            icon = Icons.Default.LocationOn,
            iconTone = accentColor,
        )

        RowCard(
            title = "Chia sẻ kinh nghiệm sử dụng Kính",
            subtitle = "Mẹo sử dụng các lệnh đọc sách, nhận diện vật thể và đặt xe nhanh.",
            icon = Icons.Default.Share,
            iconTone = accentTextColor,
        )

        RowCard(
            title = "Câu lạc bộ Âm nhạc & Sách nói",
            subtitle = "Giao lưu, chia sẻ danh sách phát nhạc và tủ sách nói trợ năng.",
            icon = Icons.Default.Favorite,
            iconTone = accentTextColor,
        )

        RowCard(
            title = "Sự kiện & Buổi gặp mặt hàng tháng",
            subtitle = "Chương trình trải nghiệm công nghệ hỗ trợ người khiếm thị.",
            icon = Icons.Default.DateRange,
            iconTone = successColor,
        )
    }
}
