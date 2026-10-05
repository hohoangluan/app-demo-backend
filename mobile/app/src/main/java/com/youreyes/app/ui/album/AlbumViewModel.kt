package com.youreyes.app.ui.album

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Display-name prefix of photos saved for the glasses; the album lists only these. */
const val CAMERA_CAPTURE_NAME_PREFIX = "YourEyes_"

/**
 * Lists photos saved by this app under [CAMERA_CAPTURE_NAME_PREFIX] (not the whole
 * camera roll). Needs no media permission: Scoped Storage always lets an app read
 * back rows it inserted itself. No server action captures photos yet, so the
 * album is empty until one does.
 */
class AlbumViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AlbumUiState(isLoading = true))
    val uiState: StateFlow<AlbumUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val items = queryCaptures(getApplication())
            _uiState.update { it.copy(items = items, isLoading = false) }
        }
    }

    private fun queryCaptures(context: Context): List<AlbumItem> =
        queryCollection(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false)
            .sortedByDescending { it.takenAtMillis }

    private fun queryCollection(context: Context, collection: Uri, isVideo: Boolean): List<AlbumItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("$CAMERA_CAPTURE_NAME_PREFIX%")
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        val result = mutableListOf<AlbumItem>()
        context.contentResolver.query(collection, projection, selection, args, sortOrder)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                result.add(
                    AlbumItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        displayName = cursor.getString(nameIdx) ?: "",
                        // DATE_ADDED is stored in seconds, not milliseconds.
                        takenAtMillis = cursor.getLong(dateIdx) * 1000L,
                        isVideo = isVideo,
                    )
                )
            }
        }
        return result
    }
}
