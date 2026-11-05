package com.crosspaste.sync.journal

import com.crosspaste.utils.getCodecsUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ClipboardManifestRequest(
    val event: ClipboardEventId,
    val offset: Int = 0,
    val limit: Int = 262144,
)

@Serializable
data class ClipboardBlobRequest(
    val event: ClipboardEventId,
    val sha256: String,
    val offset: Long = 0,
    val limit: Int = 262144,
)

/** No arbitrary digest export: every chunk is authorized through a live, original-signed capture. */
class ClipboardFileTransferService(
    private val history: ClipboardHistoryService,
    private val verifier: ClipboardFileManifestVerifier,
    private val manifestSource: suspend (SignedClipboardChange, ByteArray) -> SignedClipboardFileManifest?,
    private val blobSource: suspend (ClipboardBlob, Long, Int) -> ClipboardBlobChunk,
) {
    suspend fun manifest(request: VerifiedClipboardPeerRequest): ClipboardPayloadChunk {
        require(request.request.operation == ClipboardPeerOperation.FILE_MANIFEST)
        val query = Json.decodeFromString<ClipboardManifestRequest>(request.request.payload)
        require(query.offset >= 0 && query.limit in 1..262144)
        val context = history.fileContext(request, query.event)
        val signed = requireNotNull(manifestSource(context.event, context.metadata)) { "File manifest is unavailable" }
        verifier.verify(signed, context.event, context.metadata)
        val bytes = Json.encodeToString(signed).encodeToByteArray()
        require(bytes.size <= 32 * 1024 * 1024 && query.offset < bytes.size)
        val end = query.offset + minOf(query.limit, bytes.size - query.offset)
        return ClipboardPayloadChunk(
            requireNotNull(context.event.change.contentHash),
            query.offset,
            bytes.size,
            getCodecsUtils().base64Encode(bytes.copyOfRange(query.offset, end)),
        )
    }

    suspend fun blob(request: VerifiedClipboardPeerRequest): ClipboardBlobChunk {
        require(request.request.operation == ClipboardPeerOperation.FILE_BLOB)
        val query = Json.decodeFromString<ClipboardBlobRequest>(request.request.payload)
        require(Regex("[0-9a-f]{64}").matches(query.sha256) && query.offset >= 0 && query.limit in 1..262144)
        val context = history.fileContext(request, query.event)
        val signed = requireNotNull(manifestSource(context.event, context.metadata)) { "File manifest is unavailable" }
        val manifest = verifier.verify(signed, context.event, context.metadata).manifest
        val descriptor =
            requireNotNull(manifest.nodes.mapNotNull { it.blob }.firstOrNull { it.sha256 == query.sha256 }) {
                "Blob is not referenced by this file manifest"
            }
        require(query.offset <= descriptor.size)
        val chunk = blobSource(descriptor, query.offset, query.limit)
        require(
            chunk.sha256 == descriptor.sha256 && chunk.offset == query.offset && chunk.totalBytes == descriptor.size,
        )
        val bytes = getCodecsUtils().base64Decode(chunk.bytes)
        require(bytes.size.toLong() == minOf(query.limit.toLong(), descriptor.size - query.offset))
        return chunk
    }
}
