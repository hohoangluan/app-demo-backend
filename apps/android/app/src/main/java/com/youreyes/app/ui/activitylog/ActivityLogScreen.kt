package com.youreyes.app.ui.activitylog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
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
fun ActivityLogRoute(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val viewModel: ActivityLogViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    ActivityLogScreen(state = state, onRefresh = viewModel::refresh, modifier = modifier)
}

@Composable
fun ActivityLogScreen(
    state: ActivityLogUiState,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    ScreenShell(modifier = modifier, title = "Lịch sử hoạt động", onBack = onBack) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(text = "Các Lệnh Kính Đã Gửi Tới Điện Thoại")
            IconButton(onClick = onRefresh) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Làm mới", tint = accentColor)
            }
        }

        when {
            state.isLoading && state.rows.isEmpty() -> LoadingCard()
            state.isEmpty -> EmptyCard()
            else -> state.rows.forEach { row -> ActivityLogItemCard(row) }
        }
    }
}

@Composable
private fun LoadingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(color = accentColor)
        }
    }
}

@Composable
private fun EmptyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Chưa có hoạt động nào",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = inkColor,
                ),
            )
            Text(
                text = "Các lệnh kính gửi tới điện thoại (đặt xe, phát nhạc, điều hướng, khẩn cấp, gọi liên hệ...) sẽ xuất hiện tại đây.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = mutedColor,
                ),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ActivityLogItemCard(row: ActivityLogRow) {
    val (statusText, statusColor) = when (row.status) {
        ActivityLogStatus.SUCCEEDED -> "Thành công" to successColor
        ActivityLogStatus.FAILED -> "Thất bại" to dangerColor
        ActivityLogStatus.PROCESSING -> "Đang xử lý" to mutedColor
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 3.dp, shape = RoundedCornerShape(16.dp), spotColor = shadowColor),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.actionLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = inkColor,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Black,
                        color = statusColor,
                    ),
                )
            }
            Text(
                text = row.receivedAtText,
                style = MaterialTheme.typography.bodySmall.copy(color = mutedColor, fontSize = 11.sp),
            )
            Text(
                text = row.summary,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = mutedColor,
                ),
            )
        }
    }
}
