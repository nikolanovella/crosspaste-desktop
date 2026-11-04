package com.crosspaste.sync.journal

import kotlinx.serialization.Serializable

@Serializable
data class ClipboardEventId(
    val device: String,
    val sequence: Long,
)

@Serializable
data class ClipboardChange(
    val id: ClipboardEventId,
    val clipboard: String,
    val entry: String,
    val clock: Long,
    val parents: Set<ClipboardEventId> = emptySet(),
    val contentHash: String? = null,
    val deleted: Boolean = false,
)

/** Immutable metadata only. Payload availability and the current OS clipboard are separate state. */
@Serializable
data class ClipboardJournalSnapshot(
    val clipboard: String,
    val changes: List<ClipboardChange> = emptyList(),
)

/** Implementations must atomically replace the snapshot, or throw without changing durable state. */
fun interface ClipboardJournalStore {
    fun save(snapshot: ClipboardJournalSnapshot)
}

/**
 * Reconciliation core. Callers serialize access and authenticate each original author before ingest.
 * A transport peer's identity alone does not authenticate events relayed from a third device.
 */
class ClipboardJournal(
    val device: String,
    initial: ClipboardJournalSnapshot,
    private val store: ClipboardJournalStore,
) {
    private var snapshot =
        initial.copy(
            changes =
                initial.changes
                    .map {
                        it.copy(parents = it.parents.toSet())
                    }.sortedWith(compareBy({ it.clock }, { it.id.device }, { it.id.sequence })),
        )

    init {
        require(device.isNotBlank())
        validate(snapshot.changes)
    }

    fun snapshot(): ClipboardJournalSnapshot = snapshot.copy(changes = snapshot.changes.toList())

    fun append(
        entry: String,
        contentHash: String?,
        parents: Set<ClipboardEventId> = emptySet(),
        deleted: Boolean = false,
    ): ClipboardChange {
        require(parents.all { parent -> snapshot.changes.any { it.id == parent } })
        val sequence = (snapshot.changes.filter { it.id.device == device }.maxOfOrNull { it.id.sequence } ?: 0) + 1
        val clock = (snapshot.changes.maxOfOrNull { it.clock } ?: 0) + 1
        val change =
            ClipboardChange(
                ClipboardEventId(device, sequence),
                snapshot.clipboard,
                entry,
                clock,
                parents.toSet(),
                contentHash,
                deleted,
            )
        ingest(listOf(change))
        return change
    }

    /** Transactional ingestion: an invalid batch or a storage failure changes no in-memory state. */
    fun ingest(incoming: List<ClipboardChange>) {
        val combined = snapshot.changes.associateBy { it.id }.toMutableMap()
        incoming.forEach { raw ->
            val change = raw.copy(parents = raw.parents.toSet())
            val existing = combined[change.id]
            require(existing == null || existing == change) { "Conflicting event identity" }
            combined[change.id] = change
        }
        val changes = combined.values.sortedWith(compareBy({ it.clock }, { it.id.device }, { it.id.sequence }))
        validate(changes)
        if (changes == snapshot.changes) return
        val next = ClipboardJournalSnapshot(snapshot.clipboard, changes)
        store.save(next)
        snapshot = next
    }

    /** Never acknowledge across a hole, even when later events have already arrived. */
    fun cursor(): Map<String, Long> =
        snapshot.changes.groupBy { it.id.device }.mapValues { (_, changes) ->
            val sequences = changes.map { it.id.sequence }.toSet()
            var contiguous = 0L
            while (contiguous + 1 in sequences) contiguous++
            contiguous
        }

    /** Bounded batches include all origins, not just records created on this device. */
    fun missing(
        remoteCursor: Map<String, Long>,
        limit: Int = 50,
    ): List<ClipboardChange> {
        require(limit in 1..500)
        require(remoteCursor.values.all { it >= 0 })
        return snapshot.changes.filter { it.id.sequence > (remoteCursor[it.id.device] ?: 0) }.take(limit)
    }

    /** Unresolved ancestors remain pending. Concurrent heads, including deletions, are preserved. */
    fun heads(entry: String): List<ClipboardChange> {
        val resolved = mutableSetOf<ClipboardEventId>()
        snapshot.changes.forEach { change ->
            if (change.parents.all { it in resolved }) resolved.add(change.id)
        }
        val candidates = snapshot.changes.filter { it.entry == entry && it.id in resolved }
        val superseded = candidates.flatMap { it.parents }.toSet()
        return candidates.filter { it.id !in superseded }
    }

    private fun validate(changes: List<ClipboardChange>) {
        require(snapshot.clipboard.isNotBlank())
        require(changes.map { it.id }.toSet().size == changes.size)
        val byId = changes.associateBy { it.id }
        changes.forEach { change ->
            require(change.clipboard == snapshot.clipboard)
            require(change.id.device.isNotBlank() && change.id.sequence > 0 && change.clock > 0)
            require(change.entry.isNotBlank())
            require(if (change.deleted) change.contentHash == null else !change.contentHash.isNullOrBlank())
            require(change.id !in change.parents)
            change.parents.forEach { parentId ->
                byId[parentId]?.let { parent ->
                    require(parent.entry == change.entry && parent.clock < change.clock)
                }
            }
        }
    }
}
