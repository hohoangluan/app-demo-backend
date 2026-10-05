package com.youreyes.app.ui.guide

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youreyes.app.ui.components.ScreenShell
import com.youreyes.app.ui.theme.borderColor
import com.youreyes.app.ui.theme.inkColor
import com.youreyes.app.ui.theme.mutedColor
import com.youreyes.app.ui.theme.surfaceColor

data class GuideSection(val title: String, val body: String)

/** One entry per real, working flow in the app — keep this list in sync as new screens/flows land. */
val guideSections = listOf(
    GuideSection(
        title = "Bắt đầu: Đăng ký / Đăng nhập",
        body = "Vào Hồ sơ → Tài Khoản Đăng Nhập, nhập số điện thoại và mật khẩu để đăng ký. " +
            "Server demo hiện gửi mã OTP qua log server (chưa nối SMS thật) — nhập đúng mã đó để xác thực.",
    ),
    GuideSection(
        title = "Liên kết kính",
        body = "Nhập mã Serial in ở gọng kính vào màn hình Pairing Kính rồi bấm Xác Nhận Liên Kết. " +
            "Có thể hủy liên kết bất cứ lúc nào bằng nút Hủy Liên Kết Kính Hiện Tại.",
    ),
    GuideSection(
        title = "Ra lệnh bằng giọng nói qua kính",
        body = "Đặt xe, phát nhạc, điều hướng, gọi liên hệ đều do kính tự động kích hoạt qua giọng nói — " +
            "điện thoại chỉ thực thi lệnh nhận được, không cần chạm vào màn hình.",
    ),
    GuideSection(
        title = "An toàn khẩn cấp (SOS)",
        body = "Vào tab An toàn, nhập số điện thoại người thân, sau đó bấm nút SOS 3 lần liên tiếp " +
            "trong vòng 2 giây để gọi khẩn cấp và gửi vị trí qua SMS.",
    ),
    GuideSection(
        title = "Lịch sử hoạt động",
        body = "Xem lại các lệnh kính đã gửi tới điện thoại (đặt xe, nhạc, điều hướng, khẩn cấp...) " +
            "tại Trang chủ → Lịch Sử Hoạt Động Của Kính.",
    ),
    GuideSection(
        title = "Album ảnh",
        body = "Ảnh chụp từ kính được lưu lại và xem lại tại Trang chủ → Album Ảnh & Video.",
    ),
    GuideSection(
        title = "Cài đặt trợ năng",
        body = "Vào Hồ sơ → Cài Đặt Trợ Năng để chỉnh cỡ chữ, giọng đọc, độ tương phản và rung. " +
            "Cần đăng nhập để lưu lại — nếu chưa đăng nhập, mục này sẽ bị khoá.",
    ),
    GuideSection(
        title = "Hỗ trợ & góp ý",
        body = "Vào Hồ sơ → Hỗ Trợ & Góp Ý để gửi phản hồi hoặc yêu cầu hỗ trợ trực tiếp tới đội ngũ Your Eyes.",
    ),
)

@Composable
fun UserGuideScreen(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    ScreenShell(modifier = modifier, title = "Hướng dẫn sử dụng", onBack = onBack) {
        guideSections.forEach { section -> GuideCard(section) }
    }
}

@Composable
private fun GuideCard(section: GuideSection) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = inkColor,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = mutedColor,
                )
            }
            if (expanded) {
                Text(
                    text = section.body,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = mutedColor,
                    ),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
