package com.youreyes.app.ui.album

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.youreyes.app.ui.components.ScreenShell
import com.youreyes.app.ui.components.SectionLabel
import com.youreyes.app.ui.theme.accentColor
import com.youreyes.app.ui.theme.borderColor
import com.youreyes.app.ui.theme.inkColor
import com.youreyes.app.ui.theme.mutedColor
import com.youreyes.app.ui.theme.surfaceColor

@Composable
fun AlbumRoute(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val viewModel: AlbumViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }
    AlbumScreen(state = state, onRefresh = viewModel::refresh, modifier = modifier)
}

@Composable
fun AlbumScreen(
    state: AlbumUiState,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // scrollable=false: a LazyVerticalGrid needs a bounded-height parent, which a
    // scrolling Column can't give it (unbounded height -> crash).
    ScreenShell(modifier = modifier, title = "Album Ảnh & Video", scrollable = false, onBack = onBack) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            SectionLabel(text = "Ảnh chụp qua kính")
            IconButton(onClick = onRefresh, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Làm mới", tint = accentColor)
            }
        }

        when {
            state.isLoading && state.items.isEmpty() -> LoadingCard()
            state.isEmpty -> EmptyCard()
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    AlbumThumbnail(item) {
                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(item.uri, if (item.isVideo) "video/*" else "image/*")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(viewIntent) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumThumbnail(item: AlbumItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceColor)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            modifier = Modifier.fillMaxSize(),
        )
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
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
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
            Icon(imageVector = Icons.Default.Photo, contentDescription = null, tint = mutedColor)
            Text(
                text = "Chưa có ảnh nào",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = inkColor),
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "Ảnh chụp qua lệnh của kính (camera_capture) sẽ xuất hiện tại đây.",
                style = MaterialTheme.typography.bodySmall.copy(color = mutedColor, fontSize = 12.sp),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
