package com.crosspaste.sync.journal

import com.crosspaste.utils.getCodecsUtils
import kotlinx.serialization.json.Json

/** Transport checks happen per chunk; original signatures and whole-file hashes remain independent checks. */
class ClipboardFileTransfer(
    private val client: ClipboardPeerClient,
    private val verifier: ClipboardFileManifestVerifier,
) {
    suspend fun manifest(
        peer: String,
        event: SignedClipboardChange,
        metadata: ByteArray,
        maximumBytes: Int,
    ): SignedClipboardFileManifest {
        require(maximumBytes in 1..32 * 1024 * 1024)
        verifier.verifyCapture(event, metadata)
        val parts = mutableListOf<ByteArray>()
        var offset = 0
        var total: Int? = null
        do {
            val query = ClipboardManifestRequest(event.change.id, offset)
            val reply = client.fileManifest(peer, event.change.clipboard, query)
            val chunk = Json.decodeFromString<ClipboardPayloadChunk>(reply.payload)
            require(chunk.hash == event.change.contentHash && chunk.offset == offset)
            require(chunk.totalBytes in 1..maximumBytes && (total == null || total == chunk.totalBytes))
            require(chunk.bytes.length <= 349528)
            val bytes = getCodecsUtils().base64Decode(chunk.bytes)
            require(bytes.size in 1..query.limit && bytes.size <= chunk.totalBytes - offset)
            parts.add(bytes)
            offset += bytes.size
            total = chunk.totalBytes
        } while (offset < total)
        val bytes = ByteArray(offset)
        var position = 0
        parts.forEach { part ->
            part.copyInto(bytes, position)
            position += part.size
        }
        val signed =
            Json.decodeFromString<SignedClipboardFileManifest>(
                bytes.decodeToString(throwOnInvalidSequence = true),
            )
        verifier.verify(signed, event, metadata)
        return signed
    }

    suspend fun blob(
        peer: String,
        manifest: VerifiedClipboardFileManifest,
        descriptor: ClipboardBlob,
        maximumBytes: Long,
        write: suspend (ByteArray) -> Unit,
    ): Long {
        require(descriptor.size >= 0 && descriptor.size <= maximumBytes)
        require(manifest.manifest.nodes.any { it.blob == descriptor })
        var offset = 0L
        do {
            val query = ClipboardBlobRequest(manifest.manifest.event, descriptor.sha256, offset)
            val reply = client.fileBlob(peer, manifest.manifest.clipboard, query)
            val chunk = Json.decodeFromString<ClipboardBlobChunk>(reply.payload)
            require(chunk.sha256 == descriptor.sha256 && chunk.offset == offset && chunk.totalBytes == descriptor.size)
            require(chunk.bytes.length <= 349528)
            val bytes = getCodecsUtils().base64Decode(chunk.bytes)
            require(bytes.size.toLong() == minOf(query.limit.toLong(), descriptor.size - offset))
            write(bytes)
            offset += bytes.size
        } while (offset < descriptor.size)
        return offset
    }
}
