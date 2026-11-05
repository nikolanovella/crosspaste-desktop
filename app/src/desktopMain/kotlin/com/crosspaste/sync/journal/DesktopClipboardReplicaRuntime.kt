package com.crosspaste.sync.journal

import com.crosspaste.Database
import com.crosspaste.paste.PasteCollection
import com.crosspaste.paste.item.PasteFiles
import com.crosspaste.paste.item.PasteItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class ClipboardReplicaTick(
    val captured: Int,
    val pulled: Int,
    val metadata: Int,
    val queuedFiles: Int,
    val created: Int,
    val failures: Int,
)

/** Metadata reconciliation never waits for the bounded file queue. Neither lane selects the OS clipboard. */
internal class DesktopClipboardReplicaRuntime(
    private val database: Database,
    private val localDevice: String,
    private val history: ClipboardHistoryService,
    private val peers: suspend (String) -> List<String>,
    private val drain: suspend (String, Int) -> Int,
    private val pull: suspend (String, String, Int) -> Int,
    private val metadata: suspend (String, String, String, Int) -> Unit,
    private val snapshot: suspend (SignedClipboardChange, ByteArray, Long) -> Unit,
    private val receiveFiles: suspend (String, SignedClipboardChange, ByteArray, Int, Long) -> Unit,
    private val materialize: suspend (String, Int) -> ClipboardMaterializationResult,
    private val fileBudget: () -> Long = { 8L * 1024 * 1024 * 1024 },
) {
    private data class FileKey(
        val clipboard: String,
        val event: ClipboardEventId,
    )

    private data class FileWork(
        val key: FileKey,
        val signed: SignedClipboardChange,
        val metadata: ByteArray,
        val peers: List<String>,
    )

    private val tickMutex = Mutex()
    private val pendingMutex = Mutex()
    private val pending = mutableSetOf<FileKey>()
    private val files = Channel<FileWork>(64)
    private val offsets = mutableMapOf<String, Int>()
    private var job: Job? = null
    private val mutableFileFailures = MutableStateFlow(0L)
    val fileFailures = mutableFileFailures.asStateFlow()
    private val mutableLastTick = MutableStateFlow<ClipboardReplicaTick?>(null)
    val lastTick = mutableLastTick.asStateFlow()

    @Synchronized
    fun start(scope: CoroutineScope): Job {
        job?.takeIf { it.isActive }?.let { return it }
        return scope
            .launch {
                coroutineScope {
                    repeat(2) {
                        launch {
                            while (isActive) {
                                val work = files.receive()
                                process(work)
                            }
                        }
                    }
                    while (isActive) {
                        try {
                            tick()
                        } catch (
                            e: CancellationException,
                        ) {
                            throw e
                        } catch (_: Exception) {
                            // Retry on next tick.
                        }
                        delay(1000)
                    }
                }
            }.also { job = it }
    }

    suspend fun stop() {
        val previous = synchronized(this) { job.also { job = null } }
        previous?.cancelAndJoin()
    }

    /** Also used by deterministic integration checks without launching background threads. */
    suspend fun processNextFile(): Boolean {
        val work = files.tryReceive().getOrNull() ?: return false
        process(work)
        return true
    }

    private suspend fun process(work: FileWork) {
        try {
            if (database.sharedClipboardDatabaseQueries
                    .getWorkspace(
                        work.key.clipboard,
                    ).executeAsOneOrNull()
                    ?.enabled !=
                true
            ) {
                return
            }
            if (work.signed.change.id.device == localDevice) {
                snapshot(work.signed, work.metadata, fileBudget())
            } else {
                var complete = false
                for (peer in work.peers) {
                    try {
                        receiveFiles(peer, work.signed, work.metadata, MAX_METADATA, fileBudget())
                        complete = true
                        break
                    } catch (
                        e: CancellationException,
                    ) {
                        throw e
                    } catch (_: Exception) {
                        // Try another approved replica.
                    }
                }
                if (!complete) {
                    mutableFileFailures.update { it + 1 }
                    return
                }
            }
            materialize(work.key.clipboard, 25)
        } catch (
            e: CancellationException,
        ) {
            throw e
        } catch (
            _: Exception,
        ) {
            mutableFileFailures.update { it + 1 }
        } finally {
            withContext(NonCancellable) { pendingMutex.withLock { pending.remove(work.key) } }
        }
    }

    suspend fun tick(limit: Int = 25): ClipboardReplicaTick =
        tickMutex.withLock {
            require(limit in 1..100)
            var captured = 0
            var pulled = 0
            var received = 0
            var queued = 0
            var created = 0
            var failures = 0
            val queries = database.sharedClipboardDatabaseQueries
            for (workspace in queries.getEnabledWorkspaces().executeAsList()) {
                val clipboard = workspace.clipboardId
                try {
                    captured += drain(clipboard, limit)
                    val approved =
                        queries
                            .getMembers(clipboard)
                            .executeAsList()
                            .map { it.deviceId }
                            .toSet()
                    val online = peers(clipboard).distinct().filter { it != localDevice && it in approved }.take(16)
                    for (peer in online) {
                        try {
                            pulled += pull(clipboard, peer, limit)
                        } catch (
                            e: CancellationException,
                        ) {
                            throw e
                        } catch (_: Exception) {
                            failures++
                        }
                    }
                    val signed = history.snapshot(clipboard)
                    val byId = signed.changes.associateBy { it.change.id }
                    val heads =
                        ClipboardJournal(
                            localDevice,
                            ClipboardJournalSnapshot(clipboard, signed.changes.map { it.change }),
                        ) {}
                            .allHeads()
                            .filterNot { it.deleted }
                            .sortedWith(compareBy({ it.id.device }, { it.id.sequence }))
                    val offset = if (heads.isEmpty()) 0 else (offsets[clipboard] ?: 0) % heads.size
                    val batch = (heads.drop(offset) + heads.take(offset)).take(limit)
                    offsets[clipboard] = if (heads.isEmpty()) 0 else (offset + batch.size) % heads.size
                    for (change in batch) {
                        if (queries.getWorkspace(clipboard).executeAsOneOrNull()?.enabled != true) break
                        if (queries
                                .getProjection(
                                    clipboard,
                                    change.id.device,
                                    change.id.sequence,
                                ).executeAsOneOrNull() !=
                            null
                        ) {
                            continue
                        }
                        val hash = requireNotNull(change.contentHash)
                        var payload = queries.getPayload(clipboard, hash).executeAsOneOrNull()
                        if (payload == null) {
                            for (peer in online.sortedBy { it != change.id.device }) {
                                try {
                                    metadata(peer, clipboard, hash, MAX_METADATA)
                                    payload = queries.getPayload(clipboard, hash).executeAsOneOrNull()
                                    if (payload != null) {
                                        received++
                                        break
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    failures++
                                }
                            }
                        }
                        val bytes = payload?.encodeToByteArray() ?: continue
                        val capture = Json.decodeFromString<CapturedClipboardPayload>(bytes.decodeToString())
                        val items =
                            listOf(PasteItem.fromStoredJson(capture.pasteAppearItem)) +
                                PasteCollection.fromStoredJson(capture.pasteCollection).pasteItems
                        if (items.none { it is PasteFiles }) continue
                        val key = FileKey(clipboard, change.id)
                        pendingMutex.withLock {
                            if (pending.add(key)) {
                                if (files
                                        .trySend(
                                            FileWork(
                                                key,
                                                byId.getValue(change.id),
                                                bytes,
                                                online.sortedBy { it != change.id.device },
                                            ),
                                        ).isSuccess
                                ) {
                                    queued++
                                } else {
                                    pending.remove(key)
                                }
                            }
                        }
                    }
                    created += materialize(clipboard, limit).created
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failures++
                }
            }
            ClipboardReplicaTick(captured, pulled, received, queued, created, failures).also {
                mutableLastTick.value =
                    it
            }
        }

    companion object {
        private const val MAX_METADATA = 8 * 1024 * 1024
    }
}
