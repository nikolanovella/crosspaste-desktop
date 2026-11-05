package com.crosspaste.sync.journal

import com.crosspaste.paste.PasteCollection
import com.crosspaste.paste.PasteData
import com.crosspaste.paste.item.FilesPasteItem
import com.crosspaste.paste.item.ImagesPasteItem
import com.crosspaste.paste.item.PasteFiles
import com.crosspaste.path.encodeStoredPath
import com.crosspaste.presist.DirFileInfoTree
import com.crosspaste.presist.FileInfoTree
import com.crosspaste.presist.SingleFileInfoTree
import com.crosspaste.utils.getCodecsUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.text.Normalizer
import java.util.HexFormat
import java.util.Locale

internal fun clipboardLandingName(name: String): String {
    var safe =
        Normalizer
            .normalize(name, Normalizer.Form.NFC)
            .map {
                if (it.code < 32 || it in "<>:\"/\\|?*") '_' else it
            }.joinToString("")
            .trimEnd('.', ' ')
    if (safe.isEmpty() || safe == "." || safe == "..") safe = "item"
    val stem = safe.substringBefore('.').uppercase(Locale.ROOT)
    if (stem in setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") ||
        Regex("(?:COM|LPT)[1-9¹²³]").matches(stem)
    ) {
        safe = "_$safe"
    }
    val result = StringBuilder()
    for (point in safe.codePoints().toArray()) {
        val next = String(Character.toChars(point))
        if ((result.toString() + next).toByteArray(Charsets.UTF_8).size > 180) break
        result.append(next)
    }
    return result.toString()
}

/** Atomically lands verified bytes in a private per-event tree, never in sender-provided paths. */
internal class DesktopClipboardFileLanding(
    root: Path,
    private val manifests: DesktopClipboardFileManifestStore,
    private val verifier: ClipboardFileManifestVerifier,
    private val blobs: DesktopClipboardBlobStore,
) {
    private val root = root.toAbsolutePath().normalize()
    private val mutex = Mutex()

    suspend fun land(
        event: SignedClipboardChange,
        metadata: ByteArray,
        paste: PasteData,
    ): PasteData? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val signed = manifests.load(event, metadata) ?: return@withLock null
                val manifest = verifier.verify(signed, event, metadata).manifest
                if (!manifest.nodes.mapNotNull { it.blob }.all { blobs.has(it) }) return@withLock null
                val key =
                    Json.encodeToString(
                        listOf(
                            manifest.clipboard,
                            manifest.event.device,
                            manifest.event.sequence.toString(),
                            manifest.metadataHash,
                        ),
                    )
                val hash =
                    HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)),
                    )
                val target = root.resolve(hash)
                val names = mutableMapOf<Int, String>()
                val paths = mutableMapOf<Int, Path>()
                val used = mutableMapOf<Pair<Int, Int?>, MutableSet<String>>()
                for (node in manifest.nodes) {
                    val slot = node.slot
                    val siblings = used.getOrPut(slot.item to slot.parent) { mutableSetOf() }
                    val base = clipboardLandingName(slot.name)
                    var name = base
                    var attempt = 0

                    fun folded(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD).uppercase(Locale.ROOT)
                    while (!siblings.add(folded(name))) {
                        attempt++
                        name = "$base-${slot.id}-$attempt"
                    }
                    names[slot.id] = name
                    val parent = slot.parent?.let { paths.getValue(it) } ?: Path.of("item-${slot.item}")
                    paths[slot.id] = parent.resolve(name)
                }

                fun resolved(
                    base: Path,
                    node: ClipboardFileNode,
                ): Path =
                    base.resolve(paths.getValue(node.slot.id)).normalize().also {
                        require(it.startsWith(base)) { "Landing path escaped private storage" }
                    }

                suspend fun verifyTree(base: Path) {
                    require(Files.isDirectory(base, NOFOLLOW_LINKS))
                    val expected = mutableSetOf(base)
                    for (node in manifest.nodes) {
                        var current = resolved(base, node)
                        while (current != base) {
                            expected.add(current)
                            current = current.parent
                        }
                    }
                    val actual = Files.walk(base).use { entries -> entries.toList().toSet() }
                    require(actual == expected && actual.none { Files.isSymbolicLink(it) }) {
                        "Landed tree contains unexpected paths or links"
                    }
                    for (node in manifest.nodes) {
                        val path = resolved(base, node)
                        if (node.slot.directory) {
                            require(Files.isDirectory(path, NOFOLLOW_LINKS))
                        } else {
                            require(blobs.matches(path, requireNotNull(node.blob))) { "Landed clipboard file changed" }
                        }
                    }
                }
                if (!Files.exists(target, NOFOLLOW_LINKS)) {
                    Files.createDirectories(root)
                    val temporary = Files.createTempDirectory(root, ".landing-")
                    try {
                        for (node in manifest.nodes) {
                            val path = resolved(temporary, node)
                            Files.createDirectories(path.parent)
                            if (node.slot.directory) {
                                Files.createDirectory(path)
                            } else {
                                blobs.copyVerified(requireNotNull(node.blob), path)
                            }
                        }
                        verifyTree(temporary)
                        Files.move(temporary, target, ATOMIC_MOVE)
                    } finally {
                        if (Files.exists(temporary, NOFOLLOW_LINKS)) {
                            require(temporary.parent == root && temporary.fileName.toString().startsWith(".landing-"))
                            Files.walk(temporary).use { entries ->
                                entries.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                            }
                        }
                    }
                } else {
                    verifyTree(target)
                }
                val children = manifest.nodes.groupBy { it.slot.parent }

                fun tree(node: ClipboardFileNode): FileInfoTree =
                    if (!node.slot.directory) {
                        SingleFileInfoTree(node.slot.size, node.slot.captureHash)
                    } else {
                        val entries =
                            children[node.slot.id].orEmpty().associate {
                                names.getValue(it.slot.id) to
                                    tree(it)
                            }
                        DirFileInfoTree(
                            entries,
                            node.slot.size,
                            if (entries.isEmpty()) {
                                getCodecsUtils().hashByString(names.getValue(node.slot.id))
                            } else {
                                getCodecsUtils().hashByArray(
                                    entries
                                        .toSortedMap()
                                        .values
                                        .map { it.hash }
                                        .toTypedArray(),
                                )
                            },
                        )
                    }
                val items = listOf(requireNotNull(paste.pasteAppearItem)) + paste.pasteCollection.pasteItems
                val local =
                    items.mapIndexed { index, item ->
                        if (item !is PasteFiles) {
                            item
                        } else {
                            val roots =
                                manifest.nodes
                                    .filter {
                                        it.slot.item == index && it.slot.parent == null
                                    }.sortedBy { it.slot.root }
                            val relative = roots.map { names.getValue(it.slot.id) }
                            val info = roots.associate { names.getValue(it.slot.id) to tree(it) }
                            val base = encodeStoredPath(target.resolve("item-$index").toString())
                            when (item) {
                                is FilesPasteItem ->
                                    item.copy(
                                        basePath = base,
                                        relativePathList = relative,
                                        fileInfoTreeMap = info,
                                    )
                                is ImagesPasteItem ->
                                    item.copy(
                                        basePath = base,
                                        relativePathList = relative,
                                        fileInfoTreeMap = info,
                                    )
                                else -> error("Unsupported file representation")
                            }
                        }
                    }
                paste.copy(pasteAppearItem = local.first(), pasteCollection = PasteCollection(local.drop(1)))
            }
        }
}
