package com.crosspaste.sync.journal

import com.crosspaste.db.TestDriverFactory
import com.crosspaste.db.createDatabase
import com.crosspaste.dto.sync.ControlChallenge
import com.crosspaste.net.plugin.ClientDecryptPlugin
import com.crosspaste.net.plugin.ClientEncryptPlugin
import com.crosspaste.net.plugin.ServerDecryptionPluginFactory
import com.crosspaste.net.plugin.ServerEncryptPluginFactory
import com.crosspaste.net.routing.ClipboardPeerIdentity
import com.crosspaste.net.routing.clipboardIdentityRouting
import com.crosspaste.secure.SecureKeyPairSerializer
import com.crosspaste.utils.HEADER_APP_INSTANCE_ID
import com.crosspaste.utils.HEADER_TARGET_APP_INSTANCE_ID
import com.crosspaste.utils.getCodecsUtils
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClipboardIdentityRoutingTest {
    @Test
    fun `encrypted invitation routes cannot accept remotely and report separate local acceptance`() =
        testApplication {
            val f = clipboardPeerFixture()
            val aDb = createDatabase(TestDriverFactory())
            val bDb = createDatabase(TestDriverFactory())
            val serializer = SecureKeyPairSerializer()
            ClipboardMembershipStore(
                aDb,
                serializer,
            ).createClipboard("shared", "a", f.aStore.secureKeyPair.signKeyPair.publicKey, true)
            val aInvites = ClipboardInvitationService(aDb, "a", f.aStore, serializer)
            val bInvites = ClipboardInvitationService(bDb, "b", f.bStore, serializer)
            val publicKey =
                getCodecsUtils().base64Encode(
                    serializer.encodeSignPublicKey(f.bStore.secureKeyPair.signKeyPair.publicKey),
                )
            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(Json) }
                install(ServerDecryptionPluginFactory(f.bStore).createPlugin())
                install(ServerEncryptPluginFactory(f.bStore).createPlugin())
                routing {
                    clipboardIdentityRouting(
                        "b",
                        f.b,
                        invitations = bInvites,
                    ) { ClipboardPeerIdentity("b", publicKey) }
                }
            }
            val encrypted =
                createClient {
                    install(ContentNegotiation) { json(Json) }
                    install(ClientEncryptPlugin(f.aStore))
                    install(ClientDecryptPlugin(f.aStore))
                }

            suspend fun request(
                path: String,
                operation: ClipboardPeerOperation,
                body: String = "",
            ): Pair<ClipboardPeerProof, io.ktor.client.statement.HttpResponse> {
                val challenge =
                    encrypted
                        .get("/clipboard/challenge") {
                            header(HEADER_APP_INSTANCE_ID, "a")
                            header(HEADER_TARGET_APP_INSTANCE_ID, "b")
                            header("secure", "1")
                        }.body<ControlChallenge>()
                val proof = f.a.prove("b", "shared", operation, challenge, body)
                val response =
                    encrypted.post(path) {
                        header(HEADER_APP_INSTANCE_ID, "a")
                        header(HEADER_TARGET_APP_INSTANCE_ID, "b")
                        header("secure", "1")
                        contentType(ContentType.Application.Json)
                        setBody(proof)
                    }
                return proof to response
            }

            suspend fun verified(
                path: String,
                operation: ClipboardPeerOperation,
                body: String = "",
            ): VerifiedClipboardPeerReply {
                val (proof, response) = request(path, operation, body)
                assertEquals(HttpStatusCode.OK, response.status)
                return assertNotNull(f.a.verifiedReply("b", proof.request, response.body<ClipboardPeerResponse>()))
            }
            val identity = verified("/clipboard/identity", ClipboardPeerOperation.IDENTITY)
            val offer = aInvites.prepare("shared", identity)
            val pending =
                verified("/clipboard/invitation", ClipboardPeerOperation.PROPOSE_JOIN, Json.encodeToString(offer))
            assertEquals(
                ClipboardInvitationState.PENDING,
                Json.decodeFromString<ClipboardInvitationStatus>(pending.payload).state,
            )
            assertNull(bDb.sharedClipboardDatabaseQueries.getWorkspace("shared").executeAsOneOrNull())
            assertEquals(
                HttpStatusCode.Unauthorized,
                request(
                    "/clipboard/invitation/status",
                    ClipboardPeerOperation.ACCEPT_JOIN,
                    Json.encodeToString(ClipboardInvitationLookup(offer.id)),
                ).second.status,
            )
            assertNull(bDb.sharedClipboardDatabaseQueries.getWorkspace("shared").executeAsOneOrNull())
            bInvites.accept(offer.id, contributeLocal = false)
            val accepted =
                verified(
                    "/clipboard/invitation/status",
                    ClipboardPeerOperation.JOIN_STATUS,
                    Json.encodeToString(ClipboardInvitationLookup(offer.id)),
                )
            assertEquals(ClipboardInvitationState.ACCEPTED, aInvites.confirm(accepted))
            assertEquals(
                2,
                aDb.sharedClipboardDatabaseQueries
                    .getMembers("shared")
                    .executeAsList()
                    .size,
            )
            assertEquals(
                2,
                bDb.sharedClipboardDatabaseQueries
                    .getMembers("shared")
                    .executeAsList()
                    .size,
            )
            assertEquals(
                false,
                bDb.sharedClipboardDatabaseQueries
                    .getWorkspace("shared")
                    .executeAsOne()
                    .contributeLocal,
            )
        }

    @Test
    fun `identity route authenticates an encrypted roundtrip and rejects replay without enrollment`() =
        testApplication {
            val f = clipboardPeerFixture()
            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(Json) }
                install(ServerDecryptionPluginFactory(f.bStore).createPlugin())
                install(ServerEncryptPluginFactory(f.bStore).createPlugin())
                routing { clipboardIdentityRouting("b", f.b) { ClipboardPeerIdentity("b", "test-public-key") } }
            }
            val encrypted =
                createClient {
                    install(ContentNegotiation) { json(Json) }
                    install(ClientEncryptPlugin(f.aStore))
                    install(ClientDecryptPlugin(f.aStore))
                }
            val challenge =
                encrypted
                    .get("/clipboard/challenge") {
                        header(HEADER_APP_INSTANCE_ID, "a")
                        header(HEADER_TARGET_APP_INSTANCE_ID, "b")
                        header("secure", "1")
                    }.body<ControlChallenge>()
            val proof = f.a.prove("b", "shared", ClipboardPeerOperation.IDENTITY, challenge)

            suspend fun send() =
                encrypted.post("/clipboard/identity") {
                    header(HEADER_APP_INSTANCE_ID, "a")
                    header(HEADER_TARGET_APP_INSTANCE_ID, "b")
                    header("secure", "1")
                    contentType(ContentType.Application.Json)
                    setBody(proof)
                }
            val response = send()
            assertEquals(HttpStatusCode.OK, response.status)
            val payload = assertNotNull(f.a.verifyReply("b", proof.request, response.body<ClipboardPeerResponse>()))
            assertEquals(
                ClipboardPeerIdentity("b", "test-public-key"),
                Json.decodeFromString<ClipboardPeerIdentity>(payload),
            )
            assertEquals(HttpStatusCode.Unauthorized, send().status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/clipboard/challenge").status)
        }
}
