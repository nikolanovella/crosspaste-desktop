package com.crosspaste.sync.journal

import com.crosspaste.Database
import com.crosspaste.utils.getCodecsUtils
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Consumes frozen capture metadata only; never reads or replaces the operating-system clipboard. */
class ClipboardCaptureConsumer(
    private val database: Database,
    private val clipboard: String,
    private val device: String,
    private val privateKey: ECDSA.PrivateKey,
    private val memberKey: suspend (String) -> ECDSA.PublicKey?,
) {
    private val mutex = Mutex()

    suspend fun drain(limit: Int = 50): Int =
        mutex.withLock {
            require(limit in 1..500)
            val queries = database.sharedClipboardDatabaseQueries
            if (queries.getWorkspace(clipboard).executeAsOneOrNull()?.enabled != true) return@withLock 0
            val captures = queries.getPendingCaptures(clipboard, limit.toLong()).executeAsList()
            if (captures.isEmpty()) return@withLock 0
            val commits =
                captures.map { capture ->
                    require(capture.originDevice == device) { "Cannot sign another device's capture" }
                    val payload = Json.encodeToString(CapturedClipboardPayload.from(capture))
                    val hashBytes =
                        CryptographyProvider.Default
                            .get(SHA256)
                            .hasher()
                            .hash(payload.encodeToByteArray())
                    val hash = "sha256:" + getCodecsUtils().base64Encode(hashBytes)
                    val entry = Json.encodeToString(listOf("capture/v1", device, capture.localPasteId.toString()))
                    val identity =
                        ClipboardNativeIdentityStore(
                            database,
                        ).captured(CapturedClipboardPayload.from(capture), hash)
                    ClipboardCaptureCommit(capture, entry, payload, hash, identity)
                }
            val store = SqlSignedClipboardStore(database, clipboard, commits)
            var staged = store.load()
            val journal = SignedClipboardJournal.open(device, privateKey, memberKey, staged) { staged = it }
            commits.forEach { commit ->
                val original =
                    staged.changes.firstOrNull {
                        it.change.entry == commit.entry && it.change.parents.isEmpty()
                    }
                if (original == null) {
                    journal.append(commit.entry, commit.hash)
                } else {
                    require(
                        original.change.contentHash == commit.hash &&
                            original.change.id.device == device &&
                            !original.change.deleted,
                    )
                }
            }
            store.save(staged)
            captures.size
        }
}
