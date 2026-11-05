package com.crosspaste.sync.journal

import com.crosspaste.utils.getCodecsUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ClipboardPayloadRequest(
    val hash: String,
    val offset: Int = 0,
    val limit: Int = 262144,
)

@Serializable
data class ClipboardPayloadChunk(
    val hash: String,
    val offset: Int,
    val totalBytes: Int,
    val bytes: String,
)

/** Download into a caller-owned staging sink. Only a final whole-payload hash may commit it. */
class ClipboardPayloadTransfer(
    private val client: ClipboardPeerClient,
) {
    suspend fun download(
        peer: String,
        clipboard: String,
        hash: String,
        maximumBytes: Int,
        write: suspend (ByteArray) -> Unit,
    ): Int {
        require(hash.startsWith("sha256:") && hash.length == 51 && maximumBytes > 0)
        var offset = 0
        var total: Int? = null
        do {
            val request = ClipboardPayloadRequest(hash, offset)
            val reply = client.payload(peer, clipboard, request)
            val chunk = Json.decodeFromString<ClipboardPayloadChunk>(reply.payload)
            require(chunk.hash == hash && chunk.offset == offset)
            require(chunk.totalBytes in 1..maximumBytes && (total == null || chunk.totalBytes == total)) {
                "Clipboard payload size changed or exceeds the configured metadata budget"
            }
            require(chunk.bytes.length <= 349528)
            val bytes = getCodecsUtils().base64Decode(chunk.bytes)
            require(bytes.size in 1..request.limit && bytes.size <= chunk.totalBytes - offset)
            total = chunk.totalBytes
            write(bytes)
            offset += bytes.size
        } while (offset < total!!)
        return offset
    }
}
