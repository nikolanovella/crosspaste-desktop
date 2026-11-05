package com.crosspaste.sync.journal

import com.crosspaste.test.IntegrationTest
import com.crosspaste.utils.getCodecsUtils
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@IntegrationTest
class DesktopClipboardFileSnapshotterTest {
    private fun cleanup(root: Path) {
        require(
            root.toAbsolutePath().normalize().startsWith(
                Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize(),
            ),
        )
        require(root.fileName.toString().startsWith("clipboard-manifest-test-"))
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    @Test
    fun `actual nested files and alternate images produce an original signed manifest`() =
        runTest {
            val f = manifestFixture()
            val root = Files.createTempDirectory("clipboard-manifest-test-")
            try {
                val photos = Files.createDirectories(root.resolve("photos"))
                Files.createDirectory(photos.resolve("empty"))
                Files.writeString(photos.resolve("café.txt"), "hello")
                Files.writeString(root.resolve("thumb.png"), "png")
                val blobs = DesktopClipboardBlobStore(root.resolve("store"))
                val snapshotter =
                    DesktopClipboardFileSnapshotter("a", f.peers.aStore, f.verifier, blobs) { _, item, index ->
                        assertEquals(0, index)
                        if (item == 0) photos else root.resolve("thumb.png")
                    }
                val signed = snapshotter.snapshot(f.event, f.metadata, 8)
                assertEquals(f.manifest, f.verifier.verify(signed, f.event, f.metadata).manifest)
                signed.manifest.nodes
                    .mapNotNull { it.blob }
                    .forEach { assertTrue(blobs.has(it)) }
                val first =
                    signed.manifest.nodes
                        .first { it.slot.name == "café.txt" }
                        .blob!!
                assertEquals(
                    "hello",
                    getCodecsUtils().base64Decode(blobs.readChunk(first.sha256, 0).bytes).decodeToString(),
                )
                Files.writeString(photos.resolve("café.txt"), "HELLO")
                assertFailsWith<IllegalArgumentException> { snapshotter.snapshot(f.event, f.metadata, 8) }
                assertTrue(blobs.has(first))
            } finally {
                cleanup(root)
            }
        }

    @Test
    fun `foreign and forged captures cannot cause any local path lookup`() =
        runTest {
            val f = manifestFixture()
            val root = Files.createTempDirectory("clipboard-manifest-test-")
            try {
                var lookups = 0
                val blobs = DesktopClipboardBlobStore(root.resolve("store"))
                val foreign =
                    DesktopClipboardFileSnapshotter("b", f.peers.bStore, f.verifier, blobs) { _, _, _ ->
                        lookups++
                        error("No foreign paths may be resolved")
                    }
                assertFailsWith<IllegalArgumentException> { foreign.snapshot(f.event, f.metadata, 8) }
                val wrongKey =
                    DesktopClipboardFileSnapshotter("a", f.peers.bStore, f.verifier, blobs) { _, _, _ ->
                        lookups++
                        error("A mismatched local identity must not resolve paths")
                    }
                assertFailsWith<IllegalArgumentException> { wrongKey.snapshot(f.event, f.metadata, 8) }
                val local =
                    DesktopClipboardFileSnapshotter("a", f.peers.aStore, f.verifier, blobs) { _, _, _ ->
                        lookups++
                        error("No forged paths may be resolved")
                    }
                assertFailsWith<IllegalArgumentException> {
                    local.snapshot(
                        f.event.copy(signature = "AAAA"),
                        f.metadata,
                        8,
                    )
                }
                assertEquals(0, lookups)
                assertFalse(Files.exists(root.resolve("store")))
            } finally {
                cleanup(root)
            }
        }

    @Test
    fun `whole capture budget is checked before reading the first file`() =
        runTest {
            val f = manifestFixture()
            val root = Files.createTempDirectory("clipboard-manifest-test-")
            try {
                var lookups = 0
                val blobs = DesktopClipboardBlobStore(root.resolve("store"))
                val snapshotter =
                    DesktopClipboardFileSnapshotter("a", f.peers.aStore, f.verifier, blobs) { _, _, _ ->
                        lookups++
                        error("Budget must fail before any source lookup")
                    }
                assertFailsWith<IllegalArgumentException> { snapshotter.snapshot(f.event, f.metadata, 7) }
                assertEquals(0, lookups)
                assertFalse(Files.exists(root.resolve("store")))
            } finally {
                cleanup(root)
            }
        }
}
