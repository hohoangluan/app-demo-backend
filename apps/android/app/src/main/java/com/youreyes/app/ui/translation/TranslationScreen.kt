package com.youreyes.app.ui.translation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.youreyes.app.ui.theme.accentColor
import com.youreyes.app.ui.theme.borderColor
import com.youreyes.app.ui.theme.inkColor
import com.youreyes.app.ui.theme.mutedColor
import com.youreyes.app.ui.theme.surfaceColor

@Composable
fun TranslationRoute(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val viewModel: TranslationViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    TranslationScreen(
        state = state,
        onSwapLanguages = viewModel::swapLanguages,
        onToggleListening = viewModel::toggleListening,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun TranslationScreen(
    state: TranslationUiState,
    onSwapLanguages: () -> Unit = {},
    onToggleListening: () -> Unit = {},
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    ScreenShell(modifier = modifier, title = "Dịch Thuật", onBack = onBack) {
        DemoBanner("Bản demo — chưa nối AI dịch thuật thật. Bấm micro sẽ hiện câu mẫu giả lập.")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            border = BorderStroke(1.dp, borderColor),
        ) {
            Row(
                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.sourceLanguage,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = inkColor),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onSwapLanguages, enabled = state.canSwapLanguages) {
                    Icon(imageVector = Icons.Default.SwapHoriz, contentDescription = "Đổi chiều", tint = accentColor)
                }
                Text(
                    text = state.targetLanguage,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = inkColor),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        GradientButton(
            text = if (state.isListening) "Đang nghe..." else "Nhấn Để Nói",
            onClick = onToggleListening,
            enabled = !state.isListening,
        )

        SectionLabel(text = "Lịch Sử Dịch")
        if (state.entries.isEmpty()) {
            EmptyTranslationCard()
        } else {
            state.entries.asReversed().forEach { entry -> TranslationEntryCard(entry) }
        }
    }
}

@Composable
private fun TranslationEntryCard(entry: TranslationEntry) {
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
            Text(
                text = entry.originalText,
                style = MaterialTheme.typography.bodyMedium.copy(color = inkColor, fontWeight = FontWeight.Bold, fontSize = 14.sp),
            )
            Text(
                text = entry.translatedText,
                style = MaterialTheme.typography.bodySmall.copy(color = accentColor, fontSize = 13.sp),
            )
        }
    }
}

@Composable
private fun EmptyTranslationCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = "Chưa có câu nào được dịch.",
            style = MaterialTheme.typography.bodySmall.copy(color = mutedColor, fontSize = 12.sp),
            modifier = Modifier.padding(16.dp),
        )
    }
}
