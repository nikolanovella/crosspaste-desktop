package com.crosspaste.sync.journal

import com.crosspaste.Database
import com.crosspaste.secure.SecureKeyPairSerializer
import com.crosspaste.utils.getCodecsUtils
import dev.whyoleg.cryptography.algorithms.ECDSA

/**
 * Local, explicitly approved membership. This is not a pairing or invitation verifier.
 * Network code must authenticate the existing paired peer and obtain local approval before
 * calling approveMember; public keys supplied by clipboard events are never enrolled here.
 */
class ClipboardMembershipStore(
    private val database: Database,
    private val serializer: SecureKeyPairSerializer,
) {
    private val queries = database.sharedClipboardDatabaseQueries
    private val codecs = getCodecsUtils()

    suspend fun createClipboard(
        clipboard: String,
        localDevice: String,
        publicKey: ECDSA.PublicKey,
        contributeLocal: Boolean,
    ) {
        require(clipboard.isNotBlank() && localDevice.isNotBlank())
        val encoded = encodeMemberKey(publicKey)
        database.transaction {
            require(queries.getWorkspace(clipboard).executeAsOneOrNull() == null) { "Clipboard already exists" }
            queries.putWorkspace(clipboard, true, contributeLocal)
            queries.insertMember(clipboard, localDevice, encoded)
        }
    }

    /** Idempotent approval cannot silently replace a known member's signing identity. */
    suspend fun approveMember(
        clipboard: String,
        device: String,
        publicKey: ECDSA.PublicKey,
    ) {
        require(clipboard.isNotBlank() && device.isNotBlank())
        val encoded = encodeMemberKey(publicKey)
        database.transaction {
            require(queries.getWorkspace(clipboard).executeAsOneOrNull()?.enabled == true) { "Clipboard not joined" }
            val existing = queries.getMember(clipboard, device).executeAsOneOrNull()
            if (existing == null) {
                queries.insertMember(clipboard, device, encoded)
            } else {
                require(
                    existing.signingPublicKey == encoded,
                ) { "Clipboard member key changed; explicit recovery required" }
            }
        }
    }

    /** Historical signing identities remain separate from transport availability and paste switches. */
    suspend fun signingKey(
        clipboard: String,
        device: String,
    ): ECDSA.PublicKey? =
        queries.getMember(clipboard, device).executeAsOneOrNull()?.let {
            serializer.decodeSignPublicKey(codecs.base64Decode(it.signingPublicKey))
        }

    private suspend fun encodeMemberKey(key: ECDSA.PublicKey): String {
        val bytes = serializer.encodeSignPublicKey(key)
        // Validate against the application's P-256 decoder before committing an unreadable key.
        serializer.decodeSignPublicKey(bytes)
        return codecs.base64Encode(bytes)
    }
}
