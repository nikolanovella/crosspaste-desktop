package com.crosspaste.sync.journal

import com.crosspaste.db.TestDriverFactory
import com.crosspaste.db.createDatabase
import com.crosspaste.dto.sync.ControlChallenge
import com.crosspaste.net.routing.ClipboardPeerIdentity
import com.crosspaste.secure.SecureKeyPairSerializer
import com.crosspaste.utils.getCodecsUtils
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class SetupFixture(
    val peers: ClipboardPeerFixture,
) {
    val local = createDatabase(TestDriverFactory())
    val remote = createDatabase(TestDriverFactory())
    val serializer = SecureKeyPairSerializer()
    var now = 0L
    var dropAcknowledgement = false
    val a = ClipboardInvitationService(local, "a", peers.aStore, serializer, { now })
    val b = ClipboardInvitationService(remote, "b", peers.bStore, serializer, { now })

    suspend fun controller(): ClipboardSetupController {
        val encoded =
            getCodecsUtils().base64Encode(
                serializer.encodeSignPublicKey(peers.bStore.secureKeyPair.signKeyPair.publicKey),
            )
        val client =
            ClipboardPeerClient(
                peers.a,
                object : ClipboardPeerWire {
                    override suspend fun challenge(peer: String): ControlChallenge = peers.b.issue("a")

                    override suspend fun post(
                        peer: String,
                        path: String,
                        proof: ClipboardPeerProof,
                    ): ClipboardPeerResponse {
                        val operation =
                            when (path) {
                                "identity" -> ClipboardPeerOperation.IDENTITY
                                "invitation" -> ClipboardPeerOperation.PROPOSE_JOIN
                                "invitation/status" -> ClipboardPeerOperation.JOIN_STATUS
                                else -> error("Unexpected operation")
                            }
                        val verified = assertNotNull(peers.b.verify("a", proof, operation))
                        val payload =
                            when (path) {
                                "identity" -> Json.encodeToString(ClipboardPeerIdentity("b", encoded))
                                "invitation" ->
                                    Json.encodeToString(b.receive(verified)).also {
                                        if (dropAcknowledgement) error("Lost acknowledgement")
                                    }
                                else -> Json.encodeToString(b.status(verified))
                            }
                        return peers.b.reply(verified, payload)
                    }
                },
            )
        return ClipboardSetupController(
            local,
            "a",
            peers.aStore,
            ClipboardMembershipStore(local, serializer),
            ClipboardInvitationSender(client, a),
            { false },
            { now },
        )
    }
}

class ClipboardSetupControllerTest {
    @Test
    fun `refresh is read only and explicit setup creates one clipboard using current recording choice`() =
        runTest {
            val f = SetupFixture(clipboardPeerFixture())
            val controller = f.controller()
            controller.refresh()
            assertTrue(
                f.local.sharedClipboardDatabaseQueries
                    .getWorkspaces()
                    .executeAsList()
                    .isEmpty(),
            )
            controller.create()
            val id = assertNotNull(controller.state.value.clipboard)
            assertEquals(listOf("a"), controller.state.value.members)
            assertFalse(
                f.local.sharedClipboardDatabaseQueries
                    .getWorkspace(id)
                    .executeAsOne()
                    .contributeLocal,
            )
            controller.create()
            assertEquals(id, controller.state.value.clipboard)
            assertEquals(
                1,
                f.local.sharedClipboardDatabaseQueries
                    .getWorkspaces()
                    .executeAsList()
                    .size,
            )
            f.local.sharedClipboardDatabaseQueries.putWorkspace(id, false, false)
            controller.resume()
            assertTrue(controller.state.value.enabled)
            assertFalse(
                f.local.sharedClipboardDatabaseQueries
                    .getWorkspace(id)
                    .executeAsOne()
                    .contributeLocal,
            )
        }

    @Test
    fun `connected transport does not enroll until recipient explicitly accepts and authenticated status verifies`() =
        runTest {
            val f = SetupFixture(clipboardPeerFixture())
            val controller = f.controller()
            controller.create()
            controller.connect("b")
            assertEquals(
                ClipboardInvitationState.PENDING,
                controller.state.value.connections
                    .single()
                    .state,
            )
            assertEquals(listOf("a"), controller.state.value.members)
            controller.refresh()
            assertEquals(listOf("a"), controller.state.value.members)
            val offer =
                f.b.invitations.value
                    .single()
            f.b.accept(offer.id, false) // Explicit consent in isolated test state; no real device UI is automated.
            controller.refresh()
            assertEquals(
                ClipboardInvitationState.ACCEPTED,
                controller.state.value.connections
                    .single()
                    .state,
            )
            assertEquals(listOf("a", "b"), controller.state.value.members)
            assertNull(controller.state.value.error)
            assertFalse(controller.state.value.busy)
        }

    @Test
    fun `lost proposal acknowledgement retains exact offer so acceptance can still be checked`() =
        runTest {
            val f = SetupFixture(clipboardPeerFixture())
            val controller = f.controller()
            controller.create()
            f.dropAcknowledgement = true
            controller.connect("b")
            assertNotNull(controller.state.value.error)
            assertEquals(
                ClipboardInvitationState.PENDING,
                controller.state.value.connections
                    .single()
                    .state,
            )
            val offer =
                f.b.invitations.value
                    .single()
            f.b.accept(offer.id, false)
            controller.refresh()
            assertEquals(listOf("a", "b"), controller.state.value.members)
            assertEquals(
                ClipboardInvitationState.ACCEPTED,
                controller.state.value.connections
                    .single()
                    .state,
            )
        }

    @Test
    fun `expired requests and unknown devices never grant clipboard membership`() =
        runTest {
            val f = SetupFixture(clipboardPeerFixture())
            val controller = f.controller()
            controller.create()
            controller.connect("unknown")
            assertTrue(
                controller.state.value.connections
                    .isEmpty(),
            )
            assertNotNull(controller.state.value.error)
            controller.connect("b")
            f.now = 10 * 60 * 1000L
            controller.refresh()
            assertEquals(
                ClipboardInvitationState.EXPIRED,
                controller.state.value.connections
                    .single()
                    .state,
            )
            assertEquals(listOf("a"), controller.state.value.members)
            assertFalse(controller.state.value.busy)
        }
}
