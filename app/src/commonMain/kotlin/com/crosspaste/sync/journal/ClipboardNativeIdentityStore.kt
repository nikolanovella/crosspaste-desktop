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
        val nativeSnapshot: String? = null,
    )

    data class Received(
        val id: Long,
        val created: Boolean,
        val paste: PasteData? = null,
    )

    private val queries = database.sharedClipboardDatabaseQueries
    private val digestPattern = Regex("sha256:[A-Za-z0-9+/]{43}=")

    private fun valid(identity: ClipboardCaptureIdentity): Boolean =
        identity.version in 1..2 &&
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

    private fun hasFiles(paste: PasteData): Boolean = paste.getPasteAppearItems().any { it is PasteFiles }

    private fun canonical(
        paste: PasteData,
        logicalFiles: Boolean = true,
    ): String? {
        if (paste.pasteAppearItem == null || !paste.hasValidContent()) return null
        if (logicalFiles &&
            hasFiles(paste)
        ) {
            return ClipboardFileIdentityLayout.descriptor(paste)?.let { ordered(it).toString() }
        }
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

    private suspend fun contentHash(
        canonical: String,
        version: Int = 1,
    ): String =
        hash(
            (
                "CrossPaste/native-capture/content/v$version\n" +
                    canonical
            ).encodeToByteArray(),
        )

    internal suspend fun prepare(paste: PasteData): Prepared? {
        val identity = paste.captureIdentity ?: return null
        if (!valid(identity)) return null
        val canonical = canonical(paste) ?: return null
        if ((identity.version == 2) != hasFiles(paste)) return null
        if (contentHash(canonical, identity.version) != identity.contentHash) return null
        return Prepared(
            identity,
            canonical,
            if (identity.version ==
                2
            ) {
                canonical(paste, logicalFiles = false)
            } else {
                null
            },
        )
    }

    internal suspend fun verified(
        paste: PasteData,
        metadataHash: String,
    ): Prepared? {
        val canonical = canonical(paste) ?: return null
        val version = if (hasFiles(paste)) 2 else 1
        val identity =
            ClipboardCaptureIdentity(
                paste.appInstanceId,
                paste.id,
                metadataHash,
                contentHash(canonical, version),
                version,
            )
        require(valid(identity))
        return Prepared(identity, canonical, if (version == 2) canonical(paste, logicalFiles = false) else null)
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
    internal fun resolve(
        prepared: Prepared,
        verifiedFilePublication: Boolean = false,
    ): PasteData? {
        val identity = prepared.identity
        if (identity.version == 2 && !verifiedFilePublication) return null
        val alias =
            queries
                .getNativeIdentity(
                    identity.originDevice,
                    identity.originPasteId,
                    identity.metadataHash,
                ).executeAsOneOrNull()
                ?: return null
        if (alias.contentHash != identity.contentHash || alias.identityVersion != identity.version.toLong()) return null
        return database.pasteDatabaseQueries
            .getPasteData(
                alias.localPasteId,
                listOf(PasteState.LOADED.toLong()),
                PasteData::mapper,
            ).executeAsOneOrNull()
            ?.takeIf {
                if (identity.version ==
                    2
                ) {
                    alias.nativeSnapshot != null && canonical(it, logicalFiles = false) == alias.nativeSnapshot
                } else {
                    canonical(it) == prepared.canonical
                }
            }
    }

    /** Signed publication can replace an unverified conflicting hint, never another verified payload. */
    internal fun record(
        prepared: Prepared,
        localId: Long,
        authorVerified: Boolean,
        native: PasteData? = null,
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
        if (!authorVerified && identity.version == 2 && previous?.authorVerified == 1L) return
        val verified = authorVerified || previous?.authorVerified == 1L && previous.contentHash == identity.contentHash
        val snapshot =
            if (identity.version == 2) {
                requireNotNull(if (native == null) prepared.nativeSnapshot else canonical(native, logicalFiles = false))
            } else {
                null
            }
        queries.putNativeIdentity(
            identity.originDevice,
            identity.originPasteId,
            identity.metadataHash,
            identity.contentHash,
            localId,
            if (verified) 1 else 0,
            identity.version.toLong(),
            snapshot,
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
            val fileSnapshot = if (hasFiles(paste)) canonical(paste, logicalFiles = false) else null
            val fingerprint = if (fileSnapshot == null) contentHash(canonical) else null
            for (alias in aliases) {
                if ((alias.identityVersion == 1L && alias.contentHash == fingerprint) ||
                    (alias.identityVersion == 2L && fileSnapshot != null && alias.nativeSnapshot == fileSnapshot)
                ) {
                    val identity =
                        ClipboardCaptureIdentity(
                            alias.originDevice,
                            alias.originPasteId,
                            alias.metadataHash,
                            alias.contentHash,
                            alias.identityVersion.toInt(),
                        )
                    if (valid(identity)) return@withContext paste.copy(captureIdentity = identity)
                }
            }
            // A live send may run before the background consumer signs the frozen queue entry.
            for (pending in pendingCaptures) {
                if (pending.originDevice != localDevice) continue
                val capture = CapturedClipboardPayload.from(pending)
                val prepared = captured(capture, hash(Json.encodeToString(capture).encodeToByteArray())) ?: continue
                if (prepared.canonical != canonical || prepared.nativeSnapshot != fileSnapshot) continue
                database.transaction { record(prepared, paste.id, false) }
                return@withContext paste.copy(captureIdentity = prepared.identity)
            }
            return@withContext paste.copy(captureIdentity = null)
        }

    /** Retain an untrusted hint through path binding/chunk transfer, not as a ready alias. */
    suspend fun rememberFile(
        paste: PasteData,
        localId: Long,
    ) = withContext(ioDispatcher) {
        val prepared = prepare(paste) ?: return@withContext
        if (prepared.identity.version == 2) queries.putFileReceipt(localId, Json.encodeToString(prepared.identity))
    }

    /** Called only with a byte-verified private landing, while the native row is locked by SQL. */
    internal fun adoptVerifiedFile(
        candidate: PasteData,
        landed: PasteData,
        search: (PasteData) -> String?,
    ): PasteData {
        require(hasFiles(candidate) && hasFiles(landed) && candidate.pasteState == PasteState.LOADED)
        val adopted =
            candidate.copy(
                pasteAppearItem = landed.pasteAppearItem,
                pasteCollection = landed.pasteCollection,
                pasteType = landed.pasteType,
                size = landed.size,
                hash = landed.hash,
                captureIdentity = null,
            )
        database.pasteDatabaseQueries.adoptSharedFileRepresentation(
            adopted.pasteAppearItem?.toStoredJson(),
            adopted.pasteCollection.toStoredJson(),
            adopted.pasteType.toLong(),
            adopted.size,
            adopted.hash,
            search(adopted),
            adopted.id,
        )
        return adopted
    }

    /** A finished legacy transfer can become an alias; reuse of another row needs verified bytes. */
    internal suspend fun completeFile(
        paste: PasteData,
        verifiedLanding: suspend (ClipboardCaptureIdentity, String) -> VerifiedFileLanding?,
        search: (PasteData) -> String?,
    ): PasteData? =
        withContext(ioDispatcher) {
            val receipt = queries.getFileReceipt(paste.id).executeAsOneOrNull() ?: return@withContext paste
            val identity = Json.decodeFromString<ClipboardCaptureIdentity>(receipt)
            val prepared = prepare(paste.copy(captureIdentity = identity))
            if (prepared == null || identity.version != 2) {
                queries.deleteFileReceipt(paste.id)
                return@withContext paste
            }
            val previous =
                queries
                    .getNativeIdentity(
                        identity.originDevice,
                        identity.originPasteId,
                        identity.metadataHash,
                    ).executeAsOneOrNull()
            val landing = if (previous?.authorVerified == 1L) verifiedLanding(identity, paste.appInstanceId) else null
            database.transactionWithResult<PasteData?> {
                val landed = landing?.activePaste(identity, paste.appInstanceId)
                val current =
                    database.pasteDatabaseQueries
                        .getPasteData(
                            paste.id,
                            listOf(PasteState.LOADED.toLong()),
                            PasteData::mapper,
                        ).executeAsOneOrNull() ?: return@transactionWithResult null
                if (canonical(current, logicalFiles = false) != prepared.nativeSnapshot) {
                    queries.deleteFileReceipt(paste.id)
                    return@transactionWithResult current
                }
                val effective =
                    if (landed != null) {
                        val candidate = resolve(prepared, verifiedFilePublication = true) ?: current
                        val adopted = adoptVerifiedFile(candidate, landed, search)
                        record(prepared, adopted.id, true, adopted)
                        if (adopted.id != current.id) {
                            // Transfer completed into its own paths. Retire only the duplicate row;
                            // never delete or overwrite the legacy destination here.
                            database.pasteDatabaseQueries.markDeletePasteData(listOf(current.id))
                        }
                        adopted
                    } else {
                        record(prepared, current.id, false, current)
                        current
                    }
                queries.deleteFileReceipt(paste.id)
                effective
            }
        }

    internal suspend fun receiveInline(
        paste: PasteData,
        search: (PasteData) -> String?,
        fallback: suspend () -> Long,
        verifiedFileLanding: suspend (ClipboardCaptureIdentity, String) -> VerifiedFileLanding? = { _, _ -> null },
    ): Received =
        withContext(ioDispatcher) {
            if (paste.isFileType()) return@withContext Received(fallback(), true)
            val prepared = prepare(paste) ?: return@withContext Received(fallback(), true)
            val identity = prepared.identity
            val previous =
                if (identity.version ==
                    2
                ) {
                    queries
                        .getNativeIdentity(
                            identity.originDevice,
                            identity.originPasteId,
                            identity.metadataHash,
                        ).executeAsOneOrNull()
                } else {
                    null
                }
            val landing =
                if (previous?.authorVerified ==
                    1L
                ) {
                    verifiedFileLanding(identity, paste.appInstanceId)
                } else {
                    null
                }
            database.transactionWithResult {
                val landed = landing?.activePaste(identity, paste.appInstanceId)
                val existing = resolve(prepared, verifiedFilePublication = landed != null)
                if (existing != null) {
                    val effective =
                        if (landed == null) {
                            existing
                        } else {
                            adoptVerifiedFile(existing, landed, search).also {
                                record(prepared, it.id, true, it)
                            }
                        }
                    Received(effective.id, false, effective)
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

    internal data class VerifiedFileLanding(
        val clipboard: String,
        val event: ClipboardEventId,
        val paste: PasteData,
    )

    private fun VerifiedFileLanding.activePaste(
        identity: ClipboardCaptureIdentity,
        peer: String,
    ): PasteData? {
        if (queries.getWorkspace(clipboard).executeAsOneOrNull()?.enabled != true ||
            queries.getMember(clipboard, peer).executeAsOneOrNull() == null
        ) {
            return null
        }
        val latest = SqlSignedClipboardStore(database, clipboard).load()
        val snapshot = ClipboardJournalSnapshot(clipboard, latest.changes.map { it.change })
        val active =
            ClipboardJournal(peer, snapshot) {}.allHeads().any {
                it.id == event && !it.deleted && it.contentHash == identity.metadataHash
            }
        return paste.takeIf { active }
    }
}
