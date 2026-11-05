package com.crosspaste.sync.journal

import com.crosspaste.dto.sync.ControlChallenge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ClipboardPeerClientTest {
    @Test
    fun `client uses a new challenge and rejects a reply from its previous request`() =
        runTest {
            val f = clipboardPeerFixture()
            var previous: ClipboardPeerResponse? = null
            var calls = 0
            val client =
                ClipboardPeerClient(
                    f.a,
                    object : ClipboardPeerWire {
                        override suspend fun challenge(peer: String): ControlChallenge {
                            assertEquals("b", peer)
                            calls++
                            return f.b.issue("a")
                        }

                        override suspend fun post(
                            peer: String,
                            path: String,
                            proof: ClipboardPeerProof,
                        ): ClipboardPeerResponse {
                            assertEquals("identity", path)
                            val verified = assertNotNull(f.b.verify("a", proof, ClipboardPeerOperation.IDENTITY))
                            val response = f.b.reply(verified, "identity")
                            return previous?.also { previous = response } ?: response.also { previous = it }
                        }
                    },
                )
            assertEquals("identity", client.identity("b", "shared").payload)
            assertFailsWith<IllegalArgumentException> { client.identity("b", "shared") }
            assertEquals(2, calls)
        }

    @Test
    fun `client rejects altered status before it can reach enrollment`() =
        runTest {
            val f = clipboardPeerFixture()
            val client =
                ClipboardPeerClient(
                    f.a,
                    object : ClipboardPeerWire {
                        override suspend fun challenge(peer: String): ControlChallenge = f.b.issue("a")

                        override suspend fun post(
                            peer: String,
                            path: String,
                            proof: ClipboardPeerProof,
                        ): ClipboardPeerResponse {
                            assertEquals("invitation/status", path)
                            val verified = assertNotNull(f.b.verify("a", proof, ClipboardPeerOperation.JOIN_STATUS))
                            return f.b.reply(verified, "pending").copy(payload = "accepted")
                        }
                    },
                )
            assertFailsWith<IllegalArgumentException> { client.status("b", "shared", "offer") }
        }

    @Test
    fun `client propagates cancellation without sending or silently repairing pairing`() =
        runTest {
            val f = clipboardPeerFixture()
            var posted = false
            val client =
                ClipboardPeerClient(
                    f.a,
                    object : ClipboardPeerWire {
                        override suspend fun challenge(peer: String): ControlChallenge =
                            throw CancellationException("cancelled")

                        override suspend fun post(
                            peer: String,
                            path: String,
                            proof: ClipboardPeerProof,
                        ): ClipboardPeerResponse {
                            posted = true
                            error("Must not send after cancellation")
                        }
                    },
                )
            assertFailsWith<CancellationException> { client.identity("b", "shared") }
            assertEquals(false, posted)
        }
}
