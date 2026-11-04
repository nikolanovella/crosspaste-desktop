package com.crosspaste.workbench

import kotlinx.coroutines.ensureActive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.coroutineContext

/** One portable metadata-only implementation for Windows and macOS. */
object ClipboardFileTools {
    fun names(paths: List<Path>): String = paths.joinToString("\n") { it.fileName?.toString() ?: it.toString() }

    private data class TreeEntry(
        val path: Path,
        val directory: Boolean,
        val link: Boolean,
    )

    private data class Visit(
        val entry: TreeEntry,
        val prefix: String,
        val last: Boolean,
        val root: Boolean = false,
    )

    private fun name(path: Path): String =
        (path.fileName?.toString() ?: path.toString())
            .replace("\r", "\\r")
            .replace("\n", "\\n")

    private fun entry(path: Path): TreeEntry {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        return TreeEntry(path, attrs.isDirectory, attrs.isSymbolicLink)
    }

    suspend fun tree(
        paths: List<Path>,
        foldersOnly: Boolean,
        maxEntries: Int = 100_000,
    ): String {
        require(paths.isNotEmpty())
        var count = 0
        val output = StringBuilder()
        val seenDirectories = HashSet<Path>()
        val stack = ArrayDeque<Visit>()
        for (path in paths.asReversed()) {
            val root = entry(path)
            if (!foldersOnly || root.directory) stack.addLast(Visit(root, "", true, true))
        }
        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            check(++count <= maxEntries) { "Tree exceeds $maxEntries entries. Choose a smaller folder." }
            val visit = stack.removeLast()
            val item = visit.entry
            if (!visit.root) output.append(visit.prefix).append(if (visit.last) "└── " else "├── ")
            output.append(name(item.path))
            if (item.link) {
                output.append(" [link; not followed]\n")
                continue
            }
            output.append(if (item.directory) "/\n" else "\n")
            if (!item.directory) continue
            val children =
                try {
                    // Resolve directory identity once to stop Windows junction cycles too.
                    if (!seenDirectories.add(item.path.toRealPath())) {
                        output.append(visit.prefix).append("    [already listed; not followed]\n")
                        continue
                    }
                    Files.newDirectoryStream(item.path).use { stream ->
                        val result = ArrayList<TreeEntry>()
                        for (child in stream) {
                            coroutineContext.ensureActive()
                            val childEntry = entry(child)
                            if (foldersOnly &&
                                !childEntry.directory &&
                                !(childEntry.link && Files.isDirectory(child))
                            ) {
                                continue
                            }
                            check(
                                result.size < maxEntries - count,
                            ) { "Tree exceeds $maxEntries entries. Choose a smaller folder." }
                            result.add(childEntry)
                        }
                        result.sortedWith(
                            compareBy({ !it.directory }, { name(it.path).lowercase() }, { name(it.path) }),
                        )
                    }
                } catch (e: java.io.IOException) {
                    output.append(visit.prefix).append("    [unreadable]\n")
                    continue
                } catch (e: SecurityException) {
                    output.append(visit.prefix).append("    [unreadable]\n")
                    continue
                }
            val prefix = if (visit.root) "" else visit.prefix + if (visit.last) "    " else "│   "
            for (index in children.indices.reversed()) {
                stack.addLast(
                    Visit(
                        children[index],
                        prefix,
                        index == children.lastIndex,
                    ),
                )
            }
        }
        check(count > 0) { "Choose at least one folder." }
        val longest = Regex("`+").findAll(output).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longest + 1))
        return fence + "text\n" + output + fence + "\n"
    }
}
