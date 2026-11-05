package com.crosspaste.sync.journal

import com.crosspaste.db.TestDriverFactory
import com.crosspaste.db.createDatabase
import com.crosspaste.dto.sync.ControlChallenge
import com.crosspaste.net.plugin.ClientDecryptPlugin
import com.crosspaste.net.plugin.ClientEncryptPlugin
import com.crosspaste.net.plugin.ServerDecryptionPluginFactory
import com.crosspaste.net.plugin.ServerEncryptPluginFactory
import com.crosspaste.net.routing.ClipboardPeerIdentity
import com.crosspaste.net.routing.clipboardHistoryRouting
import com.crosspaste.net.routing.clipboardIdentityRouting
import com.crosspaste.paste.PasteCollection
import com.crosspaste.paste.item.CreatePasteItemHelper.createTextPasteItem
import com.crosspaste.secure.SecureKeyPairSerializer
import com.crosspaste.utils.HEADER_APP_INSTANCE_ID
import com.crosspaste.utils.HEADER_TARGET_APP_INSTANCE_ID
import com.crosspaste.utils.getCodecsUtils
import com.crosspaste.utils.getJsonUtils
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
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
import java.nio.file.Files
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
                    clipboardHistoryRouting("b", f.b, ClipboardHistoryService(bDb, "b", f.bStore, serializer))
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

            val sender =
                ClipboardPeerClient(
                    f.a,
                    object : ClipboardPeerWire {
                        override suspend fun challenge(peer: String): ControlChallenge =
                            encrypted
                                .get("/clipboard/challenge") {
                                    header(HEADER_APP_INSTANCE_ID, "a")
                                    header(HEADER_TARGET_APP_INSTANCE_ID, peer)
                                    header("secure", "1")
                                }.body()

                        override suspend fun post(
                            peer: String,
                            path: String,
                            proof: ClipboardPeerProof,
                        ): ClipboardPeerResponse {
                            val response =
                                encrypted.post("/clipboard/$path") {
                                    header(HEADER_APP_INSTANCE_ID, "a")
                                    header(HEADER_TARGET_APP_INSTANCE_ID, peer)
                                    header("secure", "1")
                                    contentType(ContentType.Application.Json)
                                    setBody(proof)
                                }
                            assertEquals(HttpStatusCode.OK, response.status)
                            return response.body()
                        }
                    },
                )
            val sending = ClipboardInvitationSender(sender, aInvites)
            val offer = sending.send("shared", "b")
            assertEquals(
                ClipboardInvitationState.PENDING,
                sending.check(offer),
            )
            assertNull(bDb.sharedClipboardDatabaseQueries.getWorkspace("shared").executeAsOneOrNull())
            assertEquals(
                HttpStatusCode.Unauthorized,
                request(
                    "/clipboard/history",
                    ClipboardPeerOperation.HISTORY,
                    Json.encodeToString(ClipboardHistoryRequest(emptyMap())),
                ).second.status,
            )
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
            assertEquals(ClipboardInvitationState.ACCEPTED, sending.check(offer))
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
            val sql = SqlSignedClipboardStore(bDb, "shared")
            val bMembers = ClipboardMembershipStore(bDb, serializer)
            val bJournal =
                SignedClipboardJournal.open(
                    "b",
                    f.bStore.secureKeyPair.signKeyPair.privateKey,
                    { bMembers.signingKey("shared", it) },
                    sql.load(),
                    sql,
                )
            val target = bJournal.append("offline-b-entry", "test-content").change.id
            val aHistory = ClipboardHistoryService(aDb, "a", f.aStore, serializer)
            val bKeys = f.bStore.secureKeyPair.signKeyPair
            val bHistory = ClipboardHistoryService(bDb, "b", f.bStore, serializer)
            val selection = ClipboardSelection("shared", "b", 1, target)
            val authenticator = ClipboardSelectionAuthenticator("shared") { bMembers.signingKey("shared", it) }
            bHistory.selectionStore("shared").ingest(listOf(authenticator.sign(selection, bKeys.privateKey)))
            ClipboardHistoryPuller(sender, aHistory).syncSelection("shared", "b")
            assertEquals(selection, aHistory.selectionStore("shared").latest()?.selection)
            assertEquals(1, ClipboardHistoryPuller(sender, aHistory).pull("shared", "b"))
            assertEquals(0, ClipboardHistoryPuller(sender, aHistory).pull("shared", "b"))
            assertEquals(sql.load(), SqlSignedClipboardStore(aDb, "shared").load())
            getJsonUtils()
            val item = createTextPasteItem(text = "Encrypted HTTP payload fixture")
            val payload =
                Json
                    .encodeToString(
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
                        ),
                    ).encodeToByteArray()
            val hash =
                "sha256:" +
                    getCodecsUtils().base64Encode(
                        CryptographyProvider.Default
                            .get(SHA256)
                            .hasher()
                            .hash(payload),
                    )
            bDb.sharedClipboardDatabaseQueries.insertPayload("shared", hash, payload.decodeToString())
            bJournal.append(Json.encodeToString(listOf("capture/v1", "b", "42")), hash)
            assertEquals(1, ClipboardHistoryPuller(sender, aHistory).pull("shared", "b"))
            val staging = Files.createTempDirectory("clipboard-http-payload-")
            try {
                val receiver = DesktopClipboardPayloadReceiver(ClipboardPayloadTransfer(sender), aHistory, staging)
                assertEquals(payload.size, receiver.receive("b", "shared", hash, payload.size))
                assertEquals(
                    payload.decodeToString(),
                    aDb.sharedClipboardDatabaseQueries.getPayload("shared", hash).executeAsOne(),
                )
            } finally {
                Files.delete(staging)
            }
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
