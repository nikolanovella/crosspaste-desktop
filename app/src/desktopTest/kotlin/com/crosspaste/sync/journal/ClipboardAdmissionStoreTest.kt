package com.crosspaste.sync.journal

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.crosspaste.Database
import com.crosspaste.db.Int2LongAdapter
import com.crosspaste.db.PasteTaskEntity
import com.crosspaste.db.TestDriverFactory
import com.crosspaste.db.createDatabase
import com.crosspaste.dto.sync.ControlChallenge
import com.crosspaste.secure.SecureKeyPair
import com.crosspaste.secure.SecureKeyPairSerializer
import com.crosspaste.utils.CryptographyUtils
import com.crosspaste.utils.getCodecsUtils
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipboardAdmissionStoreTest {
    private class Fixture(
        val peers: ClipboardPeerFixture,
        val cKeys: SecureKeyPair,
    ) {
        val aDb = createDatabase(TestDriverFactory())
        val bDb = createDatabase(TestDriverFactory())
        val serializer = SecureKeyPairSerializer()
        val aMembers = ClipboardMembershipStore(aDb, serializer)
        val bMembers = ClipboardMembershipStore(bDb, serializer)
        val aProofs = ClipboardAdmissionStore(aDb, serializer)
        val bProofs = ClipboardAdmissionStore(bDb, serializer)
        val a = ClipboardHistoryService(aDb, "a", peers.aStore, serializer)
        val b = ClipboardHistoryService(bDb, "b", peers.bStore, serializer)

        suspend fun admission(): ClipboardAdmission {
            val key = getCodecsUtils().base64Encode(serializer.encodeSignPublicKey(cKeys.signKeyPair.publicKey))
            val proposal =
                aProofs.propose(
                    ClipboardAdmissionClaim(
                        "shared",
                        "invite-c",
                        "a",
                        ClipboardMemberKey("c", key),
                        aMembers.memberKeys("shared"),
                    ),
                    peers.aStore.secureKeyPair.signKeyPair.privateKey,
                )
            return aProofs.consent(proposal, cKeys.signKeyPair.privateKey)
        }

        suspend fun publish(admission: ClipboardAdmission): SignedClipboardChange {
            val plan = aProofs.prepare("shared", listOf(admission))
            aDb.transaction { aProofs.commit(plan) }
            val store = SqlSignedClipboardStore(aDb, "shared")
            return SignedClipboardJournal
                .open(
                    "c",
                    cKeys.signKeyPair.privateKey,
                    { aMembers.signingKey("shared", it) },
                    store.load(),
                    store,
                ).append("from-c", "c-content")
        }

        suspend fun reply(
            transform: (ClipboardHistoryBatch) -> ClipboardHistoryBatch = { it },
        ): VerifiedClipboardPeerReply {
            val proof =
                peers.b.prove(
                    "a",
                    "shared",
                    ClipboardPeerOperation.HISTORY,
                    peers.a.issue("b"),
                    Json.encodeToString(ClipboardHistoryRequest(emptyMap())),
                )
            val request = assertNotNull(peers.a.verify("b", proof, ClipboardPeerOperation.HISTORY))
            val response = peers.a.reply(request, Json.encodeToString(transform(a.read(request))))
            return assertNotNull(peers.b.verifiedReply("a", proof.request, response))
        }
    }

    private suspend fun fixture(): Fixture {
        val f = Fixture(clipboardPeerFixture(), CryptographyUtils.generateSecureKeyPair())
        f.aMembers.createClipboard("shared", "a", f.peers.aStore.secureKeyPair.signKeyPair.publicKey, true)
        f.aMembers.approveMember("shared", "b", f.peers.bStore.secureKeyPair.signKeyPair.publicKey)
        f.bMembers.createClipboard("shared", "b", f.peers.bStore.secureKeyPair.signKeyPair.publicKey, false)
        f.bMembers.approveMember("shared", "a", f.peers.aStore.secureKeyPair.signKeyPair.publicKey)
        return f
    }

    @Test
    fun `third device admission arrives with relayed history and survives restart without pairing that device`() =
        runTest {
            val f = fixture()
            val admission = f.admission()
            val event = f.publish(admission)
            assertNull(f.bMembers.signingKey("shared", "c"))
            assertTrue(!f.peers.bStore.existCryptPublicKey("c"))
            assertEquals(1, f.b.ingest(f.reply()))
            assertNotNull(f.bMembers.signingKey("shared", "c"))
            assertEquals(listOf(admission), f.bProofs.all("shared"))
            val restarted = ClipboardHistoryService(f.bDb, "b", f.peers.bStore, f.serializer)
            assertEquals(listOf(event), restarted.snapshot("shared").changes)
            assertEquals(0, restarted.ingest(f.reply()))
            assertEquals(
                false,
                f.bDb.sharedClipboardDatabaseQueries
                    .getWorkspace("shared")
                    .executeAsOne()
                    .contributeLocal,
            )
        }

    @Test
    fun `new paired member presents consent in pull requests while its sponsor is offline`() =
        runTest {
            val f = fixture()
            val serverDb = createDatabase(TestDriverFactory())
            val serverMembers = ClipboardMembershipStore(serverDb, f.serializer)
            serverMembers.createClipboard("shared", "b", f.peers.bStore.secureKeyPair.signKeyPair.publicKey, false)
            serverMembers.approveMember("shared", "c", f.cKeys.signKeyPair.publicKey)
            val server = ClipboardHistoryService(serverDb, "b", f.peers.bStore, f.serializer)
            f.aMembers.approveMember("shared", "c", f.cKeys.signKeyPair.publicKey)
            val aKey =
                getCodecsUtils().base64Encode(
                    f.serializer.encodeSignPublicKey(f.peers.aStore.secureKeyPair.signKeyPair.publicKey),
                )
            val proposal =
                f.aProofs.propose(
                    ClipboardAdmissionClaim(
                        "shared",
                        "c-invited-a",
                        "c",
                        ClipboardMemberKey("a", aKey),
                        serverMembers.memberKeys("shared"),
                    ),
                    f.cKeys.signKeyPair.privateKey,
                )
            val consent = f.aProofs.consent(proposal, f.peers.aStore.secureKeyPair.signKeyPair.privateKey)
            f.aDb.transaction { f.aProofs.recordConsented(consent) }
            val client =
                ClipboardPeerClient(
                    f.peers.a,
                    object : ClipboardPeerWire {
                        override suspend fun challenge(peer: String): ControlChallenge {
                            assertEquals("b", peer)
                            return f.peers.b.issue("a")
                        }

                        override suspend fun post(
                            peer: String,
                            path: String,
                            proof: ClipboardPeerProof,
                        ): ClipboardPeerResponse {
                            assertEquals("b", peer)
                            assertEquals("history", path)
                            val verified = assertNotNull(f.peers.b.verify("a", proof, ClipboardPeerOperation.HISTORY))
                            return f.peers.b.reply(verified, Json.encodeToString(server.read(verified)))
                        }
                    },
                )
            assertFailsWith<IllegalArgumentException> {
                client.history(
                    "b",
                    "shared",
                    ClipboardHistoryRequest(emptyMap()),
                )
            }
            assertNull(serverMembers.signingKey("shared", "a"))
            val d = CryptographyUtils.generateSecureKeyPair()
            val dKey = getCodecsUtils().base64Encode(f.serializer.encodeSignPublicKey(d.signKeyPair.publicKey))
            val otherProposal =
                f.aProofs.propose(
                    proposal.claim.copy(invitationId = "other", recipient = ClipboardMemberKey("d", dKey)),
                    f.cKeys.signKeyPair.privateKey,
                )
            val otherConsent = f.aProofs.consent(otherProposal, d.signKeyPair.privateKey)
            assertFailsWith<IllegalArgumentException> {
                client.history("b", "shared", ClipboardHistoryRequest(emptyMap(), admissions = listOf(otherConsent)))
            }
            assertNull(serverMembers.signingKey("shared", "d"))
            assertEquals(0, ClipboardHistoryPuller(client, f.a).pull("shared", "b"))
            assertNotNull(serverMembers.signingKey("shared", "a"))
            assertEquals(listOf(consent), ClipboardAdmissionStore(serverDb, f.serializer).all("shared"))
            assertTrue(!f.peers.bStore.existCryptPublicKey("c"))
        }

    @Test
    fun `invalid event or missing consent cannot partly commit membership or history`() =
        runTest {
            val f = fixture()
            f.publish(f.admission())
            assertFailsWith<IllegalArgumentException> { f.b.ingest(f.reply { it.copy(admissions = emptyList()) }) }
            assertNull(f.bMembers.signingKey("shared", "c"))
            assertFailsWith<IllegalArgumentException> {
                f.b.ingest(
                    f.reply { batch ->
                        batch.copy(changes = batch.changes.map { it.copy(signature = "AQ==") })
                    },
                )
            }
            assertNull(f.bMembers.signingKey("shared", "c"))
            assertTrue(f.bProofs.all("shared").isEmpty())
            assertTrue(SqlSignedClipboardStore(f.bDb, "shared").load().changes.isEmpty())
            assertEquals(1, f.b.ingest(f.reply()))
        }

    @Test
    fun `tampered scope signatures and known identity replacement are rejected without writes`() =
        runTest {
            val f = fixture()
            val proof = f.admission()
            val variants =
                listOf(
                    proof.copy(recipientSignature = "AQ=="),
                    proof.copy(proposal = proof.proposal.copy(sponsorSignature = "AQ==")),
                    proof.copy(proposal = proof.proposal.copy(claim = proof.proposal.claim.copy(clipboard = "other"))),
                    proof.copy(
                        proposal = proof.proposal.copy(claim = proof.proposal.claim.copy(invitationId = "other")),
                    ),
                    proof.copy(
                        proposal =
                            proof.proposal.copy(
                                claim =
                                    proof.proposal.claim.copy(
                                        recipient =
                                            proof.proposal.claim.recipient
                                                .copy(deviceId = "other"),
                                    ),
                            ),
                    ),
                )
            for (variant in variants) {
                assertFailsWith<IllegalArgumentException> {
                    f.bProofs.prepare(
                        "shared",
                        listOf(variant),
                    )
                }
            }
            val replacement = CryptographyUtils.generateSecureKeyPair()
            f.bMembers.approveMember("shared", "c", replacement.signKeyPair.publicKey)
            assertFailsWith<IllegalArgumentException> { f.bProofs.prepare("shared", listOf(proof)) }
            assertTrue(f.bProofs.all("shared").isEmpty())
        }

    @Test
    fun `unordered admission chains require an existing trust anchor and are idempotent`() =
        runTest {
            val f = fixture()
            val first = f.admission()
            f.publish(first)
            val d = CryptographyUtils.generateSecureKeyPair()
            val dKey = getCodecsUtils().base64Encode(f.serializer.encodeSignPublicKey(d.signKeyPair.publicKey))
            val proposal =
                f.aProofs.propose(
                    ClipboardAdmissionClaim(
                        "shared",
                        "invite-d",
                        "c",
                        ClipboardMemberKey("d", dKey),
                        f.aMembers.memberKeys("shared"),
                    ),
                    f.cKeys.signKeyPair.privateKey,
                )
            val second = f.aProofs.consent(proposal, d.signKeyPair.privateKey)
            assertFailsWith<IllegalArgumentException> { f.bProofs.prepare("shared", listOf(second)) }
            val plan = f.bProofs.prepare("shared", listOf(second, first))
            f.bDb.transaction { f.bProofs.commit(plan) }
            assertNotNull(f.bMembers.signingKey("shared", "d"))
            val repeated = f.bProofs.prepare("shared", listOf(first, second))
            f.bDb.transaction { f.bProofs.commit(repeated) }
            assertEquals(2, f.bProofs.all("shared").size)
            f.bDb.sharedClipboardDatabaseQueries.putWorkspace("shared", false, false)
            assertFailsWith<IllegalArgumentException> { f.bDb.transaction { f.bProofs.commit(plan) } }
        }

    @Test
    fun `member limits and local consent persistence failures roll back the whole join`() =
        runTest {
            val f = fixture()
            val proof = f.admission()
            repeat(
                30,
            ) { index ->
                f.bMembers.approveMember(
                    "shared",
                    "existing-$index",
                    f.peers.aStore.secureKeyPair.signKeyPair.publicKey,
                )
            }
            val before = f.bMembers.memberKeys("shared")
            assertFailsWith<IllegalArgumentException> { f.bProofs.prepare("shared", listOf(proof)) }
            assertFailsWith<IllegalArgumentException> {
                f.bMembers.joinApprovedClipboard(
                    "shared",
                    "b",
                    f.peers.bStore.secureKeyPair.signKeyPair.publicKey,
                    proof.proposal.claim.roster + proof.proposal.claim.recipient,
                    false,
                )
            }
            assertEquals(before, f.bMembers.memberKeys("shared"))
            val fresh = ClipboardMembershipStore(createDatabase(TestDriverFactory()), f.serializer)
            assertFailsWith<IllegalStateException> {
                fresh.joinApprovedClipboard(
                    "new",
                    "b",
                    f.peers.bStore.secureKeyPair.signKeyPair.publicKey,
                    listOf(proof.proposal.claim.recipient),
                    true,
                    onJoined = { error("Persistence failed") },
                )
            }
            assertTrue(fresh.memberKeys("new").isEmpty())
        }

    @Test
    fun `admission migration preserves existing members and workspace preferences`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val db = Database(driver, PasteTaskEntity.Adapter(Int2LongAdapter, Int2LongAdapter))
            db.sharedClipboardDatabaseQueries.putWorkspace("existing", true, false)
            db.sharedClipboardDatabaseQueries.insertMember("existing", "saved-device", "saved-key")
            db.pasteDatabaseQueries.createPasteDataEntity(
                "old-device",
                true,
                null,
                "saved-collection",
                0,
                null,
                9,
                "saved-hash",
                1000,
                null,
                1,
                false,
            )
            val oldId = db.pasteDatabaseQueries.getLastId().executeAsOne()
            driver.execute(null, "DROP TABLE SharedClipboardAdmissionEntity", 0)
            assertEquals(16L, Database.Schema.version)
            Database.Schema.migrate(driver, 13, 14)
            val oldPaste = db.pasteDatabaseQueries.getPasteData(oldId, listOf(1L)).executeAsOne()
            assertEquals("saved-collection", oldPaste.pasteCollection)
            assertEquals("saved-hash", oldPaste.hash)
            assertTrue(oldPaste.favorite)
            assertTrue(
                db.sharedClipboardDatabaseQueries
                    .getAdmissions("existing")
                    .executeAsList()
                    .isEmpty(),
            )
            assertEquals(
                "saved-key",
                db.sharedClipboardDatabaseQueries
                    .getMember("existing", "saved-device")
                    .executeAsOne()
                    .signingPublicKey,
            )
            assertEquals(
                false,
                db.sharedClipboardDatabaseQueries
                    .getWorkspace("existing")
                    .executeAsOne()
                    .contributeLocal,
            )
        } finally {
            driver.close()
        }
    }
}
