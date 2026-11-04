package com.crosspaste.sync.journal

import com.crosspaste.net.routing.ControlChallengeStore
import com.crosspaste.secure.SecureMessageProcessor
import com.crosspaste.secure.SecureStore
import com.crosspaste.utils.CryptographyUtils
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class ClipboardPeerFixture(
    val aStore: SecureStore,
    val bStore: SecureStore,
    val a: ClipboardPeerAuthentication,
    val b: ClipboardPeerAuthentication,
)

internal suspend fun clipboardPeerFixture(
    challenges: ControlChallengeStore = ControlChallengeStore(),
): ClipboardPeerFixture {
    val aKeys = CryptographyUtils.generateSecureKeyPair()
    val bKeys = CryptographyUtils.generateSecureKeyPair()
    val aProcessor = SecureMessageProcessor(aKeys.cryptKeyPair.privateKey, bKeys.cryptKeyPair.publicKey)
    val bProcessor = SecureMessageProcessor(bKeys.cryptKeyPair.privateKey, aKeys.cryptKeyPair.publicKey)
    val aStore = mockk<SecureStore>()
    val bStore = mockk<SecureStore>()
    coEvery { aStore.existCryptPublicKey(any()) } returns false
    coEvery { bStore.existCryptPublicKey(any()) } returns false
    coEvery { aStore.existCryptPublicKey("b") } returns true
    coEvery { bStore.existCryptPublicKey("a") } returns true
    coEvery { aStore.getMessageProcessor("b") } returns aProcessor
    coEvery { bStore.getMessageProcessor("a") } returns bProcessor
    return ClipboardPeerFixture(
        aStore,
        bStore,
        ClipboardPeerAuthentication("a", aStore),
        ClipboardPeerAuthentication("b", bStore, challenges),
    )
}

class ClipboardPeerAuthenticationTest {
    @Test
    fun `paired peer proof is one use and invalid body does not burn valid challenge`() =
        runTest {
            val f = clipboardPeerFixture()
            val proof = f.a.prove("b", "shared", ClipboardPeerOperation.IDENTITY, f.b.issue("a"))
            assertNull(
                f.b.verify(
                    "a",
                    proof.copy(request = proof.request.copy(payload = "tampered")),
                    ClipboardPeerOperation.IDENTITY,
                ),
            )
            assertNotNull(f.b.verify("a", proof, ClipboardPeerOperation.IDENTITY))
            assertNull(f.b.verify("a", proof, ClipboardPeerOperation.IDENTITY))
        }

    @Test
    fun `operation recipient author and clipboard are bound to proof`() =
        runTest {
            val f = clipboardPeerFixture()
            val proof = f.a.prove("b", "shared", ClipboardPeerOperation.IDENTITY, f.b.issue("a"))
            val variants =
                listOf(
                    proof.request.copy(operation = ClipboardPeerOperation.PROPOSE_JOIN),
                    proof.request.copy(target = "another"),
                    proof.request.copy(sender = "another"),
                    proof.request.copy(clipboard = "another"),
                )
            variants.forEach { assertNull(f.b.verify("a", proof.copy(request = it), ClipboardPeerOperation.IDENTITY)) }
            assertNull(f.b.verify("unknown", proof, ClipboardPeerOperation.IDENTITY))
            assertNotNull(f.b.verify("a", proof, ClipboardPeerOperation.IDENTITY))
        }

    @Test
    fun `expired challenge cannot authorize an otherwise valid paired request`() =
        runTest {
            var now = 1000L
            val f = clipboardPeerFixture(ControlChallengeStore(now = { now }))
            val proof = f.a.prove("b", "shared", ClipboardPeerOperation.IDENTITY, f.b.issue("a"))
            now += 30001
            assertNull(f.b.verify("a", proof, ClipboardPeerOperation.IDENTITY))
        }

    @Test
    fun `response authenticates body and exact request with a separate proof domain`() =
        runTest {
            val f = clipboardPeerFixture()
            val proof = f.a.prove("b", "shared", ClipboardPeerOperation.IDENTITY, f.b.issue("a"))
            val verified = assertNotNull(f.b.verify("a", proof, ClipboardPeerOperation.IDENTITY))
            val response = f.b.reply(verified, "public identity")
            assertEquals("public identity", f.a.verifyReply("b", proof.request, response))
            assertNull(f.a.verifyReply("b", proof.request, response.copy(payload = "tampered")))
            assertNull(f.a.verifyReply("b", proof.request.copy(clipboard = "another"), response))
            assertNull(
                f.a.verifyReply("b", proof.request, response.copy(authenticationCode = proof.authenticationCode)),
            )
            assertNull(f.a.verifyReply("unknown", proof.request, response))
        }
}
