package com.youreyes.app.ui.meeting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youreyes.app.ui.components.DemoBanner
import com.youreyes.app.ui.components.GradientButton
import com.youreyes.app.ui.components.SectionLabel
import com.youreyes.app.ui.components.ScreenShell
import com.youreyes.app.ui.theme.borderColor
import com.youreyes.app.ui.theme.dangerColor
import com.youreyes.app.ui.theme.inkColor
import com.youreyes.app.ui.theme.mutedColor
import com.youreyes.app.ui.theme.surfaceColor

@Composable
fun MeetingRoute(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val viewModel: MeetingViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    MeetingScreen(
        state = state,
        onStartRecording = viewModel::startRecording,
        onStopRecording = viewModel::stopRecording,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun MeetingScreen(
    state: MeetingUiState,
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    ScreenShell(modifier = modifier, title = "Biên bản họp", onBack = onBack) {
        DemoBanner("Bản demo — chưa nối AI ghi âm/phiên âm thật. Nội dung transcript bên dưới là câu mẫu giả lập theo thời gian.")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            border = BorderStroke(1.dp, borderColor),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (state.isRecording) "Đang ghi âm..." else "Sẵn sàng ghi âm",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = inkColor),
                    )
                    Text(
                        text = formatDuration(state.elapsedSeconds),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            color = if (state.isRecording) dangerColor else mutedColor,
                        ),
                    )
                }

                if (state.liveTranscript.isNotBlank()) {
                    Text(
                        text = state.liveTranscript,
                        style = MaterialTheme.typography.bodySmall.copy(color = mutedColor),
                    )
                }

                GradientButton(
                    text = if (state.isRecording) "Dừng Ghi Âm" else "Bắt Đầu Ghi Âm",
                    icon = if (state.isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    onClick = if (state.isRecording) onStopRecording else onStartRecording,
                )
            }
        }

        SectionLabel(text = "Biên Bản Đã Lưu")
        if (state.isEmpty) {
            EmptyMeetingCard()
        } else {
            state.records.forEach { record -> MeetingRecordCard(record) }
        }
    }
}

@Composable
private fun MeetingRecordCard(record: MeetingRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = inkColor),
                )
                Text(
                    text = formatDuration(record.durationSeconds),
                    style = MaterialTheme.typography.bodySmall.copy(color = mutedColor),
                )
            }
            Text(
                text = record.transcriptSnippet,
                style = MaterialTheme.typography.bodySmall.copy(color = mutedColor),
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun EmptyMeetingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = "Chưa có biên bản họp nào được lưu.",
            style = MaterialTheme.typography.bodySmall.copy(color = mutedColor),
            modifier = Modifier.padding(16.dp),
        )
    }
}
