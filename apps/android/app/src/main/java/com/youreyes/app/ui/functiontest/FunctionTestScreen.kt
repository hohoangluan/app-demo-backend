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
import com.youreyes.app.ui.theme.YourEyesBorder
import com.youreyes.app.ui.theme.YourEyesDanger
import com.youreyes.app.ui.theme.YourEyesInk
import com.youreyes.app.ui.theme.YourEyesShadow
import com.youreyes.app.ui.theme.YourEyesSuccess

@Composable
fun FunctionTestRoute(modifier: Modifier = Modifier) {
    val viewModel: FunctionTestViewModel = viewModel()
    val rows by viewModel.rows.collectAsState()
    FunctionTestScreen(
        rows = rows,
        onParamsChange = viewModel::onParamsChange,
        onRun = viewModel::runAction,
        modifier = modifier,
    )
}

@Composable
fun FunctionTestScreen(
    rows: List<FunctionTestRow>,
    onParamsChange: (String, String) -> Unit,
    onRun: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenShell(modifier = modifier, title = "Developer Action Testing") {
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
                spotColor = YourEyesShadow,
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, YourEyesBorder),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${row.label} (${row.action})",
                style = MaterialTheme.typography.titleSmall.copy(color = YourEyesInk),
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
                    color = if (row.isError) YourEyesDanger else YourEyesSuccess,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}
