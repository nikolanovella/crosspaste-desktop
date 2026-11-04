package com.crosspaste.sync.journal

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClipboardJournalTest {
    private fun replica(device: String) = ClipboardJournal(device, ClipboardJournalSnapshot("shared")) { }

    @Test
    fun `offline edits reconcile through a third device without dropping concurrent versions`() {
        val a = replica("a")
        val b = replica("b")
        val relay = replica("relay")
        val original = a.append("entry", "original")
        b.ingest(a.missing(b.cursor()))
        val editA = a.append("entry", "edit-a", setOf(original.id))
        val editB = b.append("entry", "edit-b", setOf(original.id))
        relay.ingest(b.missing(relay.cursor()).reversed())
        relay.ingest(a.missing(relay.cursor()))
        a.ingest(relay.missing(a.cursor()))
        b.ingest(relay.missing(b.cursor()))
        assertEquals(setOf(editA.id, editB.id), a.heads("entry").map { it.id }.toSet())
        assertEquals(a.snapshot(), b.snapshot())
        assertEquals(a.snapshot(), relay.snapshot())
        val merged = a.append("entry", "merged", setOf(editA.id, editB.id))
        b.ingest(a.missing(b.cursor()))
        assertEquals(listOf(merged), b.heads("entry"))
        assertEquals(4, b.snapshot().changes.size)
    }

    @Test
    fun `out of order delivery does not acknowledge gaps or expose missing ancestor revisions`() {
        val a = replica("a")
        val b = replica("b")
        val first = a.append("entry", "one")
        val second = a.append("entry", "two", setOf(first.id))
        b.ingest(listOf(second))
        assertEquals(0L, b.cursor()["a"])
        assertTrue(b.heads("entry").isEmpty())
        b.ingest(a.missing(b.cursor(), 1))
        assertEquals(2L, b.cursor()["a"])
        assertEquals(listOf(second), b.heads("entry"))
        b.ingest(a.missing(emptyMap()))
        assertEquals(2, b.snapshot().changes.size)
    }

    @Test
    fun `catchup drains more than the old recent window and survives restart`() {
        val a = replica("a")
        repeat(137) { a.append("entry-$it", "hash-$it") }
        val b = replica("b")
        while (b.snapshot().changes.size < 137) b.ingest(a.missing(b.cursor(), 17))
        val persisted = Json.encodeToString(b.snapshot())
        val restarted = ClipboardJournal("b", Json.decodeFromString(persisted)) { }
        assertEquals(137L, restarted.cursor()["a"])
        assertEquals(a.snapshot(), restarted.snapshot())
    }

    @Test
    fun `storage failure consumes neither sequence nor accepted remote events`() {
        var fail = true
        val journal = ClipboardJournal("a", ClipboardJournalSnapshot("shared")) { if (fail) error("disk full") }
        assertFailsWith<IllegalStateException> { journal.append("entry", "one") }
        assertTrue(journal.snapshot().changes.isEmpty())
        fail = false
        assertEquals(1L, journal.append("entry", "one").id.sequence)
    }

    @Test
    fun `conflicting identities foreign clipboard and invalid ancestry reject whole batch`() {
        val a = replica("a")
        val b = replica("b")
        val first = a.append("entry", "one")
        b.ingest(listOf(first))
        assertFailsWith<IllegalArgumentException> { b.ingest(listOf(first.copy(contentHash = "forged"))) }
        assertFailsWith<IllegalArgumentException> { b.ingest(listOf(first.copy(clipboard = "other"))) }
        val second = a.append("entry", "two", setOf(first.id))
        assertFailsWith<IllegalArgumentException> { b.ingest(listOf(second.copy(entry = "unrelated"))) }
        assertEquals(listOf(first), b.snapshot().changes)
    }

    @Test
    fun `ancestor replay cannot resurrect a deleted head and concurrent edit remains visible`() {
        val a = replica("a")
        val b = replica("b")
        val first = a.append("entry", "one")
        b.ingest(listOf(first))
        val deletion = a.append("entry", null, setOf(first.id), deleted = true)
        val edit = b.append("entry", "offline-edit", setOf(first.id))
        a.ingest(listOf(first, edit))
        assertEquals(setOf(deletion, edit), a.heads("entry").toSet())
    }
}
