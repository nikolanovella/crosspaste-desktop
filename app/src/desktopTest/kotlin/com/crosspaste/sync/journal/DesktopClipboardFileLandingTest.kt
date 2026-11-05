package com.crosspaste.sync.journal

import com.crosspaste.paste.PasteCollection
import com.crosspaste.paste.PasteData
import com.crosspaste.paste.PasteState
import com.crosspaste.paste.item.PasteFiles
import com.crosspaste.paste.item.PasteItem
import com.crosspaste.paste.item.resolveBasePath
import com.crosspaste.presist.DirFileInfoTree
import com.crosspaste.presist.FileInfoTree
import com.crosspaste.presist.validateFileTransferMetadata
import com.crosspaste.test.IntegrationTest
import com.crosspaste.utils.getFileUtils
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
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
    fun `completed legacy transfer reuses verified history only while private bytes match`() =
        runTest {
            for (corrupt in listOf(false, true)) {
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
                    val db = f.localDatabase
                    val materializer =
                        ClipboardHistoryMaterializer(
                            db,
                            f.receivingHistory,
                            "b",
                            { null },
                            { _, _ -> },
                            landing::land,
                        )
                    assertEquals(1, materializer.materialize("shared").created)
                    val queries = db.pasteDatabaseQueries
                    val sharedId =
                        db.sharedClipboardDatabaseQueries
                            .getProjection("shared", "a", 1)
                            .executeAsOne()
                            .localPasteId
                    val states = listOf(PasteState.LOADED.toLong())
                    val shared = queries.getPasteData(sharedId, states, PasteData::mapper).executeAsOne()
                    if (corrupt) {
                        val files = shared.pasteAppearItem as PasteFiles
                        val root = assertNotNull(files.resolveBasePath()).toNioPath()
                        Files.writeString(root.resolve(files.relativePathList.single()).resolve("café.txt"), "HELLO")
                    }
                    val original = capturedLandingPaste(f.capture)
                    val identities = ClipboardNativeIdentityStore(db)
                    val marker = assertNotNull(identities.verified(original, f.capture.manifest.metadataHash)).identity
                    queries.createPasteDataEntity(
                        original.appInstanceId,
                        false,
                        original.pasteAppearItem?.toStoredJson(),
                        original.pasteCollection.toStoredJson(),
                        original.pasteType.toLong(),
                        original.source,
                        original.size,
                        original.hash,
                        original.createTime,
                        null,
                        PasteState.LOADED.toLong(),
                        true,
                    )
                    val legacyId = queries.getLastId().executeAsOne()
                    val legacy = original.copy(id = legacyId, captureIdentity = marker)
                    identities.rememberFile(legacy, legacyId)
                    val bridge =
                        DesktopClipboardFileConvergence(db, f.receivingHistory, identities, landing, "b") { null }
                    assertEquals(if (corrupt) legacyId else sharedId, assertNotNull(bridge.complete(legacy)).id)
                    assertEquals(
                        corrupt,
                        queries.getPasteData(legacyId, states, PasteData::mapper).executeAsOneOrNull() != null,
                    )
                } finally {
                    f.cleanup()
                }
            }
        }

    @Test
    fun `zero byte files and directory only roots survive signed transfer and native publication`() =
        runTest {
            for (directoryOnlyCapture in listOf(false, true)) {
                val f = fileReceiverFixture(emptyRoots = true, directoryOnly = directoryOnlyCapture)
                try {
                    val original = capturedLandingPaste(f.capture)
                    assertTrue(original.hasValidContent())
                    assertFailsWith<IllegalArgumentException> { validateFileTransferMetadata(original) }
                    validateFileTransferMetadata(original, allowZeroByteContent = true)
                    f.receive(budget = 0)
                    val landing =
                        DesktopClipboardFileLanding(
                            f.root.resolve("landings"),
                            f.receivedManifests,
                            f.capture.verifier,
                            f.receivedBlobs,
                        )
                    val rendered = mutableListOf<Long>()
                    val materializer =
                        ClipboardHistoryMaterializer(f.localDatabase, f.receivingHistory, "b", {
                            null
                        }, { id, _ -> rendered.add(id) }, landing::land)
                    assertEquals(1, materializer.materialize("shared").created)
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
                    assertTrue(paste.hasValidContent())
                    validateFileTransferMetadata(paste, allowZeroByteContent = true)
                    val files = paste.pasteAppearItem as PasteFiles
                    val base = assertNotNull(files.resolveBasePath()).toNioPath()
                    if (!directoryOnlyCapture) {
                        assertEquals(0L, Files.size(base.resolve("blank.txt")))
                    } else {
                        assertTrue(
                            f.signed.manifest.nodes
                                .all { it.blob == null },
                        )
                    }
                    assertTrue(Files.isDirectory(base.resolve("photos/empty")))
                    val folder = files.fileInfoTreeMap.getValue("photos") as DirFileInfoTree
                    val directoryOnly =
                        (paste.pasteAppearItem as com.crosspaste.paste.item.FilesPasteItem).copy(
                            count = 0,
                            fileInfoTreeMap = mapOf("photos" to folder),
                            relativePathList = listOf("photos"),
                        )
                    assertTrue(directoryOnly.isValid())
                    files.fileInfoTreeMap.forEach { (name, tree) ->
                        assertEquals(
                            Json.encodeToString<FileInfoTree>(
                                getFileUtils().getFileInfoTree(base.resolve(name).toString().toPath()),
                            ),
                            Json.encodeToString<FileInfoTree>(tree),
                        )
                    }
                    assertEquals(1, materializer.materialize("shared").reused)
                    assertEquals(listOf(projection.localPasteId), rendered)
                    assertNotNull(landing.land(f.capture.event, f.capture.metadata, original))
                    f.assertNoPartialFiles()
                } finally {
                    f.cleanup()
                }
            }
        }

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
