package com.crosspaste.workbench

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ClipboardDocumentTest {
    @Test
    fun `editing and merging preserve original content through serialization`() {
        val initial = ClipboardDocument().append("original one", "clip 1").append("original two", "clip 2")
        val edited = initial.edit(initial.blocks.first().id, "cleaned one")
        val restored = Json.decodeFromString<ClipboardDocument>(Json.encodeToString(edited))
        assertEquals(listOf("original one", "original two"), restored.originals.map { it.text })
        assertEquals("cleaned one\n\noriginal two", restored.render())
        assertNotEquals(restored.originals.first().text, restored.blocks.first().text)
    }

    @Test
    fun `dragging a grouped member moves its entire group in order`() {
        val document =
            ClipboardDocument()
                .append("a", "1")
                .append("b", "2")
                .append("c", "3")
                .append("d", "4")
        val grouped =
            document.grouped(
                document.blocks
                    .take(2)
                    .map { it.id }
                    .toSet(),
            )
        val moved = grouped.move(setOf(grouped.blocks[1].id), null)
        assertEquals(listOf("c", "d", "a", "b"), moved.blocks.map { it.text })
        assertEquals(document.originals, moved.originals)
        assertEquals(moved, moved.move(setOf(moved.blocks.last().id), moved.blocks[moved.blocks.lastIndex - 1].id))
    }

    @Test
    fun `dropping above a block is deterministic and ungroup permits independent movement`() {
        val initial = ClipboardDocument().append("a", "1").append("b", "2").append("c", "3")
        val grouped =
            initial.grouped(
                initial.blocks
                    .take(2)
                    .map { it.id }
                    .toSet(),
            )
        val independent =
            grouped.ungroup(
                grouped.blocks
                    .take(2)
                    .map { it.id }
                    .toSet(),
            )
        val result = independent.move(setOf(independent.blocks[2].id), independent.blocks[1].id)
        assertEquals(listOf("a", "c", "b"), result.blocks.map { it.text })
    }
}
