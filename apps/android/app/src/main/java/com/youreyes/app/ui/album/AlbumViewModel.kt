package com.youreyes.app.ui.album

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.youreyes.app.actions.CAMERA_CAPTURE_NAME_PREFIX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Lists photos captured via the `camera_capture` action
 * ([com.youreyes.app.actions.CameraCaptureHandler]), which tags every file it saves
 * with the [CAMERA_CAPTURE_NAME_PREFIX] display-name prefix specifically so this query
 * can filter to just glasses-triggered captures instead of the phone's entire camera
 * roll. No READ_MEDIA_IMAGES/READ_MEDIA_VIDEO permission needed: every row here was
 * inserted by this app's own `ContentResolver.insert()` call, and Android's Scoped
 * Storage always lets an app read back media it owns, regardless of storage
 * permission state.
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
