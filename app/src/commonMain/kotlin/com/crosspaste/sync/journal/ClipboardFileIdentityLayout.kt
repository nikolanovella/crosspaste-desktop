package com.crosspaste.sync.journal

import com.crosspaste.paste.PasteData
import com.crosspaste.paste.item.PasteFiles
import com.crosspaste.paste.item.PasteItem
import com.crosspaste.presist.DirFileInfoTree
import com.crosspaste.presist.FileInfoTree
import com.crosspaste.presist.SingleFileInfoTree
import com.crosspaste.presist.validateFileTransferMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okio.Path.Companion.toPath

/** Portable shape of the legacy metadata, never a claim that a local file is available. */
internal object ClipboardFileIdentityLayout {
    private val hashPattern = Regex("[0-9a-f]{32}")

    fun descriptor(paste: PasteData): JsonObject? =
        try {
            validateFileTransferMetadata(paste, allowZeroByteContent = true)
            val primary = requireNotNull(paste.pasteAppearItem)
            val fields =
                mutableMapOf(
                    "primary" to descriptor(primary),
                    "collection" to JsonArray(paste.pasteCollection.pasteItems.map(::descriptor)),
                    "type" to JsonPrimitive(paste.pasteType),
                    "source" to (paste.source?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull),
                    "size" to JsonPrimitive(paste.size),
                )
            if (primary !is PasteFiles) fields["hash"] = JsonPrimitive(paste.hash)
            JsonObject(fields)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun descriptor(item: PasteItem): JsonObject {
        val stored = Json.parseToJsonElement(item.toStoredJson()).jsonObject
        if (item !is PasteFiles) return stored
        val roots =
            item.relativePathList.map { relative ->
                tree(item.fileInfoTreeMap.getValue(relative.toPath().name))
            }
        // The receive side may change the root's name to avoid overwriting an existing
        // file. The signed frozen metadata still binds the original names separately.
        return JsonObject(
            (stored - setOf("basePath", "relativePathList", "fileInfoTreeMap", "hash")) + ("roots" to JsonArray(roots)),
        )
    }

    private fun tree(tree: FileInfoTree): JsonObject =
        when (tree) {
            is SingleFileInfoTree -> {
                require(hashPattern.matches(tree.hash))
                JsonObject(mapOf("size" to JsonPrimitive(tree.size), "hash" to JsonPrimitive(tree.hash)))
            }
            is DirFileInfoTree ->
                JsonObject(
                    mapOf(
                        "size" to JsonPrimitive(tree.size),
                        "children" to
                            JsonObject(tree.iterator().asSequence().associate { (name, child) -> name to tree(child) }),
                    ),
                )
        }
}
