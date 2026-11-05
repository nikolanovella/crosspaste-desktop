package com.crosspaste.sync.journal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** Remote filenames never become paths here; only verified content enters private stores. */
internal class DesktopClipboardFileReceiver(
    private val transfer: ClipboardFileTransfer,
    private val verifier: ClipboardFileManifestVerifier,
    private val manifests: DesktopClipboardFileManifestStore,
    private val blobs: DesktopClipboardBlobStore,
    private val history: ClipboardHistoryService,
    staging: Path,
) {
    private val staging = staging.toAbsolutePath().normalize()
    private val mutex = Mutex()

    suspend fun receive(
        peer: String,
        event: SignedClipboardChange,
        metadata: ByteArray,
        maximumManifestBytes: Int,
        maximumFileBytes: Long,
    ): SignedClipboardFileManifest =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(maximumFileBytes >= 0 && maximumManifestBytes in 1..32 * 1024 * 1024)

                suspend fun authorize() {
                    val context = history.receivingFileContext(peer, event.change.clipboard, event.change.id)
                    require(context.event == event && context.metadata.contentEquals(metadata)) {
                        "Incoming file does not match accepted local clipboard history"
                    }
                }
                authorize()
                verifier.verifyCapture(event, metadata)
                val cached = manifests.load(event, metadata)
                val signed = cached ?: transfer.manifest(peer, event, metadata, maximumManifestBytes)
                require(Json.encodeToString(signed).encodeToByteArray().size <= maximumManifestBytes)
                val verified = verifier.verify(signed, event, metadata)
                var total = 0L
                val descriptors = verified.manifest.nodes.mapNotNull { it.blob }
                descriptors.forEach {
                    require(it.size <= maximumFileBytes - total) { "Files exceed the configured receiving budget" }
                    total += it.size
                }
                for (descriptor in descriptors.distinct()) {
                    if (blobs.has(descriptor)) continue
                    Files.createDirectories(staging)
                    val temporary = Files.createTempFile(staging, "clipboard-file-", ".part")
                    try {
                        Files.newOutputStream(temporary).use { output ->
                            transfer.blob(peer, verified, descriptor, maximumFileBytes) { output.write(it) }
                        }
                        blobs.importVerified(temporary, descriptor, maximumFileBytes)
                    } finally {
                        Files.deleteIfExists(temporary)
                    }
                }
                authorize()
                manifests.save(signed, event, metadata)
            }
        }
}
