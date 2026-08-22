package com.youreyes.app.ui.meeting

data class MeetingRecord(
    val id: Long,
    val title: String,
    val transcriptSnippet: String,
    val durationSeconds: Int,
    val recordedAtMillis: Long,
)

data class MeetingUiState(
    val isRecording: Boolean = false,
    val elapsedSeconds: Int = 0,
    val liveTranscript: String = "",
    val records: List<MeetingRecord> = emptyList(),
) {
    val isEmpty: Boolean
        get() = records.isEmpty() && !isRecording
}

/** `mm:ss`, or `h:mm:ss` past one hour — used for both the live timer and saved record durations. */
fun formatDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
