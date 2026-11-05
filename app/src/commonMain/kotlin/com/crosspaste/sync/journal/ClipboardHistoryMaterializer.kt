package com.crosspaste.sync.journal

import com.crosspaste.Database
import com.crosspaste.paste.PasteCollection
import com.crosspaste.paste.PasteData
import com.crosspaste.paste.PasteState
import com.crosspaste.paste.item.PasteFiles
import com.crosspaste.paste.item.PasteItem
import com.crosspaste.utils.getCodecsUtils
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ClipboardMaterializationResult(
    val created: Int,
    val reused: Int,
    val missingPayloads: Set<String>,
    val awaitingFilePayloads: Set<String>,
)

/** Publishes verified records into native history without selecting or writing the OS clipboard. */
class ClipboardHistoryMaterializer(
    private val database: Database,
    private val history: ClipboardHistoryService,
    private val localDevice: String,
    private val searchContent: (PasteData) -> String?,
    private val render: suspend (Long, Int) -> Unit,
    private val fileLanding: suspend (SignedClipboardChange, ByteArray, PasteData) -> PasteData? = { _, _, _ -> null },
) {
    private val mutex = Mutex()
    private val identities = ClipboardNativeIdentityStore(database)

    private data class Prepared(
        val change: ClipboardChange,
        val payload: String,
        val paste: PasteData,
        val identity: ClipboardNativeIdentityStore.Prepared?,
    )

    suspend fun materialize(
        clipboard: String,
        limit: Int = 50,
    ): ClipboardMaterializationResult =
        mutex.withLock {
            require(limit in 1..500)
            val snapshot = history.snapshot(clipboard)
            val projection =
                ClipboardJournal(
                    localDevice,
                    ClipboardJournalSnapshot(clipboard, snapshot.changes.map { it.change }),
                ) {}
            val heads =
                projection
                    .allHeads()
                    .filterNot { it.deleted }
                    .sortedWith(
                        compareByDescending<ClipboardChange> { it.clock }.thenBy { it.id.device },
                    )
            val queries = database.sharedClipboardDatabaseQueries
            val missing = mutableSetOf<String>()
            val files = mutableSetOf<String>()
            val prepared = mutableListOf<Prepared>()
            var reused = 0
            for (change in heads) {
                val existing =
                    queries
                        .getProjection(
                            clipboard,
                            change.id.device,
                            change.id.sequence,
                        ).executeAsOneOrNull()
                if (existing != null) {
                    require(existing.entryId == change.entry && existing.contentHash == change.contentHash)
                    reused++
                    continue
                }
                if (prepared.size >= limit) break
                val hash = requireNotNull(change.contentHash)
                val payload = queries.getPayload(clipboard, hash).executeAsOneOrNull()
                if (payload == null) {
                    missing.add(hash)
                    continue
                }
                val bytes = payload.encodeToByteArray()
                val actual =
                    "sha256:" +
                        getCodecsUtils().base64Encode(
                            CryptographyProvider.Default
                                .get(SHA256)
                                .hasher()
                                .hash(bytes),
                        )
                require(actual == hash) { "Stored clipboard payload hash changed" }
                val capture = Json.decodeFromString<CapturedClipboardPayload>(payload)
                require(
                    change.id.device == capture.originDevice &&
                        change.entry ==
                        Json.encodeToString(
                            listOf("capture/v1", capture.originDevice, capture.localPasteId.toString()),
                        ),
                )
                require(capture.localPasteId > 0 && capture.size >= 0 && capture.pasteType in 0..Int.MAX_VALUE.toLong())
                val item =
                    requireNotNull(
                        PasteItem.fromStoredJson(capture.pasteAppearItem),
                    ) { "Invalid stored clipboard item" }
                var paste =
                    PasteData(
                        id = capture.localPasteId,
                        appInstanceId = capture.originDevice,
                        pasteAppearItem = item,
                        pasteCollection = PasteCollection.fromStoredJson(capture.pasteCollection),
                        pasteType = capture.pasteType.toInt(),
                        source = capture.source,
                        size = capture.size,
                        hash = capture.originalHash,
                        createTime = capture.createTime,
                        pasteState = PasteState.LOADED,
                        remote = capture.originDevice != localDevice,
                    )
                require(paste.hasValidContent()) { "Stored clipboard content is invalid" }
                if (paste.getPasteAppearItems().any { it is PasteFiles }) {
                    val signed = snapshot.changes.single { it.change.id == change.id }
                    val landed = fileLanding(signed, bytes, paste)
                    if (landed == null) {
                        files.add(hash)
                        continue
                    }
                    require(landed.hasValidContent())
                    paste = landed
                }
                prepared.add(Prepared(change, payload, paste, identities.verified(paste, hash)))
            }
            val created =
                database.transactionWithResult {
                    require(queries.getWorkspace(clipboard).executeAsOneOrNull()?.enabled == true)
                    val latest = SqlSignedClipboardStore(database, clipboard).load()
                    val active =
                        ClipboardJournal(
                            localDevice,
                            ClipboardJournalSnapshot(
                                clipboard,
                                latest.changes.map { it.change },
                            ),
                        ) {}.allHeads().filterNot { it.deleted }.map { it.id }.toSet()
                    var count = 0
                    for (item in prepared) {
                        val change = item.change
                        if (change.id !in active) continue
                        val hash = requireNotNull(change.contentHash)
                        if (queries
                                .getProjection(
                                    clipboard,
                                    change.id.device,
                                    change.id.sequence,
                                ).executeAsOneOrNull() !=
                            null
                        ) {
                            reused++
                            continue
                        }
                        require(queries.getPayload(clipboard, hash).executeAsOneOrNull() == item.payload)
                        val paste = item.paste
                        val original =
                            if (paste.appInstanceId == localDevice) {
                                database.pasteDatabaseQueries
                                    .getPasteData(
                                        paste.id,
                                        listOf(PasteState.LOADED.toLong()),
                                        PasteData::mapper,
                                    ).executeAsOneOrNull()
                            } else {
                                null
                            }
                        val aliased = item.identity?.let(identities::resolve)
                        val localId =
                            if (aliased != null) {
                                reused++
                                aliased.id
                            } else if (original != null &&
                                original.hash == paste.hash &&
                                original.size == paste.size &&
                                original.source == paste.source &&
                                original.createTime == paste.createTime &&
                                original.pasteType == paste.pasteType &&
                                original.pasteAppearItem?.toStoredJson() == paste.pasteAppearItem?.toStoredJson() &&
                                original.pasteCollection.toStoredJson() == paste.pasteCollection.toStoredJson()
                            ) {
                                reused++
                                original.id
                            } else {
                                database.pasteDatabaseQueries.createPasteDataEntity(
                                    paste.appInstanceId,
                                    false,
                                    paste.pasteAppearItem?.toStoredJson(),
                                    paste.pasteCollection.toStoredJson(),
                                    paste.pasteType.toLong(),
                                    paste.source,
                                    paste.size,
                                    paste.hash,
                                    paste.createTime,
                                    searchContent(paste),
                                    PasteState.LOADED.toLong(),
                                    paste.remote,
                                )
                                count++
                                database.pasteDatabaseQueries.getLastId().executeAsOne()
                            }
                        item.identity?.let { identities.record(it, localId, true) }
                        queries.insertProjection(
                            clipboard,
                            change.id.device,
                            change.id.sequence,
                            change.entry,
                            hash,
                            localId,
                        )
                        queries.retainPaste(clipboard, localId)
                    }
                    count
                }
            flushRendering(clipboard, limit)
            ClipboardMaterializationResult(created, reused, missing, files)
        }

    private suspend fun flushRendering(
        clipboard: String,
        limit: Int,
    ) {
        val queries = database.sharedClipboardDatabaseQueries
        for (item in queries.getPendingProjectionRendering(clipboard, limit.toLong()).executeAsList()) {
            val paste =
                requireNotNull(
                    database.pasteDatabaseQueries
                        .getPasteData(
                            item.localPasteId,
                            listOf(PasteState.LOADED.toLong()),
                            PasteData::mapper,
                        ).executeAsOneOrNull(),
                ) { "Materialized history row is not available for rendering" }
            render(item.localPasteId, paste.pasteType)
            queries.completeProjectionRendering(clipboard, item.eventDevice, item.eventSequence)
        }
    }
}
