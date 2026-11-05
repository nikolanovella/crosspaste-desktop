package com.crosspaste.sync.journal

import com.crosspaste.test.IntegrationTest
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@IntegrationTest
class DesktopClipboardFileManifestStoreTest {
    private fun cleanup(root: Path) {
        require(
            root.toAbsolutePath().normalize().startsWith(
                Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize(),
            ),
        )
        require(root.fileName.toString().startsWith("clipboard-manifest-store-test-"))
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    @Test
    fun `restart retains original signed manifest and repeated signatures reuse immutable record`() =
        runTest {
            val f = manifestFixture()
            val root = Files.createTempDirectory("clipboard-manifest-store-test-")
            try {
                val store = DesktopClipboardFileManifestStore(root, f.verifier)
                assertNull(store.load(f.event, f.metadata))
                val signed =
                    f.verifier.sign(
                        f.manifest,
                        f.event,
                        f.metadata,
                        f.peers.aStore.secureKeyPair.signKeyPair.privateKey,
                    )
                assertEquals(signed, store.save(signed, f.event, f.metadata))
                val restarted = DesktopClipboardFileManifestStore(root, f.verifier)
                assertEquals(signed, restarted.load(f.event, f.metadata))
                val signedAgain =
                    f.verifier.sign(
                        f.manifest,
                        f.event,
                        f.metadata,
                        f.peers.aStore.secureKeyPair.signKeyPair.privateKey,
                    )
                assertEquals(signed, restarted.save(signedAgain, f.event, f.metadata))
                Files.walk(root).use { paths -> assertTrue(paths.noneMatch { it.toString().endsWith(".part") }) }
            } finally {
                cleanup(root)
            }
        }

    @Test
    fun `conflicting signed blob descriptors cannot replace committed snapshot`() =
        runTest {
            val f = manifestFixture()
            val root = Files.createTempDirectory("clipboard-manifest-store-test-")
            try {
                val store = DesktopClipboardFileManifestStore(root, f.verifier)
                val key = f.peers.aStore.secureKeyPair.signKeyPair.privateKey
                val signed = f.verifier.sign(f.manifest, f.event, f.metadata, key)
                store.save(signed, f.event, f.metadata)
                val changed =
                    f.manifest.copy(
                        nodes =
                            f.manifest.nodes.map { node ->
                                node.copy(blob = node.blob?.copy(sha256 = "0".repeat(64)))
                            },
                    )
                val conflict = f.verifier.sign(changed, f.event, f.metadata, key)
                assertFailsWith<IllegalArgumentException> { store.save(conflict, f.event, f.metadata) }
                assertEquals(signed, store.load(f.event, f.metadata))
            } finally {
                cleanup(root)
            }
        }

    @Test
    fun `damaged records and revoked membership do not become trusted after restart`() =
        runTest {
            val f = manifestFixture()
            val root = Files.createTempDirectory("clipboard-manifest-store-test-")
            try {
                val store = DesktopClipboardFileManifestStore(root, f.verifier)
                val signed =
                    f.verifier.sign(
                        f.manifest,
                        f.event,
                        f.metadata,
                        f.peers.aStore.secureKeyPair.signKeyPair.privateKey,
                    )
                store.save(signed, f.event, f.metadata)
                val revoked = DesktopClipboardFileManifestStore(root, ClipboardFileManifestVerifier { _, _ -> null })
                assertFailsWith<IllegalArgumentException> { revoked.load(f.event, f.metadata) }
                val record =
                    Files.walk(root).use { paths ->
                        paths.filter { it.toString().endsWith(".json") }.findFirst().orElseThrow()
                    }
                Files.writeString(record, "{}")
                assertFailsWith<IllegalArgumentException> { store.load(f.event, f.metadata) }
            } finally {
                cleanup(root)
            }
        }

    @Test
    fun `cached snapshot survives original changes and move without resolving source again`() =
        runTest {
            val f = manifestFixture()
            val root = Files.createTempDirectory("clipboard-manifest-store-test-")
            try {
                val photos = Files.createDirectories(root.resolve("photos"))
                Files.createDirectory(photos.resolve("empty"))
                Files.writeString(photos.resolve("café.txt"), "hello")
                Files.writeString(root.resolve("thumb.png"), "png")
                val blobs = DesktopClipboardBlobStore(root.resolve("blobs"))
                val manifests = DesktopClipboardFileManifestStore(root.resolve("manifests"), f.verifier)
                var lookups = 0
                val snapshotter =
                    DesktopClipboardFileSnapshotter("a", f.peers.aStore, f.verifier, blobs, manifests) { _, item, _ ->
                        lookups++
                        if (item == 0) photos else root.resolve("thumb.png")
                    }
                val signed = snapshotter.snapshot(f.event, f.metadata, 8)
                assertEquals(2, lookups)
                Files.writeString(photos.resolve("café.txt"), "CHANGED")
                Files.move(photos, root.resolve("moved-photos"))
                val restarted =
                    DesktopClipboardFileSnapshotter(
                        "a",
                        f.peers.aStore,
                        f.verifier,
                        blobs,
                        DesktopClipboardFileManifestStore(root.resolve("manifests"), f.verifier),
                    ) { _, _, _ -> error("Must use immutable cache") }
                assertEquals(signed, restarted.snapshot(f.event, f.metadata, 8))
                assertFailsWith<IllegalArgumentException> { restarted.snapshot(f.event, f.metadata, 7) }
            } finally {
                cleanup(root)
            }
        }
}
