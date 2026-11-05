package com.crosspaste.sync.journal

import com.crosspaste.paste.PasteCollection
import com.crosspaste.paste.item.PasteFiles
import com.crosspaste.paste.item.PasteItem
import com.crosspaste.presist.DirFileInfoTree
import com.crosspaste.presist.FileInfoTree
import com.crosspaste.presist.FileTransferResourceLimits
import com.crosspaste.presist.SingleFileInfoTree
import com.crosspaste.utils.CryptographyUtils
import com.crosspaste.utils.getCodecsUtils
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

/** Logical names only. Receivers must never resolve these directly as local filesystem paths. */
@Serializable
data class ClipboardFileSlot(
    val id: Int,
    val parent: Int?,
    val item: Int,
    val root: Int,
    val name: String,
    val directory: Boolean,
    val size: Long,
    val captureHash: String,
)

@Serializable
data class ClipboardFileNode(
    val slot: ClipboardFileSlot,
    val blob: ClipboardBlob? = null,
)

@Serializable
data class ClipboardFileManifest(
    val clipboard: String,
    val event: ClipboardEventId,
    val metadataHash: String,
    val nodes: List<ClipboardFileNode>,
    val version: Int = 1,
)

@Serializable
data class SignedClipboardFileManifest(
    val manifest: ClipboardFileManifest,
    val signature: String,
)

class VerifiedClipboardFileManifest internal constructor(
    val manifest: ClipboardFileManifest,
)

class VerifiedClipboardFileCapture internal constructor(
    val event: SignedClipboardChange,
    val capture: CapturedClipboardPayload,
    val slots: List<ClipboardFileSlot>,
    internal val signingKey: ECDSA.PublicKey,
)

/** A flat, deterministic tree avoids repeating every ancestor name for every file. */
object ClipboardFileLayout {
    fun nodes(capture: CapturedClipboardPayload): List<ClipboardFileSlot> {
        val primary = requireNotNull(PasteItem.fromStoredJson(capture.pasteAppearItem))
        val items = listOf(primary) + PasteCollection.fromStoredJson(capture.pasteCollection).pasteItems
        val nodes = mutableListOf<ClipboardFileSlot>()
        var totalSize = 0L

        fun add(
            current: Long,
            amount: Long,
        ): Long {
            require(amount >= 0 && amount <= FileTransferResourceLimits.MAX_TOTAL_SIZE - current)
            return current + amount
        }

        fun visit(
            tree: FileInfoTree,
            name: String,
            parent: Int?,
            item: Int,
            root: Int,
            depth: Int,
        ): Pair<Long, Long> {
            require(depth <= FileTransferResourceLimits.MAX_TREE_DEPTH)
            require(nodes.size < FileTransferResourceLimits.MAX_TREE_NODES)
            require(
                name.isNotEmpty() &&
                    name.length <= 4096 &&
                    name != "." &&
                    name != ".." &&
                    '/' !in name &&
                    '\u0000' !in name,
            )
            require(tree.size >= 0 && Regex("[0-9a-f]{32}").matches(tree.hash))
            val id = nodes.size
            nodes.add(ClipboardFileSlot(id, parent, item, root, name, tree is DirFileInfoTree, tree.size, tree.hash))
            return when (tree) {
                is SingleFileInfoTree -> {
                    require(tree.size <= FileTransferResourceLimits.MAX_SINGLE_FILE_SIZE)
                    tree.size to 1L
                }
                is DirFileInfoTree -> {
                    var bytes = 0L
                    var count = 0L
                    tree.iterator().forEach { (childName, child) ->
                        val stats = visit(child, childName, id, item, root, depth + 1)
                        bytes = add(bytes, stats.first)
                        count += stats.second
                    }
                    require(bytes == tree.size)
                    bytes to count
                }
            }
        }
        items.forEachIndexed { itemIndex, item ->
            if (item is PasteFiles) {
                val names = item.relativePathList.map { it.toPath().name }
                require(names.size == names.toSet().size && names.toSet() == item.fileInfoTreeMap.keys)
                var bytes = 0L
                var count = 0L
                names.forEachIndexed { rootIndex, name ->
                    val stats = visit(item.fileInfoTreeMap.getValue(name), name, null, itemIndex, rootIndex, 1)
                    bytes = add(bytes, stats.first)
                    count += stats.second
                }
                require(bytes == item.size && count == item.count)
                totalSize = add(totalSize, bytes)
            }
        }
        require(nodes.isNotEmpty()) { "Capture has no file representations" }
        return nodes.toList()
    }
}

/** Manifests reuse approved original-author keys. Relay transport never grants signing authority. */
class ClipboardFileManifestVerifier(
    private val memberKey: suspend (String, String) -> ECDSA.PublicKey?,
) {
    private val json = Json { encodeDefaults = true }
    private val codecs = getCodecsUtils()

    suspend fun verifyCapture(
        event: SignedClipboardChange,
        metadata: ByteArray,
    ): VerifiedClipboardFileCapture {
        val change = event.change
        require(!change.deleted && change.parents.isEmpty() && change.id.sequence > 0 && change.clock > 0)
        require(change.clipboard.isNotBlank() && change.clipboard.length <= 256 && change.id.device.isNotBlank())
        val key = requireNotNull(memberKey(change.clipboard, change.id.device)) { "Unknown clipboard author" }
        require(event.signature.length in 1..256)
        require(authenticated(key, event.signature) { SignedClipboardJournal.signingBytes(change) }) {
            "Invalid original clipboard event signature"
        }
        val hash =
            "sha256:" +
                codecs.base64Encode(
                    CryptographyProvider.Default
                        .get(SHA256)
                        .hasher()
                        .hash(metadata),
                )
        require(hash == change.contentHash) { "File metadata does not match its signed capture" }
        val capture =
            Json.decodeFromString<CapturedClipboardPayload>(
                metadata.decodeToString(throwOnInvalidSequence = true),
            )
        require(capture.originDevice == change.id.device && capture.localPasteId > 0)
        require(
            change.entry ==
                Json.encodeToString(listOf("capture/v1", capture.originDevice, capture.localPasteId.toString())),
        )
        return VerifiedClipboardFileCapture(
            event.copy(change = change.copy(parents = change.parents.toSet())),
            capture,
            ClipboardFileLayout.nodes(capture),
            key,
        )
    }

    suspend fun sign(
        manifest: ClipboardFileManifest,
        event: SignedClipboardChange,
        metadata: ByteArray,
        privateKey: ECDSA.PrivateKey,
    ): SignedClipboardFileManifest {
        binding(manifest, event, metadata)
        val stable = manifest.copy(nodes = manifest.nodes.map { it.copy(slot = it.slot.copy()) })
        val signature = CryptographyUtils.signData(privateKey) { signingBytes(stable) }
        val signed = SignedClipboardFileManifest(stable, codecs.base64Encode(signature))
        verify(signed, event, metadata)
        return signed
    }

    suspend fun verify(
        signed: SignedClipboardFileManifest,
        event: SignedClipboardChange,
        metadata: ByteArray,
    ): VerifiedClipboardFileManifest {
        val stable = signed.manifest.copy(nodes = signed.manifest.nodes.map { it.copy(slot = it.slot.copy()) })
        val key = binding(stable, event, metadata)
        require(signed.signature.length in 1..256)
        require(authenticated(key, signed.signature) { signingBytes(stable) }) {
            "Invalid original-author file manifest signature"
        }
        return VerifiedClipboardFileManifest(stable)
    }

    private suspend fun binding(
        manifest: ClipboardFileManifest,
        event: SignedClipboardChange,
        metadata: ByteArray,
    ): ECDSA.PublicKey {
        val change = event.change
        require(manifest.version == 1 && manifest.clipboard == change.clipboard && manifest.event == change.id)
        require(manifest.metadataHash == change.contentHash)
        val capture = verifyCapture(event, metadata)
        require(
            manifest.nodes.map { it.slot } == capture.slots,
        ) { "Manifest file tree differs from its captured metadata" }
        manifest.nodes.forEach { node ->
            if (node.slot.directory) {
                require(node.blob == null)
            } else {
                val blob = requireNotNull(node.blob)
                require(Regex("[0-9a-f]{64}").matches(blob.sha256))
                require(blob.size == node.slot.size && blob.captureHash == node.slot.captureHash)
            }
        }
        return requireNotNull(memberKey(change.clipboard, change.id.device))
    }

    private fun signingBytes(manifest: ClipboardFileManifest): ByteArray =
        ("CrossPaste/shared-clipboard/file-manifest/v1\n" + json.encodeToString(manifest)).encodeToByteArray()

    private suspend fun authenticated(
        key: ECDSA.PublicKey,
        signature: String,
        bytes: () -> ByteArray,
    ): Boolean =
        try {
            CryptographyUtils.verifyData(key, codecs.base64Decode(signature), bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
}
