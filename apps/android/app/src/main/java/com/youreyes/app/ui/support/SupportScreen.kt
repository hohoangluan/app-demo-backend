package com.youreyes.app.ui.support

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youreyes.app.ui.components.GradientButton
import com.youreyes.app.ui.components.ScreenShell
import com.youreyes.app.ui.components.SectionLabel
import com.youreyes.app.ui.theme.accentColor
import com.youreyes.app.ui.theme.accentSoftColor
import com.youreyes.app.ui.theme.accentTextColor
import com.youreyes.app.ui.theme.borderColor
import com.youreyes.app.ui.theme.dangerColor
import com.youreyes.app.ui.theme.mutedColor
import com.youreyes.app.ui.theme.successColor
import com.youreyes.app.ui.theme.surfaceColor

@Composable
fun SupportRoute(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val viewModel: SupportViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }

    SupportScreen(
        state = state,
        onCategoryChange = viewModel::onCategoryChange,
        onMessageChange = viewModel::onMessageChange,
        onSubmit = viewModel::submit,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun SupportScreen(
    state: SupportUiState,
    onCategoryChange: (String) -> Unit = {},
    onMessageChange: (String) -> Unit = {},
    onSubmit: () -> Unit = {},
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    ScreenShell(modifier = modifier, title = "Hỗ trợ và góp ý", onBack = onBack) {
        if (!state.isLoggedIn) {
            Text(
                text = "Đăng nhập ở mục \"Tài Khoản Đăng Nhập\" trong Hồ sơ để gửi yêu cầu hỗ trợ.",
                style = MaterialTheme.typography.bodySmall.copy(color = mutedColor),
            )
        }

        SectionLabel(text = "Loại Yêu Cầu")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SupportUiState.CATEGORIES.forEach { (value, label) ->
                CategoryChip(
                    text = label,
                    isSelected = state.category == value,
                    enabled = state.isLoggedIn && !state.isLoading,
                    onClick = { onCategoryChange(value) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            border = BorderStroke(1.dp, borderColor),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.messageText,
                    onValueChange = onMessageChange,
                    label = { Text("Nội dung") },
                    enabled = state.isLoggedIn && !state.isLoading,
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${state.messageText.length}/${SupportUiState.MAX_MESSAGE_LENGTH}",
                    style = MaterialTheme.typography.bodySmall.copy(color = mutedColor),
                    modifier = Modifier.fillMaxWidth(),
                )
                GradientButton(
                    text = if (state.isLoading) "Đang gửi..." else "Gửi",
                    onClick = onSubmit,
                    enabled = state.canSubmit,
                )
                if (state.statusMessage.isNotBlank()) {
                    Text(
                        text = state.statusMessage,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (state.isError) dangerColor else successColor,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(
    text: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                when {
                    !enabled -> accentSoftColor.copy(alpha = 0.5f)
                    isSelected -> accentColor
                    else -> accentSoftColor
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Bold,
                color = if (isSelected && enabled) Color.White else accentTextColor.copy(alpha = if (enabled) 1f else 0.6f),
            ),
        )
    }
}
