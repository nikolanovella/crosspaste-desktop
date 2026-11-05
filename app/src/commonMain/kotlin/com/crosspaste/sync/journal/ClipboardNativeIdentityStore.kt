package com.crosspaste.sync.journal

import com.crosspaste.Database
import com.crosspaste.paste.ClipboardCaptureIdentity
import com.crosspaste.paste.PasteCollection
import com.crosspaste.paste.PasteData
import com.crosspaste.paste.PasteState
import com.crosspaste.paste.item.PasteFiles
import com.crosspaste.paste.item.PasteItem
import com.crosspaste.utils.getCodecsUtils
import com.crosspaste.utils.ioDispatcher
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Identity aliases converge transports; an unverified legacy hint never authenticates an author. */
class ClipboardNativeIdentityStore(
    private val database: Database,
) {
    internal data class Prepared(
        val identity: ClipboardCaptureIdentity,
        val canonical: String,
    )

    data class Received(
        val id: Long,
        val created: Boolean,
        val paste: PasteData? = null,
    )

    private val queries = database.sharedClipboardDatabaseQueries
    private val digestPattern = Regex("sha256:[A-Za-z0-9+/]{43}=")

    private fun valid(identity: ClipboardCaptureIdentity): Boolean =
        identity.version == 1 &&
            identity.originDevice.isNotBlank() &&
            identity.originDevice.length <= 256 &&
            identity.originPasteId > 0 &&
            digestPattern.matches(identity.metadataHash) &&
            digestPattern.matches(identity.contentHash)

    private fun ordered(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { ordered(it.value) })
            is JsonArray -> JsonArray(value.map(::ordered))
            else -> value
        }

    private fun canonical(paste: PasteData): String? {
        val items = paste.getPasteAppearItems()
        // File identity must account for rebinding/renaming and byte availability separately.
        if (items.isEmpty() || items.any { it is PasteFiles } || !paste.hasValidContent()) return null
        return ordered(
            JsonObject(
                mapOf(
                    "primary" to Json.parseToJsonElement(requireNotNull(paste.pasteAppearItem).toStoredJson()),
                    "collection" to
                        JsonArray(paste.pasteCollection.pasteItems.map { Json.parseToJsonElement(it.toStoredJson()) }),
                    "type" to JsonPrimitive(paste.pasteType),
                    "source" to (paste.source?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull),
                    "size" to JsonPrimitive(paste.size),
                    "hash" to JsonPrimitive(paste.hash),
                ),
            ),
        ).toString()
    }

    private suspend fun hash(bytes: ByteArray): String =
        "sha256:" +
            getCodecsUtils().base64Encode(
                CryptographyProvider.Default
                    .get(SHA256)
                    .hasher()
                    .hash(bytes),
            )

    private suspend fun contentHash(canonical: String): String =
        hash(
            (
                "CrossPaste/native-capture/content/v1\n" +
                    canonical
            ).encodeToByteArray(),
        )

    internal suspend fun prepare(paste: PasteData): Prepared? {
        val identity = paste.captureIdentity ?: return null
        if (!valid(identity)) return null
        val canonical = canonical(paste) ?: return null
        if (contentHash(canonical) != identity.contentHash) return null
        return Prepared(identity, canonical)
    }

    internal suspend fun verified(
        paste: PasteData,
        metadataHash: String,
    ): Prepared? {
        val canonical = canonical(paste) ?: return null
        val identity = ClipboardCaptureIdentity(paste.appInstanceId, paste.id, metadataHash, contentHash(canonical))
        require(valid(identity))
        return Prepared(identity, canonical)
    }

    internal suspend fun captured(
        capture: CapturedClipboardPayload,
        metadataHash: String,
    ): Prepared? =
        verified(
            PasteData(
                id = capture.localPasteId,
                appInstanceId = capture.originDevice,
                pasteAppearItem = PasteItem.fromStoredJson(capture.pasteAppearItem),
                pasteCollection = PasteCollection.fromStoredJson(capture.pasteCollection),
                pasteType = capture.pasteType.toInt(),
                source = capture.source,
                size = capture.size,
                hash = capture.originalHash,
                createTime = capture.createTime,
                pasteState = PasteState.LOADED,
                remote = true,
            ),
            metadataHash,
        )

    /** Must run in the transaction that selects/creates the native history row. */
    internal fun resolve(prepared: Prepared): PasteData? {
        val identity = prepared.identity
        val alias =
            queries
                .getNativeIdentity(
                    identity.originDevice,
                    identity.originPasteId,
                    identity.metadataHash,
                ).executeAsOneOrNull()
                ?: return null
        if (alias.contentHash != identity.contentHash) return null
        return database.pasteDatabaseQueries
            .getPasteData(
                alias.localPasteId,
                listOf(PasteState.LOADED.toLong()),
                PasteData::mapper,
            ).executeAsOneOrNull()
            ?.takeIf { canonical(it) == prepared.canonical }
    }

    /** Signed publication can replace an unverified conflicting hint, never another verified payload. */
    internal fun record(
        prepared: Prepared,
        localId: Long,
        authorVerified: Boolean,
    ) {
        val identity = prepared.identity
        val previous =
            queries
                .getNativeIdentity(
                    identity.originDevice,
                    identity.originPasteId,
                    identity.metadataHash,
                ).executeAsOneOrNull()
        if (previous != null && previous.contentHash != identity.contentHash) {
            if (!authorVerified) return
            require(previous.authorVerified == 0L) { "Conflicting verified capture identity" }
        }
        val verified = authorVerified || previous?.authorVerified == 1L && previous.contentHash == identity.contentHash
        queries.putNativeIdentity(
            identity.originDevice,
            identity.originPasteId,
            identity.metadataHash,
            identity.contentHash,
            localId,
            if (verified) 1 else 0,
        )
    }

    suspend fun outgoing(
        paste: PasteData,
        localDevice: String,
    ): PasteData =
        withContext(ioDispatcher) {
            val aliases = queries.getNativeIdentitiesForPaste(paste.id).executeAsList()
            val pendingCaptures = queries.getCapturesForPaste(paste.id).executeAsList()
            if (aliases.isEmpty() && pendingCaptures.isEmpty()) return@withContext paste.copy(captureIdentity = null)
            val canonical = canonical(paste) ?: return@withContext paste.copy(captureIdentity = null)
            val fingerprint = contentHash(canonical)
            for (alias in aliases) {
                if (alias.contentHash == fingerprint) {
                    val identity =
                        ClipboardCaptureIdentity(
                            alias.originDevice,
                            alias.originPasteId,
                            alias.metadataHash,
                            alias.contentHash,
                        )
                    if (valid(identity)) return@withContext paste.copy(captureIdentity = identity)
                }
            }
            // A live send may run before the background consumer signs the frozen queue entry.
            for (pending in pendingCaptures) {
                if (pending.originDevice != localDevice) continue
                val capture = CapturedClipboardPayload.from(pending)
                val prepared = captured(capture, hash(Json.encodeToString(capture).encodeToByteArray())) ?: continue
                if (prepared.canonical != canonical) continue
                database.transaction { record(prepared, paste.id, false) }
                return@withContext paste.copy(captureIdentity = prepared.identity)
            }
            return@withContext paste.copy(captureIdentity = null)
        }

    suspend fun receiveInline(
        paste: PasteData,
        search: (PasteData) -> String?,
        fallback: suspend () -> Long,
    ): Received =
        withContext(ioDispatcher) {
            val prepared = prepare(paste) ?: return@withContext Received(fallback(), true)
            database.transactionWithResult {
                val existing = resolve(prepared)
                if (existing != null) {
                    Received(existing.id, false, existing)
                } else {
                    database.pasteDatabaseQueries.createPasteDataEntity(
                        paste.appInstanceId,
                        paste.favorite,
                        paste.pasteAppearItem?.toStoredJson(),
                        paste.pasteCollection.toStoredJson(),
                        paste.pasteType.toLong(),
                        paste.source,
                        paste.size,
                        paste.hash,
                        paste.createTime,
                        search(paste),
                        PasteState.LOADED.toLong(),
                        paste.remote,
                    )
                    val id = database.pasteDatabaseQueries.getLastId().executeAsOne()
                    record(prepared, id, false)
                    Received(id, true)
                }
            }
        }
}
