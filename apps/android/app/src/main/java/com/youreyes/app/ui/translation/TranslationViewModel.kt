package com.youreyes.app.ui.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI mock for the real-time translation feature seen on Rokid Glasses (video
 * reference, 2026-08-21 product decision — "làm UI mock trước"). `project_context.md`
 * has no `translate` action in the Public API contract yet, so this simulates
 * STT+translate with a canned phrase after a short delay instead of a real call.
 * [TranslationScreen] shows [com.youreyes.app.ui.components.DemoBanner] so this never
 * reads as a working feature. The state shape (source/target language, timestamped
 * entries) is the real, intended shape — swap [MOCK_PHRASES] for a real backend call
 * once a `translate` action exists; no other change needed.
 */
class TranslationViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(TranslationUiState())
    val uiState: StateFlow<TranslationUiState> = _uiState.asStateFlow()

    private var nextId = 1L

    fun onSourceLanguageChange(value: String) = _uiState.update { it.copy(sourceLanguage = value) }
    fun onTargetLanguageChange(value: String) = _uiState.update { it.copy(targetLanguage = value) }

    fun swapLanguages() {
        if (!_uiState.value.canSwapLanguages) return
        _uiState.update { it.withLanguagesSwapped() }
    }

    fun toggleListening() {
        if (_uiState.value.isListening) return
        _uiState.update { it.copy(isListening = true) }
        viewModelScope.launch {
            delay(MOCK_LISTEN_DELAY_MS)
            val phrase = MOCK_PHRASES[(nextId % MOCK_PHRASES.size).toInt()]
            _uiState.update {
                it.copy(
                    isListening = false,
                    entries = it.entries + TranslationEntry(
                        id = nextId++,
                        originalText = phrase.first,
                        translatedText = phrase.second,
                        timestampMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    companion object {
        private const val MOCK_LISTEN_DELAY_MS = 1500L
        private val MOCK_PHRASES = listOf(
            "Xin chào, rất vui được gặp bạn" to "Hello, nice to meet you",
            "Nhà vệ sinh ở đâu?" to "Where is the restroom?",
            "Bạn có thể nói chậm hơn không?" to "Could you speak more slowly?",
            "Cảm ơn bạn rất nhiều" to "Thank you very much",
        )
    }
}
