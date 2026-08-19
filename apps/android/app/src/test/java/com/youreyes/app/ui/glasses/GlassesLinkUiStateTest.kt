package com.youreyes.app.ui.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesLinkUiStateTest {
    @Test
    fun `cannot submit by default`() {
        assertFalse(GlassesLinkUiState().canSubmit)
    }

    @Test
    fun `cannot submit while any required field is blank`() {
        val filled = GlassesLinkUiState(
            serverUrl = "https://app.visioncare-host.uk",
            bearerToken = "token-1",
            userId = "user-100",
            glassesDeviceId = "glasses-abc",
        )
        assertTrue(filled.canSubmit)

        assertFalse(filled.copy(serverUrl = "").canSubmit)
        assertFalse(filled.copy(bearerToken = "").canSubmit)
        assertFalse(filled.copy(userId = "").canSubmit)
        assertFalse(filled.copy(glassesDeviceId = "").canSubmit)
    }

    @Test
    fun `cannot submit while a request is already loading`() {
        val filled = GlassesLinkUiState(
            serverUrl = "https://app.visioncare-host.uk",
            bearerToken = "token-1",
            userId = "user-100",
            glassesDeviceId = "glasses-abc",
            isLoading = true,
        )
        assertFalse(filled.canSubmit)
    }

    @Test
    fun `can unlink without a glasses device id, unlike submit`() {
        val filled = GlassesLinkUiState(
            serverUrl = "https://app.visioncare-host.uk",
            bearerToken = "token-1",
            userId = "user-100",
            glassesDeviceId = "",
        )
        assertTrue(filled.canUnlink)
        assertFalse(filled.canSubmit)
    }

    @Test
    fun `cannot unlink while any required field is blank`() {
        val filled = GlassesLinkUiState(
            serverUrl = "https://app.visioncare-host.uk",
            bearerToken = "token-1",
            userId = "user-100",
        )
        assertTrue(filled.canUnlink)

        assertFalse(filled.copy(serverUrl = "").canUnlink)
        assertFalse(filled.copy(bearerToken = "").canUnlink)
        assertFalse(filled.copy(userId = "").canUnlink)
    }

    @Test
    fun `cannot unlink while a request is already loading`() {
        val filled = GlassesLinkUiState(
            serverUrl = "https://app.visioncare-host.uk",
            bearerToken = "token-1",
            userId = "user-100",
            isLoading = true,
        )
        assertFalse(filled.canUnlink)
    }
}
