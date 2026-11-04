package com.crosspaste.sync.journal

import com.crosspaste.utils.CryptographyUtils
import com.crosspaste.utils.getCodecsUtils
import dev.whyoleg.cryptography.algorithms.ECDSA
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SignedClipboardChange(
    val change: ClipboardChange,
    val signature: String,
)

@Serializable
data class SignedClipboardSnapshot(
    val clipboard: String,
    val changes: List<SignedClipboardChange> = emptyList(),
)

/** Atomically replaces signed metadata, preserving signatures for future relay and restart. */
fun interface SignedClipboardStore {
    fun save(snapshot: SignedClipboardSnapshot)
}

/**
 * Public keys must come from the clipboard's explicitly trusted membership, never from an event
 * or its transport peer. This layer does not grant membership or approve pairing.
 */
class SignedClipboardJournal private constructor(
    private val device: String,
    private val privateKey: ECDSA.PrivateKey,
    private val memberKey: suspend (String) -> ECDSA.PublicKey?,
    private val store: SignedClipboardStore,
    private var state: SignedClipboardSnapshot,
) {
    private val mutex = Mutex()
    private val codecs = getCodecsUtils()

    suspend fun append(
        entry: String,
        contentHash: String?,
        parents: Set<ClipboardEventId> = emptySet(),
        deleted: Boolean = false,
    ): SignedClipboardChange =
        mutex.withLock {
            val trial = projection()
            val change = trial.append(entry, contentHash, parents, deleted)
            val signature = CryptographyUtils.signData(privateKey) { signingBytes(change) }
            val signed = SignedClipboardChange(change, codecs.base64Encode(signature))
            verify(signed)
            commit(state.changes + signed)
            signed
        }

    /** Verifies original authors even when the batch was relayed through another trusted member. */
    suspend fun ingest(incoming: List<SignedClipboardChange>) =
        mutex.withLock {
            require(incoming.size <= 500)
            incoming.forEach { verify(it) }
            val trial = projection()
            trial.ingest(incoming.map { it.change })
            val byId = state.changes.associateBy { it.change.id }.toMutableMap()
            incoming.forEach { if (!byId.containsKey(it.change.id)) byId[it.change.id] = it }
            val ordered = trial.snapshot().changes.map { byId.getValue(it.id) }
            if (ordered != state.changes) commit(ordered)
        }

    suspend fun cursor(): Map<String, Long> = mutex.withLock { projection().cursor() }

    suspend fun heads(entry: String): List<ClipboardChange> = mutex.withLock { projection().heads(entry) }

    suspend fun missing(
        cursor: Map<String, Long>,
        limit: Int = 50,
    ): List<SignedClipboardChange> =
        mutex.withLock {
            val ids = projection().missing(cursor, limit).map { it.id }.toSet()
            state.changes.filter { it.change.id in ids }
        }

    private fun projection(): ClipboardJournal =
        ClipboardJournal(
            device,
            ClipboardJournalSnapshot(
                state.clipboard,
                state.changes.map {
                    it.change
                },
            ),
        ) { }

    private fun commit(changes: List<SignedClipboardChange>) {
        val next = SignedClipboardSnapshot(state.clipboard, changes)
        store.save(next)
        state = next
    }

    private suspend fun verify(signed: SignedClipboardChange) {
        require(signed.change.clipboard == state.clipboard)
        require(signed.signature.length in 1..256)
        val key = requireNotNull(memberKey(signed.change.id.device)) { "Unknown clipboard member" }
        val valid =
            runCatching {
                CryptographyUtils.verifyData(key, codecs.base64Decode(signed.signature)) { signingBytes(signed.change) }
            }.getOrDefault(false)
        require(valid) { "Invalid clipboard event signature" }
    }

    companion object {
        /** Verifies persisted authorship before exposing any recovered history. */
        suspend fun open(
            device: String,
            privateKey: ECDSA.PrivateKey,
            memberKey: suspend (String) -> ECDSA.PublicKey?,
            snapshot: SignedClipboardSnapshot,
            store: SignedClipboardStore,
        ): SignedClipboardJournal {
            val journal = SignedClipboardJournal(device, privateKey, memberKey, store, snapshot)
            snapshot.changes.forEach { journal.verify(it) }
            journal.projection()
            return journal
        }

        private val json = Json { encodeDefaults = true }

        /** Fixed fields and sorted parent IDs give all platforms identical signed bytes. */
        internal fun signingBytes(change: ClipboardChange): ByteArray =
            (
                "CrossPaste/shared-clipboard/change/v1\n" +
                    json.encodeToString(
                        change.copy(
                            parents = change.parents.sortedWith(compareBy({ it.device }, { it.sequence })).toSet(),
                        ),
                    )
            ).encodeToByteArray()
    }
}
