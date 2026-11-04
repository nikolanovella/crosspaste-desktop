package com.crosspaste.sync.journal

import com.crosspaste.Database
import com.crosspaste.db.TestDriverFactory
import com.crosspaste.db.createDatabase
import com.crosspaste.net.routing.ClipboardPeerIdentity
import com.crosspaste.secure.SecureKeyPairSerializer
import com.crosspaste.utils.CryptographyUtils
import com.crosspaste.utils.getCodecsUtils
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipboardInvitationServiceTest {
    private class Fixture(
        val peers: ClipboardPeerFixture,
        val aDb: Database,
        val bDb: Database,
    ) {
        val serializer = SecureKeyPairSerializer()
        var now = 1000L
        val a = ClipboardInvitationService(aDb, "a", peers.aStore, serializer, { now })
        var b = ClipboardInvitationService(bDb, "b", peers.bStore, serializer, { now })
        val aMembers = ClipboardMembershipStore(aDb, serializer)
        val bMembers = ClipboardMembershipStore(bDb, serializer)

        suspend fun offer(): ClipboardInvitation {
            val proof = peers.a.prove("b", "shared", ClipboardPeerOperation.IDENTITY, peers.b.issue("a"))
            val verified = assertNotNull(peers.b.verify("a", proof, ClipboardPeerOperation.IDENTITY))
            val key =
                getCodecsUtils().base64Encode(
                    serializer.encodeSignPublicKey(peers.bStore.secureKeyPair.signKeyPair.publicKey),
                )
            val response = peers.b.reply(verified, Json.encodeToString(ClipboardPeerIdentity("b", key)))
            return a.prepare("shared", assertNotNull(peers.a.verifiedReply("b", proof.request, response)))
        }

        suspend fun deliver(offer: ClipboardInvitation): ClipboardInvitationStatus {
            val proof =
                peers.a.prove(
                    "b",
                    "shared",
                    ClipboardPeerOperation.PROPOSE_JOIN,
                    peers.b.issue("a"),
                    Json.encodeToString(offer),
                )
            return b.receive(assertNotNull(peers.b.verify("a", proof, ClipboardPeerOperation.PROPOSE_JOIN)))
        }

        suspend fun poll(offer: ClipboardInvitation): VerifiedClipboardPeerReply {
            val proof =
                peers.a.prove(
                    "b",
                    "shared",
                    ClipboardPeerOperation.JOIN_STATUS,
                    peers.b.issue("a"),
                    Json.encodeToString(ClipboardInvitationLookup(offer.id)),
                )
            val verified = assertNotNull(peers.b.verify("a", proof, ClipboardPeerOperation.JOIN_STATUS))
            val response = peers.b.reply(verified, Json.encodeToString(b.status(verified)))
            return assertNotNull(peers.a.verifiedReply("b", proof.request, response))
        }
    }

    private suspend fun fixture(): Fixture {
        val f =
            Fixture(clipboardPeerFixture(), createDatabase(TestDriverFactory()), createDatabase(TestDriverFactory()))
        f.aMembers.createClipboard("shared", "a", f.peers.aStore.secureKeyPair.signKeyPair.publicKey, false)
        return f
    }

    @Test
    fun `authenticated proposal cannot enroll until local acceptance and accepted history verifies on receiver`() =
        runTest {
            val f = fixture()
            val offer = f.offer()
            assertEquals(ClipboardInvitationState.PENDING, f.deliver(offer).state)
            assertNull(
                f.bDb.sharedClipboardDatabaseQueries
                    .getWorkspace("shared")
                    .executeAsOneOrNull(),
            )
            assertEquals(ClipboardInvitationState.PENDING, f.a.confirm(f.poll(offer)))
            assertNull(f.aMembers.signingKey("shared", "b"))
            val localAcceptance = f.b.accept(offer.id, contributeLocal = true)
            assertEquals(localAcceptance, f.b.accept(offer.id, contributeLocal = true))
            val accepted = f.poll(offer)
            assertEquals(ClipboardInvitationState.ACCEPTED, f.a.confirm(accepted))
            assertEquals(ClipboardInvitationState.ACCEPTED, f.a.confirm(accepted))
            assertNotNull(f.aMembers.signingKey("shared", "b"))
            assertNotNull(f.bMembers.signingKey("shared", "a"))
            assertEquals(
                false,
                f.aDb.sharedClipboardDatabaseQueries
                    .getWorkspace("shared")
                    .executeAsOne()
                    .contributeLocal,
            )
            assertEquals(
                true,
                f.bDb.sharedClipboardDatabaseQueries
                    .getWorkspace("shared")
                    .executeAsOne()
                    .contributeLocal,
            )
            val aStore = SqlSignedClipboardStore(f.aDb, "shared")
            val bStore = SqlSignedClipboardStore(f.bDb, "shared")
            val aJournal =
                SignedClipboardJournal.open(
                    "a",
                    f.peers.aStore.secureKeyPair.signKeyPair.privateKey,
                    { f.aMembers.signingKey("shared", it) },
                    aStore.load(),
                    aStore,
                )
            val bJournal =
                SignedClipboardJournal.open(
                    "b",
                    f.peers.bStore.secureKeyPair.signKeyPair.privateKey,
                    { f.bMembers.signingKey("shared", it) },
                    bStore.load(),
                    bStore,
                )
            val original = aJournal.append("entry", "original-hash")
            bJournal.ingest(aJournal.missing(bJournal.cursor()))
            assertEquals(listOf(original.change), bJournal.heads("entry"))
        }

    @Test
    fun `restart acknowledges saved consent but an expanded member set still needs acceptance`() =
        runTest {
            val f = fixture()
            val offer = f.offer()
            f.deliver(offer)
            f.b.accept(offer.id, contributeLocal = false)
            f.b = ClipboardInvitationService(f.bDb, "b", f.peers.bStore, f.serializer)
            assertEquals(ClipboardInvitationState.ACCEPTED, f.deliver(offer).state)
            assertTrue(
                f.b.invitations.value
                    .isEmpty(),
            )
            assertEquals(ClipboardInvitationState.ACCEPTED, f.a.confirm(f.poll(offer)))
            val newKey = CryptographyUtils.generateSecureKeyPair().signKeyPair.publicKey
            f.aMembers.approveMember("shared", "third-device", newKey)
            val expanded = f.offer()
            assertEquals(ClipboardInvitationState.PENDING, f.deliver(expanded).state)
            assertNull(f.bMembers.signingKey("shared", "third-device"))
            assertEquals(
                false,
                f.bDb.sharedClipboardDatabaseQueries
                    .getWorkspace("shared")
                    .executeAsOne()
                    .contributeLocal,
            )
        }

    @Test
    fun `declined and repeated proposals never enroll or reopen consent`() =
        runTest {
            val f = fixture()
            val offer = f.offer()
            f.deliver(offer)
            f.b.decline(offer.id)
            assertEquals(ClipboardInvitationState.DECLINED, f.deliver(offer).state)
            assertEquals(ClipboardInvitationState.DECLINED, f.a.confirm(f.poll(offer)))
            assertTrue(
                f.b.invitations.value
                    .isEmpty(),
            )
            assertNull(
                f.bDb.sharedClipboardDatabaseQueries
                    .getWorkspace("shared")
                    .executeAsOneOrNull(),
            )
            assertNull(f.aMembers.signingKey("shared", "b"))
        }

    @Test
    fun `conflicting accepted identity rolls back all newly proposed members and preserves settings`() =
        runTest {
            val f = fixture()
            val extra = CryptographyUtils.generateSecureKeyPair().signKeyPair.publicKey
            f.aMembers.approveMember("shared", "0-extra", extra)
            f.bMembers.createClipboard("shared", "b", f.peers.bStore.secureKeyPair.signKeyPair.publicKey, false)
            val conflicting = CryptographyUtils.generateSecureKeyPair().signKeyPair.publicKey
            f.bMembers.approveMember("shared", "a", conflicting)
            val original =
                f.bDb.sharedClipboardDatabaseQueries
                    .getMembers("shared")
                    .executeAsList()
            val offer = f.offer()
            f.deliver(offer)
            assertFailsWith<IllegalArgumentException> { f.b.accept(offer.id, true) }
            assertEquals(
                original,
                f.bDb.sharedClipboardDatabaseQueries
                    .getMembers("shared")
                    .executeAsList(),
            )
            assertNull(f.bMembers.signingKey("shared", "0-extra"))
            assertEquals(
                false,
                f.bDb.sharedClipboardDatabaseQueries
                    .getWorkspace("shared")
                    .executeAsOne()
                    .contributeLocal,
            )
        }

    @Test
    fun `expired or no longer paired invitations cannot enroll devices`() =
        runTest {
            val f = fixture()
            val offer = f.offer()
            f.deliver(offer)
            f.now += 600001
            assertEquals(
                ClipboardInvitationState.EXPIRED,
                Json.decodeFromString<ClipboardInvitationStatus>(f.poll(offer).payload).state,
            )
            assertFailsWith<IllegalArgumentException> { f.b.accept(offer.id, true) }
            assertTrue(
                f.b.invitations.value
                    .isEmpty(),
            )
            val second = f.offer()
            f.deliver(second)
            coEvery { f.peers.bStore.existCryptPublicKey("a") } returns false
            assertFailsWith<IllegalArgumentException> { f.b.accept(second.id, true) }
            assertNull(
                f.bDb.sharedClipboardDatabaseQueries
                    .getWorkspace("shared")
                    .executeAsOneOrNull(),
            )
        }

    @Test
    fun `authenticated proposal with wrong recipient is rejected without a pending invitation`() =
        runTest {
            val f = fixture()
            val offer = f.offer().copy(recipient = "another-device")
            assertFailsWith<IllegalArgumentException> { f.deliver(offer) }
            assertTrue(
                f.b.invitations.value
                    .isEmpty(),
            )
            assertNull(
                f.bDb.sharedClipboardDatabaseQueries
                    .getWorkspace("shared")
                    .executeAsOneOrNull(),
            )
        }
}
