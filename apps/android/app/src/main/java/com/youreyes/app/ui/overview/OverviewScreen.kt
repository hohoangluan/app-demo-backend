package com.youreyes.app.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.youreyes.app.R

@Composable
fun OverviewScreen(
    state: OverviewUiState = OverviewUiState(),
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.overview_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.overview_subtitle),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(
                    if (state.isReady) R.string.setup_ready else R.string.setup_required,
                ),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )
            StatusCard(state = state)
        }
    }
}

@Composable
private fun StatusCard(state: OverviewUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusRow(
                label = stringResource(R.string.server_label),
                value = stringResource(
                    if (state.serverConfigured) {
                        R.string.server_configured
                    } else {
                        R.string.server_not_configured
                    },
                ),
            )
            StatusRow(
                label = stringResource(R.string.device_label),
                value = stringResource(
                    if (state.deviceRegistered) {
                        R.string.device_registered
                    } else {
                        R.string.device_not_registered
                    },
                ),
            )
            StatusRow(
                label = stringResource(R.string.delivery_mode_label),
                value = state.deliveryMode,
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
