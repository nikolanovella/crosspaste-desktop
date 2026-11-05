package com.crosspaste.sync.journal

import com.crosspaste.Database
import com.crosspaste.db.TestDriverFactory
import com.crosspaste.db.createDatabase
import com.crosspaste.dto.sync.ControlChallenge
import com.crosspaste.secure.SecureKeyPairSerializer
import com.crosspaste.secure.SecureStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    fun client(fromA: Boolean): ClipboardPeerClient {
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
                    assertEquals("history", path)
                    val verified = assertNotNull(remote.verify(sender, proof, ClipboardPeerOperation.HISTORY))
                    return remote.reply(verified, Json.encodeToString(target.read(verified)))
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
