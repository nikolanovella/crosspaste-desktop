package com.crosspaste.sync.journal

import kotlinx.serialization.Serializable

@Serializable
data class ClipboardBlob(
    val sha256: String,
    val size: Long,
    val captureHash: String,
)

@Serializable
data class ClipboardBlobChunk(
    val sha256: String,
    val offset: Long,
    val totalBytes: Long,
    val bytes: String,
)
