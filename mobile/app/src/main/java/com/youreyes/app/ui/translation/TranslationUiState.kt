package com.youreyes.app.ui.translation

data class TranslationEntry(
    val id: Long,
    val originalText: String,
    val translatedText: String,
    val timestampMillis: Long,
)

data class TranslationUiState(
    val sourceLanguage: String = "Tiếng Việt",
    val targetLanguage: String = "English",
    val isListening: Boolean = false,
    val entries: List<TranslationEntry> = emptyList(),
) {
    val canSwapLanguages: Boolean
        get() = !isListening

    companion object {
        val SUPPORTED_LANGUAGES = listOf("Tiếng Việt", "English", "中文", "日本語", "한국어")
    }
}

/** Pure swap logic, used by [TranslationViewModel.swapLanguages]; kept separate so it's unit-testable without a ViewModel. */
fun TranslationUiState.withLanguagesSwapped(): TranslationUiState =
    copy(sourceLanguage = targetLanguage, targetLanguage = sourceLanguage)
