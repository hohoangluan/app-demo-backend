package com.innostar.appdemo.ui.overview

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
        assertFalse(OverviewUiState(serverConfigured = true).isReady)
        assertFalse(OverviewUiState(deviceRegistered = true).isReady)

        val state = OverviewUiState(
            serverConfigured = true,
            deviceRegistered = true,
        )

        assertTrue(state.isReady)
    }
}
