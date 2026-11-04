package com.crosspaste.sync.journal

import com.crosspaste.db.TestDriverFactory
import com.crosspaste.db.createDatabase
import com.crosspaste.paste.PasteCollection
import com.crosspaste.paste.item.CreatePasteItemHelper.createTextPasteItem
import com.crosspaste.utils.CryptographyUtils
import com.crosspaste.utils.getJsonUtils
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClipboardCaptureConsumerTest {
    @Suppress("unused")
    private val jsonUtils = getJsonUtils()

    private class Fixture {
        val driverFactory = TestDriverFactory()
        val database = createDatabase(driverFactory)
        val queries = database.sharedClipboardDatabaseQueries

        init {
            queries.putWorkspace("shared", true, true)
        }

        fun capture(
            text: String,
            device: String = "a",
        ): Long {
            val item = createTextPasteItem(text = text)
            database.pasteDatabaseQueries.createPasteDataEntity(
                device,
                false,
                item.toStoredJson(),
                PasteCollection(emptyList()).toStoredJson(),
                0L,
                null,
                item.size,
                item.hash,
                1L,
                null,
                1L,
                false,
            )
            val id = database.pasteDatabaseQueries.getLastId().executeAsOne()
            queries.enqueueLoadedLocalCapture(id)
            return id
        }
    }

    @Test
    fun `consumption survives restart and queue replay does not duplicate signed entries`() =
        runTest {
            val f = Fixture()
            val key = CryptographyUtils.generateSecureKeyPair().signKeyPair
            val id = f.capture("original")
            val consumer = ClipboardCaptureConsumer(f.database, "shared", "a", key.privateKey, { key.publicKey })
            assertEquals(1, consumer.drain())
            assertTrue(
                f.queries
                    .getPendingCaptures("shared", 50)
                    .executeAsList()
                    .isEmpty(),
            )
            val store = SqlSignedClipboardStore(f.database, "shared")
            val event = store.load().changes.single()
            val payload =
                Json.decodeFromString<CapturedClipboardPayload>(
                    f.queries.getPayload("shared", event.change.contentHash!!).executeAsOne(),
                )
            assertTrue(payload.pasteAppearItem.contains("original"))
            assertEquals(id, payload.localPasteId)
            f.queries.enqueueLoadedLocalCapture(id)
            val restarted = ClipboardCaptureConsumer(f.database, "shared", "a", key.privateKey, { key.publicKey })
            assertEquals(1, restarted.drain())
            assertEquals(listOf(event), store.load().changes)
            f.capture("next")
            assertEquals(1, restarted.drain())
            val recovered = SignedClipboardJournal.open("a", key.privateKey, { key.publicKey }, store.load(), store)
            assertEquals(2L, recovered.cursor()["a"])
        }

    @Test
    fun `failed acknowledgement rolls back signed event payload and queue removal together`() =
        runTest {
            val f = Fixture()
            val key = CryptographyUtils.generateSecureKeyPair().signKeyPair
            f.capture("pending")
            f.driverFactory.sqlDriver!!.execute(
                null,
                "CREATE TRIGGER RejectAck BEFORE DELETE ON SharedClipboardCaptureEntity " +
                    "BEGIN SELECT RAISE(ABORT, 'test acknowledgement failure'); END",
                0,
            )
            val consumer = ClipboardCaptureConsumer(f.database, "shared", "a", key.privateKey, { key.publicKey })
            assertFailsWith<Exception> { consumer.drain() }
            assertEquals(0L, f.queries.getPayloadCount("shared").executeAsOne())
            assertTrue(
                f.queries
                    .getEvents("shared")
                    .executeAsList()
                    .isEmpty(),
            )
            assertEquals(
                1,
                f.queries
                    .getPendingCaptures("shared", 50)
                    .executeAsList()
                    .size,
            )
            f.driverFactory.sqlDriver!!.execute(null, "DROP TRIGGER RejectAck", 0)
            assertEquals(1, consumer.drain())
            assertEquals(
                1L,
                f.queries
                    .getEvents("shared")
                    .executeAsOne()
                    .originSequence,
            )
        }

    @Test
    fun `disabled workspace and foreign author cannot consume pending captures`() =
        runTest {
            val f = Fixture()
            val key = CryptographyUtils.generateSecureKeyPair().signKeyPair
            f.capture("pending", "foreign")
            val consumer = ClipboardCaptureConsumer(f.database, "shared", "a", key.privateKey, { key.publicKey })
            f.queries.putWorkspace("shared", false, true)
            assertEquals(0, consumer.drain())
            f.queries.putWorkspace("shared", true, true)
            assertFailsWith<IllegalArgumentException> { consumer.drain() }
            assertEquals(
                1,
                f.queries
                    .getPendingCaptures("shared", 50)
                    .executeAsList()
                    .size,
            )
            assertTrue(
                f.queries
                    .getEvents("shared")
                    .executeAsList()
                    .isEmpty(),
            )
        }

    @Test
    fun `untrusted signing key leaves capture available for recovery`() =
        runTest {
            val f = Fixture()
            val key = CryptographyUtils.generateSecureKeyPair().signKeyPair
            f.capture("pending")
            val consumer = ClipboardCaptureConsumer(f.database, "shared", "a", key.privateKey, { null })
            assertFailsWith<IllegalArgumentException> { consumer.drain() }
            assertEquals(
                1,
                f.queries
                    .getPendingCaptures("shared", 50)
                    .executeAsList()
                    .size,
            )
            assertTrue(
                f.queries
                    .getEvents("shared")
                    .executeAsList()
                    .isEmpty(),
            )
        }

    @Test
    fun `stale writer cannot overwrite another committed signed event`() =
        runTest {
            val f = Fixture()
            val key = CryptographyUtils.generateSecureKeyPair().signKeyPair
            val store = SqlSignedClipboardStore(f.database, "shared")
            val one = SignedClipboardJournal.open("a", key.privateKey, { key.publicKey }, store.load(), store)
            val stale = SignedClipboardJournal.open("a", key.privateKey, { key.publicKey }, store.load(), store)
            val saved = one.append("one", "hash-one")
            assertFailsWith<IllegalArgumentException> { stale.append("two", "hash-two") }
            assertEquals(listOf(saved), store.load().changes)
        }
}
