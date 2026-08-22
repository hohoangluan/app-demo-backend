package com.youreyes.app.ui.album

import android.net.Uri

data class AlbumItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val takenAtMillis: Long,
    val isVideo: Boolean,
)

data class AlbumUiState(
    val items: List<AlbumItem> = emptyList(),
    val isLoading: Boolean = false,
) {
    val isEmpty: Boolean
        get() = items.isEmpty() && !isLoading
}
