package com.crosspaste.sync.journal

import com.crosspaste.Database
import com.crosspaste.db.TestDriverFactory
import com.crosspaste.db.createDatabase
import com.crosspaste.dto.sync.ControlChallenge
import com.crosspaste.paste.PasteCollection
import com.crosspaste.paste.item.CreatePasteItemHelper.createTextPasteItem
import com.crosspaste.secure.SecureKeyPairSerializer
import com.crosspaste.secure.SecureStore
import com.crosspaste.utils.getCodecsUtils
import com.crosspaste.utils.getJsonUtils
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class HistoryFixture(
    val peers: ClipboardPeerFixture,
    val aDb: Database,
    val bDb: Database,
    val a: ClipboardHistoryService,
    val b: ClipboardHistoryService,
) {
    fun client(
        fromA: Boolean,
        interruptPayloadAt: Int? = null,
    ): ClipboardPeerClient {
        val local = if (fromA) peers.a else peers.b
        val remote = if (fromA) peers.b else peers.a
        val sender = if (fromA) "a" else "b"
        val target = if (fromA) b else a
        return ClipboardPeerClient(
            local,
            object : ClipboardPeerWire {
                override suspend fun challenge(peer: String): ControlChallenge = remote.issue(sender)

                override suspend fun post(
                    peer: String,
                    path: String,
                    proof: ClipboardPeerProof,
                ): ClipboardPeerResponse {
                    val operation =
                        when (path) {
                            "history" -> ClipboardPeerOperation.HISTORY
                            "payload" -> ClipboardPeerOperation.PAYLOAD
                            else -> error("Unexpected operation")
                        }
                    val verified = assertNotNull(remote.verify(sender, proof, operation))
                    if (operation == ClipboardPeerOperation.PAYLOAD &&
                        interruptPayloadAt != null &&
                        Json.decodeFromString<ClipboardPayloadRequest>(proof.request.payload).offset >=
                        interruptPayloadAt
                    ) {
                        throw kotlinx.coroutines.CancellationException("Fixture transfer interrupted")
                    }
                    val body =
                        if (operation ==
                            ClipboardPeerOperation.HISTORY
                        ) {
                            Json.encodeToString(target.read(verified))
                        } else {
                            Json.encodeToString(target.payload(verified))
                        }
                    return remote.reply(verified, body)
                }
            },
        )
    }
}

private suspend fun historyFixture(approveAOnB: Boolean = true): HistoryFixture {
    val peers = clipboardPeerFixture()
    val aDb = createDatabase(TestDriverFactory())
    val bDb = createDatabase(TestDriverFactory())
    val serializer = SecureKeyPairSerializer()
    val aMembers = ClipboardMembershipStore(aDb, serializer)
    val bMembers = ClipboardMembershipStore(bDb, serializer)
    aMembers.createClipboard("shared", "a", peers.aStore.secureKeyPair.signKeyPair.publicKey, true)
    bMembers.createClipboard("shared", "b", peers.bStore.secureKeyPair.signKeyPair.publicKey, false)
    aMembers.approveMember("shared", "b", peers.bStore.secureKeyPair.signKeyPair.publicKey)
    if (approveAOnB) bMembers.approveMember("shared", "a", peers.aStore.secureKeyPair.signKeyPair.publicKey)
    return HistoryFixture(
        peers,
        aDb,
        bDb,
        ClipboardHistoryService(aDb, "a", peers.aStore, serializer),
        ClipboardHistoryService(bDb, "b", peers.bStore, serializer),
    )
}

private suspend fun journal(
    db: Database,
    device: String,
    store: SecureStore,
): SignedClipboardJournal {
    val members = ClipboardMembershipStore(db, SecureKeyPairSerializer())
    val sql = SqlSignedClipboardStore(db, "shared")
    return SignedClipboardJournal.open(
        device,
        store.secureKeyPair.signKeyPair.privateKey,
        { members.signingKey("shared", it) },
        sql.load(),
        sql,
    )
}

class ClipboardHistoryServiceTest {
    private suspend fun preparePayload(
        f: HistoryFixture,
        text: String,
    ): Pair<String, ByteArray> {
        getJsonUtils()
        val item = createTextPasteItem(text = text)
        val payload =
            CapturedClipboardPayload(
                "b",
                42,
                item.toStoredJson(),
                PasteCollection(emptyList()).toStoredJson(),
                0,
                null,
                item.size,
                item.hash,
                1,
            )
        val bytes = Json.encodeToString(payload).encodeToByteArray()
        val hash =
            "sha256:" +
                getCodecsUtils().base64Encode(
                    CryptographyProvider.Default
                        .get(SHA256)
                        .hasher()
                        .hash(bytes),
                )
        f.bDb.sharedClipboardDatabaseQueries.insertPayload("shared", hash, bytes.decodeToString())
        journal(f.bDb, "b", f.peers.bStore).append(Json.encodeToString(listOf("capture/v1", "b", "42")), hash)
        ClipboardHistoryPuller(f.client(true), f.a).pull("shared", "b")
        return hash to bytes
    }

    @Test
    fun `multi chunk unicode metadata verifies before SQL commit and staging is cleaned`() =
        runTest {
            val f = historyFixture()
            val (hash, bytes) = preparePayload(f, "🙂漢字".repeat(80000))
            assertTrue(bytes.size > 262144)
            val directory = Files.createTempDirectory("clipboard-receive-test-")
            try {
                val receiver = DesktopClipboardPayloadReceiver(ClipboardPayloadTransfer(f.client(true)), f.a, directory)
                assertEquals(bytes.size, receiver.receive("b", "shared", hash, bytes.size))
                assertEquals(
                    bytes.decodeToString(),
                    f.aDb.sharedClipboardDatabaseQueries
                        .getPayload("shared", hash)
                        .executeAsOne(),
                )
                assertEquals(bytes.size, receiver.receive("b", "shared", hash, bytes.size))
                Files.list(directory).use { assertEquals(0L, it.count()) }
            } finally {
                Files.delete(directory)
            }
        }

    @Test
    fun `metadata budget failure never commits partial payload and cleans staging`() =
        runTest {
            val f = historyFixture()
            val (hash, bytes) = preparePayload(f, "test")
            val directory = Files.createTempDirectory("clipboard-receive-budget-")
            try {
                val receiver = DesktopClipboardPayloadReceiver(ClipboardPayloadTransfer(f.client(true)), f.a, directory)
                assertFailsWith<IllegalArgumentException> { receiver.receive("b", "shared", hash, bytes.size - 1) }
                assertEquals(
                    null,
                    f.aDb.sharedClipboardDatabaseQueries
                        .getPayload("shared", hash)
                        .executeAsOneOrNull(),
                )
                Files.list(directory).use { assertEquals(0L, it.count()) }
            } finally {
                Files.delete(directory)
            }
        }

    @Test
    fun `corrupt metadata and unreferenced payload cannot enter SQL`() =
        runTest {
            val f = historyFixture()
            val (hash, bytes) = preparePayload(f, "original")
            assertFailsWith<IllegalArgumentException> { f.a.storePayload("shared", hash, bytes + byteArrayOf(32)) }
            val unreferenced = "sha256:" + getCodecsUtils().base64Encode(ByteArray(32))
            assertFailsWith<IllegalArgumentException> { f.a.storePayload("shared", unreferenced, bytes) }
            assertFailsWith<IllegalArgumentException> {
                f.client(true).payload("b", "shared", ClipboardPayloadRequest(unreferenced))
            }
            assertEquals(
                null,
                f.aDb.sharedClipboardDatabaseQueries
                    .getPayload("shared", hash)
                    .executeAsOneOrNull(),
            )
        }

    @Test
    fun `interrupted download discards partial file without publishing metadata`() =
        runTest {
            val f = historyFixture()
            val (hash, bytes) = preparePayload(f, "content".repeat(100000))
            val directory = Files.createTempDirectory("clipboard-receive-interrupted-")
            try {
                val transfer = ClipboardPayloadTransfer(f.client(true, interruptPayloadAt = 262144))
                val receiver = DesktopClipboardPayloadReceiver(transfer, f.a, directory)
                assertFailsWith<kotlinx.coroutines.CancellationException> {
                    receiver.receive(
                        "b",
                        "shared",
                        hash,
                        bytes.size,
                    )
                }
                assertEquals(
                    null,
                    f.aDb.sharedClipboardDatabaseQueries
                        .getPayload("shared", hash)
                        .executeAsOneOrNull(),
                )
                Files.list(directory).use { assertEquals(0L, it.count()) }
            } finally {
                Files.delete(directory)
            }
        }

    @Test
    fun `payload identity must match a signed original capture`() =
        runTest {
            val f = historyFixture()
            val payload = CapturedClipboardPayload("a", 42, "{}", "{}", 0, null, 0, "test", 1)
            val bytes = Json.encodeToString(payload).encodeToByteArray()
            val hash =
                "sha256:" +
                    getCodecsUtils().base64Encode(
                        CryptographyProvider.Default
                            .get(SHA256)
                            .hasher()
                            .hash(bytes),
                    )
            journal(f.bDb, "b", f.peers.bStore).append(Json.encodeToString(listOf("capture/v1", "b", "42")), hash)
            ClipboardHistoryPuller(f.client(true), f.a).pull("shared", "b")
            assertFailsWith<IllegalArgumentException> { f.a.storePayload("shared", hash, bytes) }
            assertEquals(
                null,
                f.aDb.sharedClipboardDatabaseQueries
                    .getPayload("shared", hash)
                    .executeAsOneOrNull(),
            )
        }

    @Test
    fun `offline histories catch up in bounded batches survive restart and do not echo`() =
        runTest {
            val f = historyFixture()
            val aJournal = journal(f.aDb, "a", f.peers.aStore)
            repeat(137) { aJournal.append("a-$it", "content-$it") }
            journal(f.bDb, "b", f.peers.bStore).append("b-original", "b-content")
            val aPull = ClipboardHistoryPuller(f.client(true), f.a)
            val bPull = ClipboardHistoryPuller(f.client(false), f.b)
            assertEquals(1, aPull.pull("shared", "b"))
            assertEquals(50, bPull.pull("shared", "a"))
            assertEquals(50, bPull.pull("shared", "a"))
            assertEquals(37, bPull.pull("shared", "a"))
            assertEquals(0, bPull.pull("shared", "a"))
            assertEquals(0, aPull.pull("shared", "b"))
            assertEquals(
                SqlSignedClipboardStore(f.aDb, "shared").load(),
                SqlSignedClipboardStore(f.bDb, "shared").load(),
            )
            val restarted = ClipboardHistoryService(f.bDb, "b", f.peers.bStore, SecureKeyPairSerializer())
            assertEquals(mapOf("a" to 137L, "b" to 1L), restarted.cursor("shared"))
            assertEquals(
                false,
                f.bDb.sharedClipboardDatabaseQueries
                    .getWorkspace("shared")
                    .executeAsOne()
                    .contributeLocal,
            )
        }

    @Test
    fun `paired transport without explicit clipboard membership cannot read history`() =
        runTest {
            val f = historyFixture(approveAOnB = false)
            journal(f.bDb, "b", f.peers.bStore).append("private", "private-content")
            assertFailsWith<IllegalArgumentException> {
                f
                    .client(
                        true,
                    ).history("b", "shared", ClipboardHistoryRequest(emptyMap()))
            }
            assertTrue(SqlSignedClipboardStore(f.aDb, "shared").load().changes.isEmpty())
        }

    @Test
    fun `one invalid original signature rolls back the complete received batch`() =
        runTest {
            val f = historyFixture()
            val remote = journal(f.bDb, "b", f.peers.bStore)
            val first = remote.append("one", "first")
            val second = remote.append("two", "second")
            val proof =
                f.peers.a.prove(
                    "b",
                    "shared",
                    ClipboardPeerOperation.HISTORY,
                    f.peers.b.issue("a"),
                    Json.encodeToString(ClipboardHistoryRequest(emptyMap())),
                )
            val verified = assertNotNull(f.peers.b.verify("a", proof, ClipboardPeerOperation.HISTORY))
            val body =
                ClipboardHistoryBatch(
                    "shared",
                    listOf(first, second.copy(change = second.change.copy(contentHash = "tampered"))),
                )
            val response = f.peers.b.reply(verified, Json.encodeToString(body))
            val reply = assertNotNull(f.peers.a.verifiedReply("b", proof.request, response))
            assertFailsWith<IllegalArgumentException> { f.a.ingest(reply) }
            assertTrue(SqlSignedClipboardStore(f.aDb, "shared").load().changes.isEmpty())
        }

    @Test
    fun `out of order records never acknowledge a hole and later pull fills it`() =
        runTest {
            val f = historyFixture()
            val remote = journal(f.bDb, "b", f.peers.bStore)
            remote.append("one", "first")
            remote.append("two", "second")
            val third = remote.append("three", "third")
            val proof =
                f.peers.a.prove(
                    "b",
                    "shared",
                    ClipboardPeerOperation.HISTORY,
                    f.peers.b.issue("a"),
                    Json.encodeToString(ClipboardHistoryRequest(emptyMap())),
                )
            val verified = assertNotNull(f.peers.b.verify("a", proof, ClipboardPeerOperation.HISTORY))
            val response =
                f.peers.b.reply(
                    verified,
                    Json.encodeToString(ClipboardHistoryBatch("shared", listOf(third))),
                )
            assertEquals(1, f.a.ingest(assertNotNull(f.peers.a.verifiedReply("b", proof.request, response))))
            assertEquals(0L, f.a.cursor("shared")["b"])
            assertEquals(2, ClipboardHistoryPuller(f.client(true), f.a).pull("shared", "b"))
            assertEquals(3L, f.a.cursor("shared")["b"])
        }

    @Test
    fun `disabled clipboard and malformed cursors cannot serve history`() =
        runTest {
            val f = historyFixture()
            assertFailsWith<IllegalArgumentException> {
                f.client(true).history("b", "shared", ClipboardHistoryRequest(mapOf("a" to -1)))
            }
            f.bDb.sharedClipboardDatabaseQueries.putWorkspace("shared", false, false)
            assertFailsWith<IllegalArgumentException> {
                f
                    .client(
                        true,
                    ).history("b", "shared", ClipboardHistoryRequest(emptyMap()))
            }
            assertFailsWith<IllegalArgumentException> { f.b.cursor("shared") }
        }
}
