package com.crosspaste.sync.journal

import com.crosspaste.dto.sync.ControlChallenge
import com.crosspaste.net.plugin.ClientDecryptPlugin
import com.crosspaste.net.plugin.ClientEncryptPlugin
import com.crosspaste.net.plugin.ServerDecryptionPluginFactory
import com.crosspaste.net.plugin.ServerEncryptPluginFactory
import com.crosspaste.net.routing.ClipboardPeerIdentity
import com.crosspaste.net.routing.clipboardIdentityRouting
import com.crosspaste.utils.HEADER_APP_INSTANCE_ID
import com.crosspaste.utils.HEADER_TARGET_APP_INSTANCE_ID
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
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ClipboardIdentityRoutingTest {
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
