package com.crosspaste.workbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipboardDropTargetTest {
    @Test
    fun `scrolled viewport targets visible blocks and the adjacent insertion boundary`() {
        val order = listOf("above", "visible-first", "visible-last", "below")
        val centers = mapOf("visible-first" to 400f, "visible-last" to 600f)
        assertEquals("visible-first", clipboardDropTarget(order, centers, 300f))
        assertEquals("visible-last", clipboardDropTarget(order, centers, 500f))
        assertEquals("below", clipboardDropTarget(order, centers, 700f))
    }

    @Test
    fun `end and empty viewport remain safe`() {
        assertNull(clipboardDropTarget(listOf("last"), mapOf("last" to 400f), 500f))
        assertNull(clipboardDropTarget(listOf("last"), emptyMap(), 500f))
    }
}
