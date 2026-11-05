package com.crosspaste.sync.journal

import com.crosspaste.paste.PasteCollection
import com.crosspaste.paste.PasteData
import com.crosspaste.paste.PasteState
import com.crosspaste.paste.item.PasteFiles
import com.crosspaste.paste.item.PasteItem
import com.crosspaste.paste.item.resolveBasePath
import com.crosspaste.test.IntegrationTest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun capturedLandingPaste(f: ManifestFixture): PasteData {
    val capture = Json.decodeFromString<CapturedClipboardPayload>(f.metadata.decodeToString())
    return PasteData(
        id = capture.localPasteId,
        appInstanceId = capture.originDevice,
        pasteAppearItem = PasteItem.fromStoredJson(capture.pasteAppearItem),
        pasteCollection = PasteCollection.fromStoredJson(capture.pasteCollection),
        pasteType = capture.pasteType.toInt(),
        source = capture.source,
        size = capture.size,
        hash = capture.originalHash,
        createTime = capture.createTime,
        pasteState = PasteState.LOADED,
        remote = true,
    )
}

@IntegrationTest
class DesktopClipboardFileLandingTest {
    @Test
    fun `native history waits for all bytes then resolves primary folder and alternate image to private files`() =
        runTest {
            val f = fileReceiverFixture()
            try {
                val landing =
                    DesktopClipboardFileLanding(
                        f.root.resolve("landings"),
                        f.receivedManifests,
                        f.capture.verifier,
                        f.receivedBlobs,
                    )
                val rendered = mutableListOf<Long>()
                val materializer =
                    ClipboardHistoryMaterializer(
                        f.localDatabase,
                        f.receivingHistory,
                        "b",
                        { null },
                        { id, _ -> rendered.add(id) },
                        landing::land,
                    )
                val waiting = materializer.materialize("shared")
                assertEquals(0, waiting.created)
                assertEquals(setOf(f.capture.manifest.metadataHash), waiting.awaitingFilePayloads)
                assertFalse(Files.exists(f.root.resolve("landings")))
                f.receive()
                val ready = materializer.materialize("shared")
                assertEquals(1, ready.created)
                assertTrue(ready.awaitingFilePayloads.isEmpty())
                val projection =
                    f.localDatabase.sharedClipboardDatabaseQueries
                        .getProjection(
                            "shared",
                            "a",
                            1,
                        ).executeAsOne()
                val paste =
                    f.localDatabase.pasteDatabaseQueries
                        .getPasteData(
                            projection.localPasteId,
                            listOf(PasteState.LOADED.toLong()),
                            PasteData::mapper,
                        ).executeAsOne()
                val files = paste.pasteAppearItem as PasteFiles
                val base = assertNotNull(files.resolveBasePath()).toNioPath()
                assertTrue(base.startsWith(f.root.resolve("landings")))
                val photos = base.resolve(files.relativePathList.single())
                assertEquals("hello", Files.readString(photos.resolve("café.txt")))
                assertTrue(Files.isDirectory(photos.resolve("empty")))
                val image = paste.pasteCollection.pasteItems.single() as PasteFiles
                val imagePath =
                    assertNotNull(
                        image.resolveBasePath(),
                    ).toNioPath().resolve(image.relativePathList.single())
                assertEquals("png", Files.readString(imagePath))
                assertEquals(1, materializer.materialize("shared").reused)
                assertEquals(listOf(projection.localPasteId), rendered)
                assertNotNull(landing.land(f.capture.event, f.capture.metadata, capturedLandingPaste(f.capture)))
                f.assertNoPartialFiles()
            } finally {
                f.cleanup()
            }
        }

    @Test
    fun `modified or extra landed files are rejected instead of silently exposed`() =
        runTest {
            val f = fileReceiverFixture()
            try {
                f.receive()
                val landing =
                    DesktopClipboardFileLanding(
                        f.root.resolve("landings"),
                        f.receivedManifests,
                        f.capture.verifier,
                        f.receivedBlobs,
                    )
                val original = capturedLandingPaste(f.capture)
                val local = assertNotNull(landing.land(f.capture.event, f.capture.metadata, original))
                val files = local.pasteAppearItem as PasteFiles
                val photos = assertNotNull(files.resolveBasePath()).toNioPath().resolve(files.relativePathList.single())
                Files.writeString(photos.resolve("café.txt"), "HELLO")
                assertFailsWith<IllegalArgumentException> {
                    landing.land(
                        f.capture.event,
                        f.capture.metadata,
                        original,
                    )
                }
                Files.writeString(photos.resolve("café.txt"), "hello")
                Files.writeString(photos.resolve("unexpected.txt"), "not in original capture")
                assertFailsWith<IllegalArgumentException> {
                    landing.land(
                        f.capture.event,
                        f.capture.metadata,
                        original,
                    )
                }
            } finally {
                f.cleanup()
            }
        }

    @Test
    fun `portable filenames remove device names path separators controls and trailing dots without breaking unicode`() {
        assertEquals("_CON.txt", clipboardLandingName("CON.txt"))
        assertEquals("_LPT¹", clipboardLandingName("LPT¹"))
        assertEquals("a_b_c_", clipboardLandingName("a:b\\c?"))
        assertEquals("item", clipboardLandingName(". "))
        assertEquals("café.txt", clipboardLandingName("cafe\u0301.txt"))
        val long = clipboardLandingName("💙".repeat(200))
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= 180)
        assertEquals(0, long.length % 2)
    }

    @Test
    fun `deletion during file preparation prevents native history publication`() =
        runTest {
            val f = fileReceiverFixture()
            try {
                f.receive()
                val landing =
                    DesktopClipboardFileLanding(
                        f.root.resolve("landings"),
                        f.receivedManifests,
                        f.capture.verifier,
                        f.receivedBlobs,
                    )
                val sql = SqlSignedClipboardStore(f.localDatabase, "shared")
                val members = ClipboardMembershipStore(f.localDatabase, com.crosspaste.secure.SecureKeyPairSerializer())
                val journal =
                    SignedClipboardJournal.open(
                        "b",
                        f.capture.peers.bStore.secureKeyPair.signKeyPair.privateKey,
                        { members.signingKey("shared", it) },
                        sql.load(),
                        sql,
                    )
                val materializer =
                    ClipboardHistoryMaterializer(
                        f.localDatabase,
                        f.receivingHistory,
                        "b",
                        { null },
                        { _, _ -> error("Deleted item must not render") },
                    ) { event, bytes, paste ->
                        val local = landing.land(event, bytes, paste)
                        journal.append(event.change.entry, null, setOf(event.change.id), deleted = true)
                        local
                    }
                assertEquals(0, materializer.materialize("shared").created)
                assertNull(
                    f.localDatabase.sharedClipboardDatabaseQueries
                        .getProjection("shared", "a", 1)
                        .executeAsOneOrNull(),
                )
            } finally {
                f.cleanup()
            }
        }
}
