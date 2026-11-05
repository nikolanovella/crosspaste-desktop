package com.crosspaste.sync.journal

import com.crosspaste.paste.PasteCollection
import com.crosspaste.paste.item.PasteFiles
import com.crosspaste.paste.item.PasteItem
import com.crosspaste.paste.item.getFilePaths
import com.crosspaste.path.UserDataPathProvider
import com.crosspaste.secure.SecureKeyPairSerializer
import com.crosspaste.secure.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal fun capturedClipboardFileRoot(
    capture: CapturedClipboardPayload,
    item: Int,
    root: Int,
    paths: UserDataPathProvider,
): Path {
    val primary = requireNotNull(PasteItem.fromStoredJson(capture.pasteAppearItem))
    val items = listOf(primary) + PasteCollection.fromStoredJson(capture.pasteCollection).pasteItems
    val files = requireNotNull(items.getOrNull(item) as? PasteFiles)
    return files.getFilePaths(paths).getOrNull(root)?.toNioPath() ?: error("Captured root is unavailable")
}

/** Only original local captures can cause local files to be read. Relayed paths never reach the resolver. */
internal class DesktopClipboardFileSnapshotter(
    private val localDevice: String,
    private val secureStore: SecureStore,
    private val verifier: ClipboardFileManifestVerifier,
    private val blobs: DesktopClipboardBlobStore,
    private val rootSource: (CapturedClipboardPayload, Int, Int) -> Path,
) {
    suspend fun snapshot(
        event: SignedClipboardChange,
        metadata: ByteArray,
        maximumBytes: Long,
    ): SignedClipboardFileManifest =
        withContext(Dispatchers.IO) {
            val verified = verifier.verifyCapture(event, metadata)
            require(
                verified.capture.originDevice == localDevice,
            ) { "Cannot snapshot another device's filesystem paths" }
            val serializer = SecureKeyPairSerializer()
            require(
                serializer.encodeSignPublicKey(verified.signingKey).contentEquals(
                    serializer.encodeSignPublicKey(secureStore.secureKeyPair.signKeyPair.publicKey),
                ),
            ) { "Local signing identity differs from the original capture" }
            require(maximumBytes >= 0)
            var total = 0L
            verified.slots.filterNot { it.directory }.forEach {
                require(it.size <= maximumBytes - total) { "Captured files exceed the configured snapshot budget" }
                total += it.size
            }
            val paths = mutableMapOf<Int, Path>()
            val roots = mutableMapOf<Pair<Int, Int>, Path>()
            val nodes = mutableListOf<ClipboardFileNode>()
            for (slot in verified.slots) {
                val rootKey = slot.item to slot.root
                val source =
                    if (slot.parent == null) {
                        rootSource(verified.capture, slot.item, slot.root).toAbsolutePath().normalize().also {
                            require(
                                it.fileName.toString() == slot.name,
                            ) { "Captured root name does not match its local source" }
                            roots[rootKey] = it
                        }
                    } else {
                        val name = Path.of(slot.name)
                        require(!name.isAbsolute && name.nameCount == 1)
                        paths.getValue(slot.parent).resolve(name).normalize().also {
                            require(it.startsWith(roots.getValue(rootKey))) { "Captured path escaped its root" }
                        }
                    }
                paths[slot.id] = source
                val blob =
                    if (slot.directory) {
                        require(Files.isDirectory(source, NOFOLLOW_LINKS)) { "Captured directory is unavailable" }
                        null
                    } else {
                        blobs.snapshot(source, slot.size, slot.captureHash, maximumBytes)
                    }
                nodes.add(ClipboardFileNode(slot, blob))
            }
            verifier.sign(
                ClipboardFileManifest(
                    event.change.clipboard,
                    event.change.id,
                    requireNotNull(event.change.contentHash),
                    nodes,
                ),
                event,
                metadata,
                secureStore.secureKeyPair.signKeyPair.privateKey,
            )
        }
}
