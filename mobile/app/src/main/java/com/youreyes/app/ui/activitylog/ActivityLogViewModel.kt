package com.youreyes.app.ui.activitylog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.youreyes.app.command.CommandStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reads the on-device `commands` table via [CommandStore] — already populated by
 * [com.youreyes.app.command.CommandDispatcher] on every push command it processes —
 * so this screen needs no new backend endpoint or network call.
 */
class ActivityLogViewModel(application: Application) : AndroidViewModel(application) {

    private val dbHelper = CommandStore(application)

    private val _uiState = MutableStateFlow(ActivityLogUiState(isLoading = true))
    val uiState: StateFlow<ActivityLogUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads local command history; call when returning to this screen so recently executed commands show up. */
    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val rows = dbHelper.allCommands().map { it.toActivityLogRow() }
            _uiState.update { it.copy(rows = rows, isLoading = false) }
        }
    }
}
