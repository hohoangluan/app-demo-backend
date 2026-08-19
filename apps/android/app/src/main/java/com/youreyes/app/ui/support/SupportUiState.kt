package com.youreyes.app.ui.support

data class SupportUiState(
    val isLoggedIn: Boolean = false,
    val category: String = CATEGORY_FEEDBACK,
    val messageText: String = "",
    val isLoading: Boolean = false,
    val statusMessage: String = "",
    val isError: Boolean = false,
) {
    val canSubmit: Boolean
        get() = isLoggedIn &&
            !isLoading &&
            messageText.trim().isNotEmpty() &&
            messageText.length <= MAX_MESSAGE_LENGTH

    companion object {
        const val CATEGORY_FEEDBACK = "feedback"
        const val CATEGORY_SUPPORT_REQUEST = "support_request"

        /** Matches apps/backend/src/app/schemas/support.py: `message` has `max_length=2000`. */
        const val MAX_MESSAGE_LENGTH = 2000

        /** (value sent to the API, Vietnamese label) pairs, in the order shown as chips — matches `SupportTicketCategory = Literal["feedback", "support_request"]`. */
        val CATEGORIES = listOf(
            CATEGORY_FEEDBACK to "Góp ý",
            CATEGORY_SUPPORT_REQUEST to "Yêu cầu hỗ trợ",
        )
    }
}
