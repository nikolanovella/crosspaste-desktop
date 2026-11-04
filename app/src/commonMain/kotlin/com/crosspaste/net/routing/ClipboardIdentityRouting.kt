package com.crosspaste.net.routing

import com.crosspaste.sync.journal.ClipboardInvitationService
import com.crosspaste.sync.journal.ClipboardInvitationStatus
import com.crosspaste.sync.journal.ClipboardPeerAuthentication
import com.crosspaste.sync.journal.ClipboardPeerOperation
import com.crosspaste.sync.journal.ClipboardPeerProof
import com.crosspaste.sync.journal.VerifiedClipboardPeerRequest
import com.crosspaste.utils.HEADER_APP_INSTANCE_ID
import com.crosspaste.utils.HEADER_TARGET_APP_INSTANCE_ID
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ClipboardPeerIdentity(
    val deviceId: String,
    val signingPublicKey: String,
    val protocolVersion: Int = 1,
)

/** Read-only identity bootstrap for already paired peers; no enrollment or radio activation. */
fun Routing.clipboardIdentityRouting(
    localDevice: String,
    authentication: ClipboardPeerAuthentication,
    invitations: ClipboardInvitationService? = null,
    identity: suspend () -> ClipboardPeerIdentity,
) {
    fun ApplicationCall.peer(): String? =
        request.headers[HEADER_APP_INSTANCE_ID]?.takeIf {
            it.isNotBlank() &&
                it.length <= 256 &&
                request.headers["secure"] == "1" &&
                request.headers[HEADER_TARGET_APP_INSTANCE_ID] == localDevice
        }

    get("/clipboard/challenge") {
        val peer = call.peer()
        if (peer == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return@get
        }
        try {
            call.respond(authentication.issue(peer))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            call.respond(HttpStatusCode.Unauthorized)
        }
    }

    post("/clipboard/identity") {
        val peer = call.peer()
        if (peer == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }
        // Reject oversized declared bodies before parsing. The normal server decryption plugin
        // also bounds secure chunked bodies before they reach deserialization.
        val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (length != null && length !in 1..16384) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@post
        }
        try {
            val verified =
                authentication.verify(
                    peer,
                    call.receive<ClipboardPeerProof>(),
                    ClipboardPeerOperation.IDENTITY,
                )
            if (verified == null || verified.request.payload.isNotEmpty()) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
            val own = identity()
            require(own.deviceId == localDevice)
            call.respond(authentication.reply(verified, Json.encodeToString(own)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            call.respond(HttpStatusCode.Unauthorized)
        }
    }

    suspend fun invitationRequest(
        call: ApplicationCall,
        operation: ClipboardPeerOperation,
        action: suspend (VerifiedClipboardPeerRequest) -> ClipboardInvitationStatus,
    ) {
        val peer = call.peer()
        if (peer == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }
        val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (length != null && length !in 1..32768) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return
        }
        try {
            val verified = authentication.verify(peer, call.receive<ClipboardPeerProof>(), operation)
            if (verified == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return
            }
            call.respond(authentication.reply(verified, Json.encodeToString(action(verified))))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            call.respond(HttpStatusCode.Unauthorized)
        }
    }

    invitations?.let { service ->
        post("/clipboard/invitation") { invitationRequest(call, ClipboardPeerOperation.PROPOSE_JOIN, service::receive) }
        post(
            "/clipboard/invitation/status",
        ) { invitationRequest(call, ClipboardPeerOperation.JOIN_STATUS, service::status) }
    }
}
