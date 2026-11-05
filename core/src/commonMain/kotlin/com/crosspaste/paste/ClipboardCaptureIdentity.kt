package com.crosspaste.paste

import kotlinx.serialization.Serializable

/** Transport hint only. Original authorship is established separately by the signed journal. */
@Serializable
data class ClipboardCaptureIdentity(
    val originDevice: String,
    val originPasteId: Long,
    val metadataHash: String,
    val contentHash: String,
    val version: Int = 1,
)
