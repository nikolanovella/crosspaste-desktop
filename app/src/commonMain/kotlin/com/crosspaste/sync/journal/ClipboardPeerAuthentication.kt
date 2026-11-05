package com.crosspaste.sync.journal

import com.crosspaste.dto.sync.ControlChallenge
import com.crosspaste.net.routing.ControlChallengeStore
import com.crosspaste.secure.SecureStore
import com.crosspaste.utils.getCodecsUtils
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ClipboardPeerOperation {
    IDENTITY,
    PROPOSE_JOIN,
    ACCEPT_JOIN,
    JOIN_STATUS,
    HISTORY,
    PAYLOAD,
    FILE_MANIFEST,
    FILE_BLOB,
    SELECTION,
}

@Serializable
data class ClipboardPeerRequest(
    val sender: String,
    val target: String,
    val clipboard: String,
    val operation: ClipboardPeerOperation,
    val challengeId: String,
    val challengeNonce: String,
    val payload: String,
)

@Serializable
data class ClipboardPeerProof(
    val request: ClipboardPeerRequest,
    val authenticationCode: String,
)

@Serializable
data class ClipboardPeerResponse(
    val sender: String,
    val target: String,
    val requestDigest: String,
    val payload: String,
    val authenticationCode: String,
)

class VerifiedClipboardPeerRequest internal constructor(
    val request: ClipboardPeerRequest,
)

class VerifiedClipboardPeerReply internal constructor(
    val peer: String,
    val request: ClipboardPeerRequest,
    val payload: String,
)

/** Authenticates existing paired peers. Successful proof never grants clipboard membership. */
class ClipboardPeerAuthentication(
    private val localDevice: String,
    private val secureStore: SecureStore,
    private val challenges: ControlChallengeStore = ControlChallengeStore(),
) {
    private val codecs = getCodecsUtils()
    private val json = Json { encodeDefaults = true }

    suspend fun issue(peer: String): ControlChallenge {
        require(peer.isNotBlank() && peer.length <= 256 && peer != localDevice)
        require(secureStore.existCryptPublicKey(peer)) { "Peer is not paired" }
        return challenges.issue(peer)
    }

    suspend fun prove(
        peer: String,
        clipboard: String,
        operation: ClipboardPeerOperation,
        challenge: ControlChallenge,
        payload: String = "",
    ): ClipboardPeerProof {
        val request =
            ClipboardPeerRequest(
                localDevice,
                peer,
                clipboard,
                operation,
                codecs.base64Encode(challenge.id),
                codecs.base64Encode(challenge.nonce),
                payload,
            )
        require(validRequest(request))
        require(secureStore.existCryptPublicKey(peer)) { "Peer is not paired" }
        val code = secureStore.getMessageProcessor(peer).authenticationCode(requestBytes(request))
        return ClipboardPeerProof(request, codecs.base64Encode(code))
    }

    suspend fun verify(
        peer: String,
        proof: ClipboardPeerProof,
        operation: ClipboardPeerOperation,
    ): VerifiedClipboardPeerRequest? {
        val request = proof.request
        if (!validRequest(request) ||
            request.sender != peer ||
            request.target != localDevice ||
            request.operation != operation ||
            !secureStore.existCryptPublicKey(peer)
        ) {
            return null
        }
        val code = decode(proof.authenticationCode, 32) ?: return null
        if (!secureStore.getMessageProcessor(peer).verifyAuthentication(requestBytes(request), code)) return null
        if (!challenges.consume(
                peer,
                codecs.base64Decode(request.challengeId),
                codecs.base64Decode(request.challengeNonce),
            )
        ) {
            return null
        }
        return VerifiedClipboardPeerRequest(request)
    }

    suspend fun reply(
        verified: VerifiedClipboardPeerRequest,
        payload: String,
    ): ClipboardPeerResponse {
        val request = verified.request
        require(request.target == localDevice && payload.encodeToByteArray().size <= MAX_RESPONSE_BYTES)
        require(secureStore.existCryptPublicKey(request.sender))
        val unsigned = ClipboardPeerResponse(localDevice, request.sender, digest(request), payload, "")
        val code = secureStore.getMessageProcessor(request.sender).authenticationCode(responseBytes(unsigned))
        return unsigned.copy(authenticationCode = codecs.base64Encode(code))
    }

    suspend fun verifyReply(
        peer: String,
        request: ClipboardPeerRequest,
        response: ClipboardPeerResponse,
    ): String? {
        if (request.sender != localDevice ||
            request.target != peer ||
            response.sender != peer ||
            response.target != localDevice ||
            response.payload.length > MAX_RESPONSE_BYTES ||
            response.payload.encodeToByteArray().size > MAX_RESPONSE_BYTES ||
            !secureStore.existCryptPublicKey(peer)
        ) {
            return null
        }
        if (response.requestDigest != digest(request)) return null
        val code = decode(response.authenticationCode, 32) ?: return null
        if (!secureStore.getMessageProcessor(peer).verifyAuthentication(responseBytes(response), code)) return null
        return response.payload
    }

    suspend fun verifiedReply(
        peer: String,
        request: ClipboardPeerRequest,
        response: ClipboardPeerResponse,
    ): VerifiedClipboardPeerReply? =
        verifyReply(peer, request, response)?.let { VerifiedClipboardPeerReply(peer, request, it) }

    private fun validRequest(request: ClipboardPeerRequest): Boolean =
        listOf(request.sender, request.target, request.clipboard).all { it.isNotBlank() && it.length <= 256 } &&
            request.sender != request.target &&
            request.payload.length <= MAX_REQUEST_BYTES &&
            request.payload.encodeToByteArray().size <= MAX_REQUEST_BYTES &&
            decode(request.challengeId, 16) != null &&
            decode(request.challengeNonce, 32) != null

    private fun decode(
        value: String,
        expectedSize: Int,
    ): ByteArray? {
        if (value.length > 128) return null
        return try {
            codecs.base64Decode(value).takeIf { it.size == expectedSize }
        } catch (
            _: IllegalArgumentException,
        ) {
            null
        }
    }

    private fun requestBytes(request: ClipboardPeerRequest): ByteArray =
        ("CrossPaste/clipboard-peer/request/v1\n" + json.encodeToString(request)).encodeToByteArray()

    private fun responseBytes(response: ClipboardPeerResponse): ByteArray =
        (
            "CrossPaste/clipboard-peer/response/v1\n" +
                json.encodeToString(response.copy(authenticationCode = ""))
        ).encodeToByteArray()

    private suspend fun digest(request: ClipboardPeerRequest): String =
        codecs.base64Encode(
            CryptographyProvider.Default
                .get(SHA256)
                .hasher()
                .hash(requestBytes(request)),
        )

    companion object {
        const val MAX_REQUEST_BYTES = 512 * 1024
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    }
}
