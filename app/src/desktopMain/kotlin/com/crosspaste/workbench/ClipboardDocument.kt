package com.crosspaste.workbench

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ClipboardOriginal(
    val id: String,
    val text: String,
    val source: String,
)

@Serializable
data class ClipboardBlock(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val originalId: String,
    val groupId: String? = null,
)

/** Originals are immutable; edits and merges only change the derived blocks. */
@Serializable
data class ClipboardDocument(
    val originals: List<ClipboardOriginal> = emptyList(),
    val blocks: List<ClipboardBlock> = emptyList(),
    val separator: String = "\n\n",
) {
    fun append(
        text: String,
        source: String,
    ): ClipboardDocument {
        val original = ClipboardOriginal(UUID.randomUUID().toString(), text, source)
        return copy(
            originals = originals + original,
            blocks =
                blocks + ClipboardBlock(text = text, originalId = original.id),
        )
    }

    fun edit(
        id: String,
        text: String,
    ): ClipboardDocument =
        copy(
            blocks =
                blocks.map {
                    if (it.id ==
                        id
                    ) {
                        it.copy(text = text)
                    } else {
                        it
                    }
                },
        )

    fun grouped(ids: Set<String>): ClipboardDocument {
        val group = UUID.randomUUID().toString()
        return copy(blocks = blocks.map { if (it.id in ids) it.copy(groupId = group) else it })
    }

    fun ungroup(ids: Set<String>): ClipboardDocument =
        copy(
            blocks =
                blocks.map {
                    if (it.id in
                        ids
                    ) {
                        it.copy(groupId = null)
                    } else {
                        it
                    }
                },
        )

    /** Moving any grouped member moves that whole group, preserving its order. */
    fun move(
        ids: Set<String>,
        beforeId: String?,
    ): ClipboardDocument {
        val groups = blocks.filter { it.id in ids }.mapNotNull { it.groupId }.toSet()
        val moving = blocks.filter { it.id in ids || (it.groupId != null && it.groupId in groups) }
        if (moving.any { it.id == beforeId }) return this
        val remaining = blocks - moving.toSet()
        val targetGroup = remaining.firstOrNull { it.id == beforeId }?.groupId
        val index =
            remaining
                .indexOfFirst { if (targetGroup == null) it.id == beforeId else it.groupId == targetGroup }
                .takeIf { it >= 0 } ?: remaining.size
        return copy(blocks = remaining.take(index) + moving + remaining.drop(index))
    }

    fun render(): String = blocks.joinToString(separator) { it.text }
}
