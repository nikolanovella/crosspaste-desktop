package com.crosspaste.net.routing

import com.crosspaste.sync.journal.ClipboardHistoryService
import com.crosspaste.sync.journal.ClipboardPeerAuthentication
import com.crosspaste.sync.journal.ClipboardPeerOperation
import com.crosspaste.sync.journal.ClipboardPeerProof
import com.crosspaste.utils.HEADER_APP_INSTANCE_ID
import com.crosspaste.utils.HEADER_TARGET_APP_INSTANCE_ID
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Routing.clipboardHistoryRouting(
    localDevice: String,
    authentication: ClipboardPeerAuthentication,
    history: ClipboardHistoryService,
) {
    post("/clipboard/history") {
        val peer = call.request.headers[HEADER_APP_INSTANCE_ID]
        if (peer.isNullOrBlank() ||
            peer.length > 256 ||
            call.request.headers["secure"] != "1" ||
            call.request.headers[HEADER_TARGET_APP_INSTANCE_ID] != localDevice
        ) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }
        val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (length != null && length !in 1..131072) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@post
        }
        try {
            val verified =
                authentication.verify(
                    peer,
                    call.receive<ClipboardPeerProof>(),
                    ClipboardPeerOperation.HISTORY,
                )
            if (verified == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
            call.respond(authentication.reply(verified, Json.encodeToString(history.read(verified))))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            call.respond(HttpStatusCode.Unauthorized)
        }
    }
}
