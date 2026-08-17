package com.youreyes.app.ui.functiontest

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.youreyes.app.actions.ActionExecutionResult
import com.youreyes.app.actions.ActionRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Runs each of the 9 contract actions locally against the same [ActionRegistry]
 * handlers the FCM push path uses ("Chế độ local demo" in project_context.md
 * section 9) — no backend round trip needed to try a function on-device.
 */
class FunctionTestViewModel(application: Application) : AndroidViewModel(application) {

    private val actionRegistry = ActionRegistry()

    private val _rows = MutableStateFlow(DEFAULT_FUNCTION_TEST_ROWS)
    val rows: StateFlow<List<FunctionTestRow>> = _rows.asStateFlow()

    fun onParamsChange(action: String, paramsJson: String) {
        _rows.update { list -> list.map { if (it.action == action) it.copy(paramsJson = paramsJson) else it } }
    }

    fun runAction(action: String) {
        _rows.update { list -> list.map { if (it.action == action) it.copy(isRunning = true, resultText = null) else it } }
        viewModelScope.launch(Dispatchers.IO) {
            val row = _rows.value.first { it.action == action }
            val handler = actionRegistry.getHandler(action)
            val outcome = runCatching { handler.execute(getApplication(), row.paramsJson) }

            val (resultText, isError) = outcome.fold(
                onSuccess = { result ->
                    when (result) {
                        is ActionExecutionResult.Success -> JSONObject(result.resultData).toString(2) to false
                        is ActionExecutionResult.Error -> JSONObject()
                            .put("code", result.errorPayload.code)
                            .put("message", result.errorPayload.message)
                            .put("details", JSONObject(result.errorPayload.details))
                            .toString(2) to true
                    }
                },
                onFailure = { error -> "Unexpected error: ${error.message}" to true },
            )

            _rows.update { list ->
                list.map {
                    if (it.action == action) it.copy(isRunning = false, resultText = resultText, isError = isError) else it
                }
            }
        }
    }
}
