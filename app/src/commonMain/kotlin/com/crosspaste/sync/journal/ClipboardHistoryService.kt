package com.crosspaste.sync.journal

import com.crosspaste.Database
import com.crosspaste.secure.SecureKeyPairSerializer
import com.crosspaste.secure.SecureStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ClipboardHistoryRequest(
    val cursor: Map<String, Long>,
    val limit: Int = 50,
)

@Serializable
data class ClipboardHistoryBatch(
    val clipboard: String,
    val changes: List<SignedClipboardChange>,
)

/** Shared history is independent of the current OS clipboard and the automatic-paste switch. */
class ClipboardHistoryService(
    private val database: Database,
    private val localDevice: String,
    private val secureStore: SecureStore,
    serializer: SecureKeyPairSerializer,
) {
    private val members = ClipboardMembershipStore(database, serializer)
    private val mutex = Mutex()

    suspend fun read(verified: VerifiedClipboardPeerRequest): ClipboardHistoryBatch =
        mutex.withLock {
            val request = verified.request
            require(request.operation == ClipboardPeerOperation.HISTORY && request.target == localDevice)
            requireMember(request.clipboard, request.sender)
            val query = Json.decodeFromString<ClipboardHistoryRequest>(request.payload)
            require(query.limit in 1..100 && query.cursor.size <= 256)
            require(
                query.cursor.all { (device, sequence) ->
                    device.isNotBlank() &&
                        device.length <= 256 &&
                        sequence >= 0
                },
            )
            val journal = open(request.clipboard)
            ClipboardHistoryBatch(request.clipboard, journal.missing(query.cursor, query.limit))
        }

    suspend fun cursor(clipboard: String): Map<String, Long> = mutex.withLock { open(clipboard).cursor() }

    /** A reply authenticates its transport, while each event must independently authenticate its original author. */
    suspend fun ingest(reply: VerifiedClipboardPeerReply): Int =
        mutex.withLock {
            val request = reply.request
            require(request.operation == ClipboardPeerOperation.HISTORY && request.sender == localDevice)
            requireMember(request.clipboard, reply.peer)
            val query = Json.decodeFromString<ClipboardHistoryRequest>(request.payload)
            require(query.limit in 1..100)
            val batch = Json.decodeFromString<ClipboardHistoryBatch>(reply.payload)
            require(batch.clipboard == request.clipboard && batch.changes.size <= query.limit)
            val store = SqlSignedClipboardStore(database, request.clipboard)
            val before = store.load().changes.size
            val journal = open(request.clipboard, store)
            journal.ingest(batch.changes)
            store.load().changes.size - before
        }

    private suspend fun requireMember(
        clipboard: String,
        peer: String,
    ) {
        require(peer != localDevice && secureStore.existCryptPublicKey(peer)) { "Device is not paired" }
        require(
            database.sharedClipboardDatabaseQueries
                .getWorkspace(clipboard)
                .executeAsOneOrNull()
                ?.enabled == true,
        ) {
            "Clipboard is not joined"
        }
        requireNotNull(members.signingKey(clipboard, peer)) { "Device is not a clipboard member" }
    }

    private suspend fun open(
        clipboard: String,
        store: SqlSignedClipboardStore = SqlSignedClipboardStore(database, clipboard),
    ): SignedClipboardJournal {
        require(
            database.sharedClipboardDatabaseQueries
                .getWorkspace(clipboard)
                .executeAsOneOrNull()
                ?.enabled == true,
        )
        val own = requireNotNull(members.signingKey(clipboard, localDevice)) { "Local membership is missing" }
        val serializer = SecureKeyPairSerializer()
        require(
            serializer.encodeSignPublicKey(own).contentEquals(
                serializer.encodeSignPublicKey(secureStore.secureKeyPair.signKeyPair.publicKey),
            ),
        ) { "Local signing identity changed" }
        return SignedClipboardJournal.open(
            localDevice,
            secureStore.secureKeyPair.signKeyPair.privateKey,
            { members.signingKey(clipboard, it) },
            store.load(),
            store,
        )
    }
}

/** One bounded background step; callers repeat while progress is made and resume after reconnects. */
class ClipboardHistoryPuller(
    private val client: ClipboardPeerClient,
    private val history: ClipboardHistoryService,
) {
    suspend fun pull(
        clipboard: String,
        peer: String,
        limit: Int = 50,
    ): Int {
        require(limit in 1..100)
        val cursor = history.cursor(clipboard)
        return history.ingest(client.history(peer, clipboard, ClipboardHistoryRequest(cursor, limit)))
    }
}
