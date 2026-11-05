package com.crosspaste.sync.journal

import com.crosspaste.db.TestDriverFactory
import com.crosspaste.db.createDatabase
import com.crosspaste.dto.sync.ControlChallenge
import com.crosspaste.net.plugin.ClientDecryptPlugin
import com.crosspaste.net.plugin.ClientEncryptPlugin
import com.crosspaste.net.plugin.ServerDecryptionPluginFactory
import com.crosspaste.net.plugin.ServerEncryptPluginFactory
import com.crosspaste.net.routing.ClipboardPeerIdentity
import com.crosspaste.net.routing.clipboardFileRouting
import com.crosspaste.net.routing.clipboardIdentityRouting
import com.crosspaste.secure.SecureKeyPairSerializer
import com.crosspaste.test.IntegrationTest
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@IntegrationTest
class ClipboardFileRoutingTest {
    @Test
    fun `encrypted relay serves original manifest and file ranges only while membership and live capture permit`() =
        testApplication {
            val f = manifestFixture()
            val root = Files.createTempDirectory("clipboard-file-http-test-")
            try {
                val photos = Files.createDirectory(root.resolve("photos"))
                Files.createDirectory(photos.resolve("empty"))
                Files.writeString(photos.resolve("café.txt"), "hello")
                Files.writeString(root.resolve("thumb.png"), "png")
                val blobs = DesktopClipboardBlobStore(root.resolve("blob-store"))
                val manifests = DesktopClipboardFileManifestStore(root.resolve("manifests"), f.verifier)
                val original =
                    DesktopClipboardFileSnapshotter("a", f.peers.aStore, f.verifier, blobs, manifests) { _, item, _ ->
                        if (item == 0) photos else root.resolve("thumb.png")
                    }.snapshot(f.event, f.metadata, 8)
                val db = createDatabase(TestDriverFactory())
                val serializer = SecureKeyPairSerializer()
                val members = ClipboardMembershipStore(db, serializer)
                members.createClipboard("shared", "b", f.peers.bStore.secureKeyPair.signKeyPair.publicKey, false)
                val sql = SqlSignedClipboardStore(db, "shared")
                sql.save(SignedClipboardSnapshot("shared", listOf(f.event)))
                db.sharedClipboardDatabaseQueries.insertPayload(
                    "shared",
                    f.manifest.metadataHash,
                    f.metadata.decodeToString(),
                )
                val history = ClipboardHistoryService(db, "b", f.peers.bStore, serializer)
                var blobReads = 0
                val files =
                    ClipboardFileTransferService(history, f.verifier, manifests::load) { blob, offset, limit ->
                        blobReads++
                        blobs.readChunk(blob.sha256, offset, limit)
                    }
                application {
                    install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(Json) }
                    install(ServerDecryptionPluginFactory(f.peers.bStore).createPlugin())
                    install(ServerEncryptPluginFactory(f.peers.bStore).createPlugin())
                    routing {
                        clipboardIdentityRouting("b", f.peers.b) { ClipboardPeerIdentity("b", "unused") }
                        clipboardFileRouting("b", f.peers.b, files)
                    }
                }
                val encrypted =
                    createClient {
                        install(ContentNegotiation) { json(Json) }
                        install(ClientEncryptPlugin(f.peers.aStore))
                        install(ClientDecryptPlugin(f.peers.aStore))
                    }

                suspend fun request(
                    path: String,
                    operation: ClipboardPeerOperation,
                    payload: String,
                ): Pair<ClipboardPeerProof, io.ktor.client.statement.HttpResponse> {
                    val challenge =
                        encrypted
                            .get("/clipboard/challenge") {
                                header(HEADER_APP_INSTANCE_ID, "a")
                                header(HEADER_TARGET_APP_INSTANCE_ID, "b")
                                header("secure", "1")
                            }.body<ControlChallenge>()
                    val proof = f.peers.a.prove("b", "shared", operation, challenge, payload)
                    val response =
                        encrypted.post("/clipboard/$path") {
                            header(HEADER_APP_INSTANCE_ID, "a")
                            header(HEADER_TARGET_APP_INSTANCE_ID, "b")
                            header("secure", "1")
                            contentType(ContentType.Application.Json)
                            setBody(proof)
                        }
                    return proof to response
                }
                val manifestQuery = Json.encodeToString(ClipboardManifestRequest(f.event.change.id, limit = 37))
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    request("file-manifest", ClipboardPeerOperation.FILE_MANIFEST, manifestQuery).second.status,
                )
                members.approveMember("shared", "a", f.peers.aStore.secureKeyPair.signKeyPair.publicKey)
                val all = java.io.ByteArrayOutputStream()
                var offset = 0
                var total: Int
                do {
                    val (proof, response) =
                        request(
                            "file-manifest",
                            ClipboardPeerOperation.FILE_MANIFEST,
                            Json.encodeToString(ClipboardManifestRequest(f.event.change.id, offset, 211)),
                        )
                    assertEquals(HttpStatusCode.OK, response.status)
                    val reply = assertNotNull(f.peers.a.verifiedReply("b", proof.request, response.body()))
                    val chunk = Json.decodeFromString<ClipboardPayloadChunk>(reply.payload)
                    assertEquals(offset, chunk.offset)
                    assertEquals(f.manifest.metadataHash, chunk.hash)
                    val bytes = getCodecsUtils().base64Decode(chunk.bytes)
                    all.write(bytes)
                    offset += bytes.size
                    total = chunk.totalBytes
                } while (offset < total)
                val relayed = Json.decodeFromString<SignedClipboardFileManifest>(all.toByteArray().decodeToString())
                assertEquals(original, relayed)
                f.verifier.verify(relayed, f.event, f.metadata)
                val descriptor =
                    original.manifest.nodes
                        .first { it.slot.name == "café.txt" }
                        .blob!!
                val (proof, response) =
                    request(
                        "file-blob",
                        ClipboardPeerOperation.FILE_BLOB,
                        Json.encodeToString(ClipboardBlobRequest(f.event.change.id, descriptor.sha256, 1, 2)),
                    )
                assertEquals(HttpStatusCode.OK, response.status)
                val reply = assertNotNull(f.peers.a.verifiedReply("b", proof.request, response.body()))
                val chunk = Json.decodeFromString<ClipboardBlobChunk>(reply.payload)
                assertEquals(5L, chunk.totalBytes)
                assertEquals("el", getCodecsUtils().base64Decode(chunk.bytes).decodeToString())
                assertEquals(1, blobReads)
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    request(
                        "file-blob",
                        ClipboardPeerOperation.FILE_BLOB,
                        Json.encodeToString(ClipboardBlobRequest(f.event.change.id, "0".repeat(64))),
                    ).second.status,
                )
                assertEquals(1, blobReads)
                val journal =
                    SignedClipboardJournal.open(
                        "b",
                        f.peers.bStore.secureKeyPair.signKeyPair.privateKey,
                        { members.signingKey("shared", it) },
                        sql.load(),
                        sql,
                    )
                journal.append(f.event.change.entry, null, setOf(f.event.change.id), deleted = true)
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    request("file-manifest", ClipboardPeerOperation.FILE_MANIFEST, manifestQuery).second.status,
                )
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    request(
                        "file-blob",
                        ClipboardPeerOperation.FILE_BLOB,
                        Json.encodeToString(ClipboardBlobRequest(f.event.change.id, descriptor.sha256)),
                    ).second.status,
                )
                assertEquals(1, blobReads)
                // Retained history alone does not authorize transfer.
                assertNotNull(manifests.load(f.event, f.metadata))
            } finally {
                require(
                    root.toAbsolutePath().normalize().startsWith(
                        Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize(),
                    ),
                )
                require(root.fileName.toString().startsWith("clipboard-file-http-test-"))
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
            }
        }
}
