package com.crosspaste.sync.journal

import com.crosspaste.Database
import com.crosspaste.db.TestDriverFactory
import com.crosspaste.db.createDatabase
import com.crosspaste.dto.sync.ControlChallenge
import com.crosspaste.secure.SecureKeyPairSerializer
import com.crosspaste.test.IntegrationTest
import com.crosspaste.utils.getCodecsUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class FileReceiverFixture(
    val root: Path,
    val capture: ManifestFixture,
) {
    lateinit var service: ClipboardFileTransferService
    lateinit var signed: SignedClipboardFileManifest
    lateinit var receivingHistory: ClipboardHistoryService
    lateinit var localDatabase: Database
    val receivedBlobs = DesktopClipboardBlobStore(root.resolve("received-blobs"))
    val receivedManifests = DesktopClipboardFileManifestStore(root.resolve("received-manifests"), capture.verifier)
    var requests = 0
    var mutate: (String, String) -> String = { _, payload -> payload }

    fun receiver(): DesktopClipboardFileReceiver {
        val client =
            ClipboardPeerClient(
                capture.peers.b,
                object : ClipboardPeerWire {
                    override suspend fun challenge(peer: String): ControlChallenge = capture.peers.a.issue("b")

                    override suspend fun post(
                        peer: String,
                        path: String,
                        proof: ClipboardPeerProof,
                    ): ClipboardPeerResponse {
                        requests++
                        val operation =
                            when (path) {
                                "file-manifest" -> ClipboardPeerOperation.FILE_MANIFEST
                                "file-blob" -> ClipboardPeerOperation.FILE_BLOB
                                else -> error("Unexpected route")
                            }
                        val verified = assertNotNull(capture.peers.a.verify("b", proof, operation))
                        val payload =
                            if (path == "file-manifest") {
                                Json.encodeToString(service.manifest(verified))
                            } else {
                                Json.encodeToString(service.blob(verified))
                            }
                        return capture.peers.a.reply(verified, mutate(path, payload))
                    }
                },
            )
        return DesktopClipboardFileReceiver(
            ClipboardFileTransfer(client, capture.verifier),
            capture.verifier,
            receivedManifests,
            receivedBlobs,
            receivingHistory,
            root.resolve("receiving"),
        )
    }

    suspend fun receive(
        budget: Long = 8,
        manifestBudget: Int = 32768,
    ): SignedClipboardFileManifest = receiver().receive("a", capture.event, capture.metadata, manifestBudget, budget)

    fun assertNoPartialFiles() {
        Files.walk(root).use { paths -> assertTrue(paths.noneMatch { it.toString().endsWith(".part") }) }
    }

    fun cleanup() {
        require(
            root.toAbsolutePath().normalize().startsWith(
                Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize(),
            ),
        )
        require(root.fileName.toString().startsWith("clipboard-file-receiver-test-"))
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
}

internal suspend fun fileReceiverFixture(): FileReceiverFixture {
    val capture = manifestFixture()
    val root = Files.createTempDirectory("clipboard-file-receiver-test-")
    val fixture = FileReceiverFixture(root, capture)
    try {
        val photos = Files.createDirectory(root.resolve("photos"))
        Files.createDirectory(photos.resolve("empty"))
        Files.writeString(photos.resolve("café.txt"), "hello")
        Files.writeString(root.resolve("thumb.png"), "png")
        val sourceBlobs = DesktopClipboardBlobStore(root.resolve("source-blobs"))
        val sourceManifests = DesktopClipboardFileManifestStore(root.resolve("source-manifests"), capture.verifier)
        fixture.signed =
            DesktopClipboardFileSnapshotter(
                "a",
                capture.peers.aStore,
                capture.verifier,
                sourceBlobs,
                sourceManifests,
            ) { _, item, _ -> if (item == 0) photos else root.resolve("thumb.png") }
                .snapshot(capture.event, capture.metadata, 8)
        val db = createDatabase(TestDriverFactory())
        val serializer = SecureKeyPairSerializer()
        fixture.localDatabase = createDatabase(TestDriverFactory())
        val localMembers = ClipboardMembershipStore(fixture.localDatabase, serializer)
        localMembers.createClipboard("shared", "b", capture.peers.bStore.secureKeyPair.signKeyPair.publicKey, false)
        localMembers.approveMember("shared", "a", capture.peers.aStore.secureKeyPair.signKeyPair.publicKey)
        SqlSignedClipboardStore(
            fixture.localDatabase,
            "shared",
        ).save(SignedClipboardSnapshot("shared", listOf(capture.event)))
        fixture.localDatabase.sharedClipboardDatabaseQueries.insertPayload(
            "shared",
            capture.manifest.metadataHash,
            capture.metadata.decodeToString(),
        )
        fixture.receivingHistory = ClipboardHistoryService(fixture.localDatabase, "b", capture.peers.bStore, serializer)
        val members = ClipboardMembershipStore(db, serializer)
        members.createClipboard("shared", "a", capture.peers.aStore.secureKeyPair.signKeyPair.publicKey, true)
        members.approveMember("shared", "b", capture.peers.bStore.secureKeyPair.signKeyPair.publicKey)
        SqlSignedClipboardStore(db, "shared").save(SignedClipboardSnapshot("shared", listOf(capture.event)))
        db.sharedClipboardDatabaseQueries.insertPayload(
            "shared",
            capture.manifest.metadataHash,
            capture.metadata.decodeToString(),
        )
        fixture.service =
            ClipboardFileTransferService(
                ClipboardHistoryService(db, "a", capture.peers.aStore, serializer),
                capture.verifier,
                sourceManifests::load,
            ) { blob, offset, limit -> sourceBlobs.readChunk(blob.sha256, offset, limit) }
        return fixture
    } catch (error: Throwable) {
        fixture.cleanup()
        throw error
    }
}

@IntegrationTest
class DesktopClipboardFileReceiverTest {
    @Test
    fun `disabled local clipboard denies both downloads and cached reuse without making a request`() =
        runTest {
            val f = fileReceiverFixture()
            try {
                f.localDatabase.sharedClipboardDatabaseQueries.putWorkspace("shared", false, false)
                assertFailsWith<IllegalArgumentException> { f.receive() }
                assertEquals(0, f.requests)
                f.localDatabase.sharedClipboardDatabaseQueries.putWorkspace("shared", true, false)
                f.receive()
                val requests = f.requests
                f.localDatabase.sharedClipboardDatabaseQueries.putWorkspace("shared", false, false)
                assertFailsWith<IllegalArgumentException> { f.receive() }
                assertEquals(requests, f.requests)
                f.assertNoPartialFiles()
            } finally {
                f.cleanup()
            }
        }

    @Test
    fun `receiver commits all verified representations and reconnect uses private bytes without requests`() =
        runTest {
            val f = fileReceiverFixture()
            try {
                assertEquals(f.signed, f.receive())
                f.signed.manifest.nodes
                    .mapNotNull { it.blob }
                    .forEach { assertTrue(f.receivedBlobs.has(it)) }
                assertNotNull(f.receivedManifests.load(f.capture.event, f.capture.metadata))
                val requests = f.requests
                f.mutate = { _, _ -> error("Reconnect should use cached files") }
                assertEquals(f.signed, f.receive())
                assertEquals(requests, f.requests)
                assertFailsWith<IllegalArgumentException> { f.receive(7) }
                assertFailsWith<IllegalArgumentException> { f.receive(manifestBudget = 1) }
                f.assertNoPartialFiles()
            } finally {
                f.cleanup()
            }
        }

    @Test
    fun `authenticated corrupt file bytes are rejected without committing a manifest or damaged blob`() =
        runTest {
            val f = fileReceiverFixture()
            try {
                f.mutate = { path, payload ->
                    if (path == "file-blob") {
                        val chunk = Json.decodeFromString<ClipboardBlobChunk>(payload)
                        Json.encodeToString(
                            chunk.copy(
                                bytes = getCodecsUtils().base64Encode(ByteArray(chunk.totalBytes.toInt()) { 120 }),
                            ),
                        )
                    } else {
                        payload
                    }
                }
                assertFailsWith<IllegalArgumentException> { f.receive() }
                assertNull(f.receivedManifests.load(f.capture.event, f.capture.metadata))
                f.signed.manifest.nodes
                    .mapNotNull { it.blob }
                    .forEach { assertFalse(f.receivedBlobs.has(it)) }
                f.assertNoPartialFiles()
            } finally {
                f.cleanup()
            }
        }

    @Test
    fun `cancelled blob transfer removes staging and retry completes without duplicate history`() =
        runTest {
            val f = fileReceiverFixture()
            try {
                f.mutate =
                    { path, payload ->
                        if (path ==
                            "file-blob"
                        ) {
                            throw CancellationException("Disconnected")
                        } else {
                            payload
                        }
                    }
                assertFailsWith<CancellationException> { f.receive() }
                assertNull(f.receivedManifests.load(f.capture.event, f.capture.metadata))
                f.assertNoPartialFiles()
                f.mutate = { _, payload -> payload }
                assertEquals(f.signed, f.receive())
                f.assertNoPartialFiles()
            } finally {
                f.cleanup()
            }
        }

    @Test
    fun `manifest budget and invalid range metadata reject before file publication`() =
        runTest {
            val f = fileReceiverFixture()
            try {
                assertFailsWith<IllegalArgumentException> { f.receive(manifestBudget = 1) }
                assertFalse(Files.exists(f.root.resolve("receiving")))
                f.mutate = { path, payload ->
                    if (path == "file-blob") {
                        val chunk = Json.decodeFromString<ClipboardBlobChunk>(payload)
                        Json.encodeToString(chunk.copy(offset = chunk.offset + 1))
                    } else {
                        payload
                    }
                }
                assertFailsWith<IllegalArgumentException> { f.receive() }
                assertNull(f.receivedManifests.load(f.capture.event, f.capture.metadata))
                f.assertNoPartialFiles()
            } finally {
                f.cleanup()
            }
        }

    @Test
    fun `whole-file SHA mismatch is rejected even when size and captured Murmur hash match`() =
        runTest {
            val f = fileReceiverFixture()
            try {
                val descriptor =
                    f.signed.manifest.nodes
                        .first { it.slot.name == "café.txt" }
                        .blob!!
                val source = f.root.resolve("photos").resolve("café.txt")
                val wrong = descriptor.copy(sha256 = "0".repeat(64))
                assertFailsWith<IllegalArgumentException> { f.receivedBlobs.importVerified(source, wrong, 8) }
                assertFalse(f.receivedBlobs.has(wrong))
                assertFalse(f.receivedBlobs.has(descriptor))
                f.assertNoPartialFiles()
            } finally {
                f.cleanup()
            }
        }
}
