package com.crosspaste.net.routing

import com.crosspaste.sync.journal.ClipboardFileTransferService
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

fun Routing.clipboardFileRouting(
    localDevice: String,
    authentication: ClipboardPeerAuthentication,
    files: ClipboardFileTransferService,
) {
    for ((path, operation) in listOf(
        "/clipboard/file-manifest" to ClipboardPeerOperation.FILE_MANIFEST,
        "/clipboard/file-blob" to ClipboardPeerOperation.FILE_BLOB,
    )) {
        post(path) {
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
            if (length != null && length !in 1..16384) {
                call.respond(HttpStatusCode.PayloadTooLarge)
                return@post
            }
            try {
                val verified = authentication.verify(peer, call.receive<ClipboardPeerProof>(), operation)
                if (verified == null) {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@post
                }
                val payload =
                    if (operation == ClipboardPeerOperation.FILE_MANIFEST) {
                        Json.encodeToString(files.manifest(verified))
                    } else {
                        Json.encodeToString(files.blob(verified))
                    }
                call.respond(authentication.reply(verified, payload))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                call.respond(HttpStatusCode.Unauthorized)
            }
        }
    }
}
