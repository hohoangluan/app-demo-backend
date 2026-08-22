package com.youreyes.app.ui.functiontest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

import androidx.lifecycle.viewmodel.compose.viewModel
import com.youreyes.app.ui.components.GradientButton
import com.youreyes.app.ui.components.ScreenShell
import com.youreyes.app.ui.components.SectionLabel
import com.youreyes.app.ui.theme.borderColor
import com.youreyes.app.ui.theme.dangerColor
import com.youreyes.app.ui.theme.inkColor
import com.youreyes.app.ui.theme.shadowColor
import com.youreyes.app.ui.theme.successColor
import com.youreyes.app.ui.theme.surfaceColor

@Composable
fun FunctionTestRoute(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val viewModel: FunctionTestViewModel = viewModel()
    val rows by viewModel.rows.collectAsState()
    FunctionTestScreen(
        rows = rows,
        onParamsChange = viewModel::onParamsChange,
        onRun = viewModel::runAction,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun FunctionTestScreen(
    rows: List<FunctionTestRow>,
    onParamsChange: (String, String) -> Unit,
    onRun: (String) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    ScreenShell(modifier = modifier, title = "Kiểm thử action (nội bộ)", onBack = onBack) {
        SectionLabel(text = "Kiểm Thử 9 Native Handlers")

        rows.forEach { row ->
            FunctionTestCard(row = row, onParamsChange = onParamsChange, onRun = onRun)
        }
    }
}

@Composable
private fun FunctionTestCard(
    row: FunctionTestRow,
    onParamsChange: (String, String) -> Unit,
    onRun: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
                spotColor = shadowColor,
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${row.label} (${row.action})",
                style = MaterialTheme.typography.titleSmall.copy(color = inkColor),
            )
            OutlinedTextField(
                value = row.paramsJson,
                onValueChange = { onParamsChange(row.action, it) },
                label = { Text("params (JSON)") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            GradientButton(
                text = if (row.isRunning) "Đang thực thi..." else "Chạy Local Handler",
                onClick = { onRun(row.action) },
                enabled = !row.isRunning,
            )
            row.resultText?.let { text ->
                Text(
                    text = text,
                    color = if (row.isError) dangerColor else successColor,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}
