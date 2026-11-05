package com.crosspaste.sync.journal

import com.crosspaste.Database
import com.crosspaste.secure.SecureKeyPairSerializer
import com.crosspaste.secure.SecureStore
import com.crosspaste.utils.getCodecsUtils
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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

    /** File access requires a current live head, not merely an old event retained in history. */
    suspend fun fileContext(
        verified: VerifiedClipboardPeerRequest,
        event: ClipboardEventId,
    ): ClipboardFileContext =
        mutex.withLock {
            val request = verified.request
            require(
                request.target == localDevice &&
                    request.operation in
                    setOf(
                        ClipboardPeerOperation.FILE_MANIFEST,
                        ClipboardPeerOperation.FILE_BLOB,
                    ),
            )
            requireMember(request.clipboard, request.sender)
            activeFileContext(request.clipboard, event)
        }

    suspend fun receivingFileContext(
        peer: String,
        clipboard: String,
        event: ClipboardEventId,
    ): ClipboardFileContext =
        mutex.withLock {
            requireMember(clipboard, peer)
            activeFileContext(clipboard, event)
        }

    private suspend fun activeFileContext(
        clipboard: String,
        event: ClipboardEventId,
    ): ClipboardFileContext {
        val store = SqlSignedClipboardStore(database, clipboard)
        val snapshot = store.load()
        open(clipboard, store, snapshot)
        val head =
            ClipboardJournal(
                localDevice,
                ClipboardJournalSnapshot(
                    clipboard,
                    snapshot.changes.map { it.change },
                ),
            ) {}.allHeads().singleOrNull { it.id == event && !it.deleted }
        requireNotNull(head) { "File capture is no longer active in this clipboard" }
        val signed = snapshot.changes.single { it.change.id == head.id }
        val hash = requireNotNull(head.contentHash)
        val metadata =
            requireNotNull(
                database.sharedClipboardDatabaseQueries
                    .getPayload(clipboard, hash)
                    .executeAsOneOrNull(),
            )
        return ClipboardFileContext(signed, metadata.encodeToByteArray())
    }

    suspend fun snapshot(clipboard: String): SignedClipboardSnapshot =
        mutex.withLock {
            val store = SqlSignedClipboardStore(database, clipboard)
            val snapshot = store.load()
            open(clipboard, store, snapshot)
            snapshot
        }

    suspend fun payload(verified: VerifiedClipboardPeerRequest): ClipboardPayloadChunk =
        mutex.withLock {
            val request = verified.request
            require(request.operation == ClipboardPeerOperation.PAYLOAD && request.target == localDevice)
            requireMember(request.clipboard, request.sender)
            val query = Json.decodeFromString<ClipboardPayloadRequest>(request.payload)
            require(query.hash.startsWith("sha256:") && query.hash.length == 51)
            require(query.offset >= 0 && query.limit in 1..262144)
            open(request.clipboard) // Authenticate stored original authors before using their references.
            require(
                SqlSignedClipboardStore(database, request.clipboard).load().changes.any {
                    it.change.contentHash == query.hash && !it.change.deleted
                },
            ) { "Payload is not referenced by this clipboard" }
            val payload =
                requireNotNull(
                    database.sharedClipboardDatabaseQueries
                        .getPayload(
                            request.clipboard,
                            query.hash,
                        ).executeAsOneOrNull(),
                ) {
                    "Payload is not available on this replica"
                }.encodeToByteArray()
            require(query.offset < payload.size)
            val end = query.offset + minOf(query.limit, payload.size - query.offset)
            ClipboardPayloadChunk(
                query.hash,
                query.offset,
                payload.size,
                getCodecsUtils().base64Encode(payload.copyOfRange(query.offset, end)),
            )
        }

    /** Caller must supply a fully staged payload; no row is inserted before its signed hash verifies. */
    suspend fun storePayload(
        clipboard: String,
        hash: String,
        bytes: ByteArray,
    ) = mutex.withLock {
        open(clipboard)
        require(
            SqlSignedClipboardStore(database, clipboard).load().changes.any {
                it.change.contentHash == hash && !it.change.deleted
            },
        ) { "Payload is not referenced by this clipboard" }
        val computed =
            "sha256:" +
                getCodecsUtils().base64Encode(
                    CryptographyProvider.Default
                        .get(SHA256)
                        .hasher()
                        .hash(bytes),
                )
        require(computed == hash) { "Clipboard payload hash did not verify" }
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        val payload = Json.decodeFromString<CapturedClipboardPayload>(text)
        require(payload.originDevice.isNotBlank() && payload.localPasteId > 0 && payload.size >= 0)
        val entry = Json.encodeToString(listOf("capture/v1", payload.originDevice, payload.localPasteId.toString()))
        require(
            SqlSignedClipboardStore(database, clipboard).load().changes.any {
                it.change.contentHash == hash && it.change.id.device == payload.originDevice && it.change.entry == entry
            },
        ) { "Captured payload identity does not match its signed event" }
        database.transaction {
            val queries = database.sharedClipboardDatabaseQueries
            val existing = queries.getPayload(clipboard, hash).executeAsOneOrNull()
            require(existing == null || existing == text) { "Conflicting clipboard payload" }
            if (existing == null) queries.insertPayload(clipboard, hash, text)
        }
    }

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
        snapshot: SignedClipboardSnapshot = store.load(),
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
            snapshot,
            store,
        )
    }
}

class ClipboardFileContext(
    val event: SignedClipboardChange,
    val metadata: ByteArray,
)

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
