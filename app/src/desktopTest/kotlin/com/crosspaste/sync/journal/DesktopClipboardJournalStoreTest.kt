package com.crosspaste.sync.journal

import com.crosspaste.test.IntegrationTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

@IntegrationTest
class DesktopClipboardJournalStoreTest {
    @Test
    fun `restart recovers all revisions and continues local sequence`() {
        val directory = Files.createTempDirectory("crosspaste-journal-test")
        try {
            val store = DesktopClipboardJournalStore(directory.resolve("journal.json"), "shared")
            val journal = ClipboardJournal("a", store.load(), store)
            val first = journal.append("entry", "original")
            journal.append("entry", "edited", setOf(first.id))
            val reopenedStore = DesktopClipboardJournalStore(directory.resolve("journal.json"), "shared")
            val reopened = ClipboardJournal("a", reopenedStore.load(), reopenedStore)
            assertEquals(journal.snapshot(), reopened.snapshot())
            assertEquals(3L, reopened.append("another", "hash").id.sequence)
        } finally {
            Files.deleteIfExists(directory.resolve("journal.json"))
            Files.delete(directory)
        }
    }

    @Test
    fun `corruption and wrong clipboard never silently reset history`() {
        val directory = Files.createTempDirectory("crosspaste-journal-invalid")
        val path = directory.resolve("journal.json")
        try {
            Files.writeString(path, "broken")
            assertFails { DesktopClipboardJournalStore(path, "shared").load() }
            val store = DesktopClipboardJournalStore(path, "other")
            store.save(ClipboardJournalSnapshot("other"))
            assertFails { DesktopClipboardJournalStore(path, "shared").load() }
            assertEquals("other", store.load().clipboard)
        } finally {
            Files.deleteIfExists(path)
            Files.delete(directory)
        }
    }
}
