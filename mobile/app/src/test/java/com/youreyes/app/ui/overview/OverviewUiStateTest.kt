package com.youreyes.app.ui.overview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewUiStateTest {
    @Test
    fun `setup is not ready by default`() {
        assertFalse(OverviewUiState().isReady)
    }

    @Test
    fun `setup is ready only after server and device are configured`() {
        assertFalse(OverviewUiState(serverUrl = "http://10.0.2.2:8000", isRegistered = false).isReady)
        assertFalse(OverviewUiState(serverUrl = "", isRegistered = true).isReady)

        val state = OverviewUiState(
            serverUrl = "http://10.0.2.2:8000",
            isRegistered = true,
        )

        assertTrue(state.isReady)
    }
}
