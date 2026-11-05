package com.crosspaste.sync.journal

import com.crosspaste.utils.CryptographyUtils
import com.crosspaste.utils.getCodecsUtils
import dev.whyoleg.cryptography.algorithms.ECDSA
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The authenticated selection stream is separate from capture history. */
@Serializable
data class ClipboardSelection(
    val clipboard: String,
    val author: String,
    val clock: Long,
    val target: ClipboardEventId,
)

internal sealed interface ClipboardSelectionResolution {
    data object None : ClipboardSelectionResolution

    data class Pending(
        val selection: ClipboardSelection,
    ) : ClipboardSelectionResolution

    data class Ready(
        val selection: ClipboardSelection,
        val target: ClipboardChange,
    ) : ClipboardSelectionResolution
}

/**
 * Resolve records after origin authentication. A missing or unavailable newest target must
 * not revive an older clipboard item. History ingestion alone cannot create a selection.
 */
internal fun resolveClipboardSelection(
    clipboard: String,
    selections: Collection<ClipboardSelection>,
    availableHeads: Collection<ClipboardChange>,
): ClipboardSelectionResolution {
    val matching = selections.filter { it.clipboard == clipboard }
    require(matching.all { it.author.isNotBlank() && it.clock > 0 })
    require(matching.groupBy { it.author to it.clock }.values.all { it.distinct().size == 1 }) {
        "Conflicting selection identity"
    }
    val newest =
        matching.maxWithOrNull(compareBy({ it.clock }, { it.author }))
            ?: return ClipboardSelectionResolution.None
    val target =
        availableHeads.firstOrNull {
            it.clipboard == clipboard && it.id == newest.target && !it.deleted
        } ?: return ClipboardSelectionResolution.Pending(newest)
    return ClipboardSelectionResolution.Ready(newest, target)
}

@Serializable
data class SignedClipboardSelection(
    val selection: ClipboardSelection,
    val signature: String,
)

internal class ClipboardSelectionAuthenticator(
    private val clipboard: String,
    private val memberKey: suspend (String) -> ECDSA.PublicKey?,
) {
    private val codecs = getCodecsUtils()

    suspend fun sign(
        selection: ClipboardSelection,
        key: ECDSA.PrivateKey,
    ): SignedClipboardSelection {
        val signature = CryptographyUtils.signData(key) { signingBytes(selection) }
        return SignedClipboardSelection(selection, codecs.base64Encode(signature)).also { verify(it) }
    }

    suspend fun verify(signed: SignedClipboardSelection): ClipboardSelection {
        val selection = signed.selection
        require(selection.clipboard == clipboard)
        require(selection.author.isNotBlank() && selection.clock > 0)
        require(selection.target.device.isNotBlank() && selection.target.sequence > 0)
        require(signed.signature.length in 1..256)
        val key = requireNotNull(memberKey(selection.author)) { "Unknown clipboard member" }
        val valid =
            runCatching {
                CryptographyUtils.verifyData(key, codecs.base64Decode(signed.signature)) { signingBytes(selection) }
            }.getOrDefault(false)
        require(valid) { "Invalid clipboard selection signature" }
        return selection
    }

    private fun signingBytes(selection: ClipboardSelection): ByteArray =
        ("CrossPaste/shared-clipboard/selection/v1\n" + Json.encodeToString(selection)).encodeToByteArray()
}
