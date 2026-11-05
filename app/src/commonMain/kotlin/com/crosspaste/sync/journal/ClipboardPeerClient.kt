package com.crosspaste.sync.journal

import com.crosspaste.dto.sync.ControlChallenge
import com.crosspaste.net.PasteClient
import com.crosspaste.utils.HEADER_TARGET_APP_INSTANCE_ID
import com.crosspaste.utils.buildUrl
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.util.reflect.typeInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Transport supplies delivery only; every operation still needs a fresh paired-peer proof. */
interface ClipboardPeerWire {
    suspend fun challenge(peer: String): ControlChallenge

    suspend fun post(
        peer: String,
        path: String,
        proof: ClipboardPeerProof,
    ): ClipboardPeerResponse
}

class ClipboardPeerUnavailableException :
    IllegalStateException("The other device must be online and support shared clipboards")

/** Uses the existing encrypted client and its Wi-Fi/Bluetooth route selection. */
class PasteClipboardPeerWire(
    private val client: PasteClient,
    private val endpoint: suspend (String) -> (URLBuilder.() -> Unit),
) : ClipboardPeerWire {
    override suspend fun challenge(peer: String): ControlChallenge {
        val address = endpoint(peer)
        val response =
            client.get(
                headersBuilder = {
                    append(HEADER_TARGET_APP_INSTANCE_ID, peer)
                    append("secure", "1")
                },
                urlBuilder = {
                    address()
                    buildUrl("clipboard", "challenge")
                },
            )
        if (response.status != HttpStatusCode.OK) throw ClipboardPeerUnavailableException()
        return response.body()
    }

    override suspend fun post(
        peer: String,
        path: String,
        proof: ClipboardPeerProof,
    ): ClipboardPeerResponse {
        require(path in setOf("identity", "invitation", "invitation/status"))
        val address = endpoint(peer)
        val response =
            client.post(
                message = proof,
                messageType = typeInfo<ClipboardPeerProof>(),
                headersBuilder = {
                    append(HEADER_TARGET_APP_INSTANCE_ID, peer)
                    append("secure", "1")
                },
                urlBuilder = {
                    address()
                    buildUrl("clipboard", *path.split('/').toTypedArray())
                },
            )
        if (response.status != HttpStatusCode.OK) throw ClipboardPeerUnavailableException()
        return response.body()
    }
}

/** No API here can approve a remote invitation, replace pairing, or modify the OS clipboard. */
class ClipboardPeerClient(
    private val authentication: ClipboardPeerAuthentication,
    private val wire: ClipboardPeerWire,
) {
    suspend fun identity(
        peer: String,
        clipboard: String,
    ): VerifiedClipboardPeerReply = exchange(peer, clipboard, ClipboardPeerOperation.IDENTITY, "identity")

    suspend fun propose(offer: ClipboardInvitation): VerifiedClipboardPeerReply =
        exchange(
            offer.recipient,
            offer.clipboard,
            ClipboardPeerOperation.PROPOSE_JOIN,
            "invitation",
            Json.encodeToString(offer),
        )

    suspend fun status(
        peer: String,
        clipboard: String,
        invitationId: String,
    ): VerifiedClipboardPeerReply =
        exchange(
            peer,
            clipboard,
            ClipboardPeerOperation.JOIN_STATUS,
            "invitation/status",
            Json.encodeToString(ClipboardInvitationLookup(invitationId)),
        )

    private suspend fun exchange(
        peer: String,
        clipboard: String,
        operation: ClipboardPeerOperation,
        path: String,
        payload: String = "",
    ): VerifiedClipboardPeerReply {
        val challenge = wire.challenge(peer)
        val proof = authentication.prove(peer, clipboard, operation, challenge, payload)
        val response = wire.post(peer, path, proof)
        return requireNotNull(authentication.verifiedReply(peer, proof.request, response)) {
            "Shared clipboard response did not authenticate"
        }
    }
}
