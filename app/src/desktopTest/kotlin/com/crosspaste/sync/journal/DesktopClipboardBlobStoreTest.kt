package com.crosspaste.sync.journal

import com.crosspaste.test.IntegrationTest
import com.crosspaste.utils.getCodecsUtils
import com.crosspaste.utils.getFileUtils
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@IntegrationTest
class DesktopClipboardBlobStoreTest {
    private fun captureHash(path: Path) = getFileUtils().getFileHash(path.toString().toPath())

    private fun cleanup(root: Path) {
        val temporaryRoot = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        require(root.toAbsolutePath().normalize().startsWith(temporaryRoot))
        require(root.fileName.toString().startsWith("clipboard-blob-test-"))
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    @Test
    fun `large snapshot streams into bounded chunks and does not follow later source edits`() =
        runTest {
            val root = Files.createTempDirectory("clipboard-blob-test-")
            try {
                val bytes = ByteArray(DesktopClipboardBlobStore.CHUNK_BYTES * 2 + 37) { (it % 251).toByte() }
                val source = Files.write(root.resolve("source.bin"), bytes)
                val store = DesktopClipboardBlobStore(root.resolve("store"))
                val descriptor = store.snapshot(source, bytes.size.toLong(), captureHash(source), bytes.size.toLong())
                Files.writeString(source, "original changed")
                val output = ByteArrayOutputStream()
                var offset = 0L
                while (offset < descriptor.size) {
                    val chunk = store.readChunk(descriptor.sha256, offset)
                    assertEquals(descriptor.size, chunk.totalBytes)
                    assertEquals(offset, chunk.offset)
                    val data = getCodecsUtils().base64Decode(chunk.bytes)
                    assertTrue(data.size <= DesktopClipboardBlobStore.CHUNK_BYTES)
                    output.write(data)
                    offset += data.size
                }
                assertContentEquals(bytes, output.toByteArray())
                assertTrue(store.has(descriptor))
            } finally {
                cleanup(root)
            }
        }

    @Test
    fun `changed capture hash and over budget copies never publish a blob`() =
        runTest {
            val root = Files.createTempDirectory("clipboard-blob-test-")
            try {
                val source = Files.writeString(root.resolve("source.txt"), "original")
                val expected = captureHash(source)
                val store = DesktopClipboardBlobStore(root.resolve("store"))
                assertFailsWith<IllegalArgumentException> { store.snapshot(source, 8, expected, 7) }
                assertFalse(Files.exists(root.resolve("store")))
                Files.writeString(source, "modified")
                assertFailsWith<IllegalArgumentException> { store.snapshot(source, 8, expected, 8) }
                assertFalse(Files.exists(root.resolve("store/blobs")))
                Files.list(root.resolve("store/staging")).use { assertEquals(0L, it.count()) }
                assertEquals("modified", Files.readString(source))
            } finally {
                cleanup(root)
            }
        }

    @Test
    fun `identical blobs deduplicate and corrupted cached bytes can be repaired`() =
        runTest {
            val root = Files.createTempDirectory("clipboard-blob-test-")
            try {
                val source = Files.writeString(root.resolve("source.txt"), "unchanged")
                val store = DesktopClipboardBlobStore(root.resolve("store"))
                val first = store.snapshot(source, 9, captureHash(source), 9)
                assertEquals(first, store.snapshot(source, 9, captureHash(source), 9))
                val stored = root.resolve("store/blobs").resolve(first.sha256.take(2)).resolve(first.sha256)
                Files.writeString(stored, "corrupted")
                assertFalse(store.has(first))
                assertEquals(first, store.snapshot(source, 9, captureHash(source), 9))
                assertTrue(store.has(first))
                assertEquals("unchanged", Files.readString(source))
                Files.list(stored.parent).use { assertEquals(1L, it.count()) }
            } finally {
                cleanup(root)
            }
        }

    @Test
    fun `empty files and range boundaries are explicit and digest paths cannot escape`() =
        runTest {
            val root = Files.createTempDirectory("clipboard-blob-test-")
            try {
                val source = Files.createFile(root.resolve("empty"))
                val store = DesktopClipboardBlobStore(root.resolve("store"))
                val blob = store.snapshot(source, 0, captureHash(source), 0)
                assertTrue(store.has(blob))
                assertEquals(0L, store.readChunk(blob.sha256, 0).totalBytes)
                assertEquals("", store.readChunk(blob.sha256, 0).bytes)
                assertFailsWith<IllegalArgumentException> { store.readChunk("../escape", 0) }
                assertFailsWith<IllegalArgumentException> { store.readChunk(blob.sha256, 1) }
                assertFailsWith<IllegalArgumentException> { store.readChunk(blob.sha256, -1) }
                assertFailsWith<IllegalArgumentException> { store.readChunk(blob.sha256, 0, 0) }
            } finally {
                cleanup(root)
            }
        }

    @Test
    fun `restart keeps verified bytes after original moves away`() =
        runTest {
            val root = Files.createTempDirectory("clipboard-blob-test-")
            try {
                val source = Files.writeString(root.resolve("source.txt"), "retained")
                val store = DesktopClipboardBlobStore(root.resolve("store"))
                val blob = store.snapshot(source, 8, captureHash(source), 8)
                Files.move(source, root.resolve("moved.txt"))
                val restarted = DesktopClipboardBlobStore(root.resolve("store"))
                assertTrue(restarted.has(blob))
                val chunk = restarted.readChunk(blob.sha256, 0)
                assertEquals("retained", getCodecsUtils().base64Decode(chunk.bytes).decodeToString())
            } finally {
                cleanup(root)
            }
        }
}
