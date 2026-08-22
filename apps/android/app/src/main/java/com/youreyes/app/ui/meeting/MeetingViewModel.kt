package com.youreyes.app.ui.meeting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UI mock for the "Biên bản họp" (meeting recording + AI transcription) feature seen
 * on Rokid Glasses (video reference, 2026-08-21 product decision — "làm UI mock
 * trước"). `project_context.md` has no `meeting_record`/transcription action in the
 * Public API contract yet, so this simulates a live transcript with canned lines on a
 * timer instead of real audio capture + STT. [MeetingScreen] shows
 * [com.youreyes.app.ui.components.DemoBanner] so this never reads as a working
 * feature. Records are in-memory only (lost on process death) — deliberately not
 * persisted, since the whole transcript content here is fake.
 */
class MeetingViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MeetingUiState())
    val uiState: StateFlow<MeetingUiState> = _uiState.asStateFlow()

    private var recordingJob: Job? = null
    private var nextId = 1L

    fun startRecording() {
        if (_uiState.value.isRecording) return
        _uiState.update { it.copy(isRecording = true, elapsedSeconds = 0, liveTranscript = "") }

        recordingJob = viewModelScope.launch {
            var nextLineIndex = 0
            while (isActive) {
                delay(1_000L)
                _uiState.update { state ->
                    val elapsed = state.elapsedSeconds + 1
                    val dueForLine = elapsed % MOCK_LINE_INTERVAL_SECONDS == 0 && nextLineIndex < MOCK_TRANSCRIPT_LINES.size
                    val transcript = if (dueForLine) {
                        val line = MOCK_TRANSCRIPT_LINES[nextLineIndex++]
                        if (state.liveTranscript.isBlank()) line else "${state.liveTranscript}\n$line"
                    } else {
                        state.liveTranscript
                    }
                    state.copy(elapsedSeconds = elapsed, liveTranscript = transcript)
                }
            }
        }
    }

    fun stopRecording() {
        val state = _uiState.value
        if (!state.isRecording) return
        recordingJob?.cancel()
        recordingJob = null

        val record = MeetingRecord(
            id = nextId++,
            title = "Cuộc họp #$nextId",
            transcriptSnippet = state.liveTranscript.ifBlank { "(không có nội dung)" },
            durationSeconds = state.elapsedSeconds,
            recordedAtMillis = System.currentTimeMillis(),
        )
        _uiState.update {
            it.copy(isRecording = false, records = listOf(record) + it.records)
        }
    }

    override fun onCleared() {
        recordingJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val MOCK_LINE_INTERVAL_SECONDS = 4
        private val MOCK_TRANSCRIPT_LINES = listOf(
            "Người nói A: Chào mọi người, chúng ta bắt đầu cuộc họp nhé.",
            "Người nói B: Vâng, tuần này team đã hoàn thành module đăng nhập.",
            "Người nói A: Tốt, tuần sau tập trung vào phần thanh toán.",
            "Người nói B: Đồng ý, tôi sẽ chuẩn bị kế hoạch chi tiết.",
        )
    }
}
