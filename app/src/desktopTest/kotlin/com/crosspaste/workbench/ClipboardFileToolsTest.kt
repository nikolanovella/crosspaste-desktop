package com.crosspaste.workbench

import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipboardFileToolsTest {
    @TempDir lateinit var folder: Path

    @Test
    fun `selected names and both tree modes use metadata not file contents`() =
        runTest {
            val child = Files.createDirectories(folder.resolve("nested/deep"))
            val file = folder.resolve("filename.txt")
            Files.writeString(file, "file contents must never appear in a tree")
            Files.writeString(child.resolve("inside.txt"), "payload")
            assertEquals("filename.txt\nnested", ClipboardFileTools.names(listOf(file, folder.resolve("nested"))))
            val full = ClipboardFileTools.tree(listOf(folder), false)
            val folders = ClipboardFileTools.tree(listOf(folder), true)
            assertTrue(full.contains("inside.txt"))
            assertTrue(full.contains("filename.txt"))
            assertTrue(folders.contains("deep/"))
            assertFalse(folders.contains(".txt"))
            assertFalse(full.contains("file contents must"))
        }

    @Test
    fun `large exports fail before producing partial clipboard text`() =
        runTest {
            repeat(10) { Files.createFile(folder.resolve("$it.txt")) }
            assertFailsWith<IllegalStateException> { ClipboardFileTools.tree(listOf(folder), false, maxEntries = 3) }
        }

    @Test
    fun `cancellation aborts tree building`() =
        runTest {
            val job =
                launch {
                    cancel()
                    ClipboardFileTools.tree(listOf(folder), false)
                }
            job.join()
            assertTrue(job.isCancelled)
        }

    @Test
    fun `markdown fence cannot be closed by a filename`() =
        runTest {
            Files.createFile(folder.resolve("```odd.txt"))
            val tree = ClipboardFileTools.tree(listOf(folder), false)
            assertTrue(tree.startsWith("````text\n"))
            assertTrue(tree.endsWith("````\n"))
        }
}
