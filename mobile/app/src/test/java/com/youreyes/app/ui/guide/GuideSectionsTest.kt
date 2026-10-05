package com.youreyes.app.ui.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideSectionsTest {

    @Test
    fun `every section has a non-blank title and body`() {
        assertTrue(guideSections.isNotEmpty())
        guideSections.forEach { section ->
            assertTrue("title must not be blank: $section", section.title.isNotBlank())
            assertTrue("body must not be blank: $section", section.body.isNotBlank())
        }
    }

    @Test
    fun `section titles are unique`() {
        val titles = guideSections.map { it.title }
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun `no leftover placeholder text`() {
        guideSections.forEach { section ->
            assertFalse(section.body.contains("TODO", ignoreCase = true))
            assertFalse(section.body.contains("lorem ipsum", ignoreCase = true))
        }
    }
}
