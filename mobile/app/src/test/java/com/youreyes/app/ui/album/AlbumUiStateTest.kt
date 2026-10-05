package com.youreyes.app.ui.album

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only the loading/no-items combinations are covered here: constructing a real
 * [AlbumItem] needs a real `android.net.Uri` instance, and `Uri.parse`/`Uri.EMPTY`
 * throw "Stub!" on the plain JVM unit test classpath this project uses (no
 * Robolectric configured) — same constraint as the rest of [AlbumViewModel], which is
 * why it isn't unit-tested directly either.
 */
class AlbumUiStateTest {

    @Test
    fun `isEmpty is true when there are no items and nothing is loading`() {
        assertTrue(AlbumUiState(items = emptyList(), isLoading = false).isEmpty)
    }

    @Test
    fun `isEmpty is false while loading, even with no items yet`() {
        assertFalse(AlbumUiState(items = emptyList(), isLoading = true).isEmpty)
    }
}
