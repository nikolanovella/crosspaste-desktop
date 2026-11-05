package com.crosspaste.sync.journal

import com.crosspaste.Database
import com.crosspaste.db.SharedClipboardCaptureEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CapturedClipboardPayload(
    val originDevice: String,
    val localPasteId: Long,
    val pasteAppearItem: String,
    val pasteCollection: String,
    val pasteType: Long,
    val source: String?,
    val size: Long,
    val originalHash: String,
    val createTime: Long,
) {
    companion object {
        fun from(capture: SharedClipboardCaptureEntity): CapturedClipboardPayload =
            CapturedClipboardPayload(
                capture.originDevice,
                capture.localPasteId,
                capture.pasteAppearItem,
                capture.pasteCollection,
                capture.pasteType,
                capture.source,
                capture.size,
                capture.hash,
                capture.createTime,
            )
    }
}

internal data class ClipboardCaptureCommit(
    val capture: SharedClipboardCaptureEntity,
    val entry: String,
    val payload: String,
    val hash: String,
    val identity: ClipboardNativeIdentityStore.Prepared? = null,
)

/** Append-only SQL storage. A stale writer cannot discard events committed since its last read. */
class SqlSignedClipboardStore internal constructor(
    private val database: Database,
    private val clipboard: String,
    private val captureCommits: List<ClipboardCaptureCommit> = emptyList(),
) : SignedClipboardStore {
    private val queries = database.sharedClipboardDatabaseQueries

    fun load(): SignedClipboardSnapshot =
        SignedClipboardSnapshot(
            clipboard,
            queries.getEvents(clipboard).executeAsList().map { row ->
                Json.decodeFromString<SignedClipboardChange>(row.signedChange).also { signed ->
                    require(signed.change.clipboard == row.clipboardId && signed.change.id.device == row.originDevice)
                    require(signed.change.id.sequence == row.originSequence && signed.change.clock == row.lamportClock)
                    require(signed.change.entry == row.entryId)
                }
            },
        )

    override fun save(snapshot: SignedClipboardSnapshot) {
        require(snapshot.clipboard == clipboard)
        database.transaction {
            require(queries.getWorkspace(clipboard).executeAsOneOrNull()?.enabled == true) { "Clipboard not joined" }
            val current = load().changes.associateBy { it.change.id }
            val proposed = snapshot.changes.associateBy { it.change.id }
            require(proposed.size == snapshot.changes.size)
            current.forEach { (id, event) ->
                require(proposed[id] == event) { "Stale or conflicting clipboard writer" }
            }
            snapshot.changes.filter { it.change.id !in current }.forEach { signed ->
                val change = signed.change
                require(change.clipboard == clipboard)
                queries.insertEvent(
                    clipboard,
                    change.id.device,
                    change.id.sequence,
                    change.clock,
                    change.entry,
                    Json.encodeToString(signed),
                )
            }
            captureCommits.forEach { commit ->
                require(commit.capture.clipboardId == clipboard)
                require(
                    queries.getCapture(clipboard, commit.capture.localPasteId).executeAsOneOrNull() == commit.capture,
                )
                require(
                    snapshot.changes.any {
                        it.change.entry == commit.entry &&
                            it.change.contentHash == commit.hash &&
                            it.change.id.device == commit.capture.originDevice &&
                            it.change.parents.isEmpty() &&
                            !it.change.deleted
                    },
                )
                val existing = queries.getPayload(clipboard, commit.hash).executeAsOneOrNull()
                if (existing == null) {
                    queries.insertPayload(clipboard, commit.hash, commit.payload)
                } else {
                    require(existing == commit.payload) { "Conflicting clipboard payload" }
                }
                commit.identity?.let {
                    ClipboardNativeIdentityStore(
                        database,
                    ).record(it, commit.capture.localPasteId, true)
                }
                queries.completeCapture(clipboard, commit.capture.localPasteId)
            }
        }
    }
}
