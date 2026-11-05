package com.crosspaste.sync.journal

import com.crosspaste.paste.PasteCollection
import com.crosspaste.paste.item.CreatePasteItemHelper.createFilesPasteItem
import com.crosspaste.paste.item.CreatePasteItemHelper.createImagesPasteItem
import com.crosspaste.paste.item.FilesPasteItem
import com.crosspaste.presist.DirFileInfoTree
import com.crosspaste.presist.SingleFileInfoTree
import com.crosspaste.utils.getCodecsUtils
import com.crosspaste.utils.getJsonUtils
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal data class ManifestFixture(
    val peers: ClipboardPeerFixture,
    val event: SignedClipboardChange,
    val metadata: ByteArray,
    val manifest: ClipboardFileManifest,
    val verifier: ClipboardFileManifestVerifier,
)

internal suspend fun manifestFixture(filesOverride: FilesPasteItem? = null): ManifestFixture {
    getJsonUtils()
    val peers = clipboardPeerFixture()
    val codecs = getCodecsUtils()
    val empty = DirFileInfoTree(emptyMap(), 0, codecs.hashByString("empty"))
    val folder =
        DirFileInfoTree(
            mapOf(
                "café.txt" to SingleFileInfoTree(5, codecs.hashByString("hello")),
                "empty" to empty,
            ),
            5,
            codecs.hashByString("folder"),
        )
    val files =
        filesOverride ?: createFilesPasteItem(
            relativePathList = listOf("a/1/42/photos"),
            fileInfoTreeMap =
                mapOf(
                    "photos" to folder,
                ),
        )
    val image =
        createImagesPasteItem(
            relativePathList = listOf("a/1/42/thumb.png"),
            fileInfoTreeMap = mapOf("thumb.png" to SingleFileInfoTree(3, codecs.hashByString("png"))),
        )
    val capture =
        CapturedClipboardPayload(
            "a",
            42,
            files.toStoredJson(),
            PasteCollection(if (filesOverride == null) listOf(image) else emptyList()).toStoredJson(),
            3,
            null,
            files.size + (if (filesOverride == null) image.size else 0),
            files.hash,
            1,
        )
    val metadata = Json.encodeToString(capture).encodeToByteArray()
    val hash =
        "sha256:" +
            codecs.base64Encode(
                CryptographyProvider.Default
                    .get(SHA256)
                    .hasher()
                    .hash(metadata),
            )
    val key: suspend (String) -> dev.whyoleg.cryptography.algorithms.ECDSA.PublicKey? = { device ->
        when (device) {
            "a" -> peers.aStore.secureKeyPair.signKeyPair.publicKey
            "b" -> peers.bStore.secureKeyPair.signKeyPair.publicKey
            else -> null
        }
    }
    val journal =
        SignedClipboardJournal.open(
            "a",
            peers.aStore.secureKeyPair.signKeyPair.privateKey,
            key,
            SignedClipboardSnapshot("shared"),
        ) {}
    val event = journal.append(Json.encodeToString(listOf("capture/v1", "a", "42")), hash)
    val nodes =
        ClipboardFileLayout.nodes(capture).map { slot ->
            val blob =
                if (slot.directory) {
                    null
                } else {
                    val bytes =
                        (
                            if (slot.size ==
                                0L
                            ) {
                                ""
                            } else if (slot.name == "thumb.png") {
                                "png"
                            } else {
                                "hello"
                            }
                        ).encodeToByteArray()
                    ClipboardBlob(
                        HexFormat.of().formatHex(
                            CryptographyProvider.Default
                                .get(SHA256)
                                .hasher()
                                .hash(bytes),
                        ),
                        slot.size,
                        slot.captureHash,
                    )
                }
            ClipboardFileNode(slot, blob)
        }
    return ManifestFixture(
        peers,
        event,
        metadata,
        ClipboardFileManifest("shared", event.change.id, hash, nodes),
        ClipboardFileManifestVerifier { clipboard, author -> if (clipboard == "shared") key(author) else null },
    )
}

class ClipboardFileManifestTest {
    @Test
    fun `manifest binds all file representations nested directories and original author`() =
        runTest {
            val f = manifestFixture()
            val signed =
                f.verifier.sign(
                    f.manifest,
                    f.event,
                    f.metadata,
                    f.peers.aStore.secureKeyPair.signKeyPair.privateKey,
                )
            val verified = f.verifier.verify(signed, f.event, f.metadata)
            assertEquals(f.manifest, verified.manifest)
            assertEquals(
                listOf("photos", "café.txt", "empty", "thumb.png"),
                verified.manifest.nodes.map { it.slot.name },
            )
            assertEquals(
                setOf(0, 1),
                verified.manifest.nodes
                    .map { it.slot.item }
                    .toSet(),
            )
            assertTrue(
                verified.manifest.nodes
                    .filter { it.slot.directory }
                    .all { it.blob == null },
            )
        }

    @Test
    fun `paired relay cannot sign another authors file manifest`() =
        runTest {
            val f = manifestFixture()
            assertFailsWith<IllegalArgumentException> {
                f.verifier.sign(f.manifest, f.event, f.metadata, f.peers.bStore.secureKeyPair.signKeyPair.privateKey)
            }
            val signed =
                f.verifier.sign(
                    f.manifest,
                    f.event,
                    f.metadata,
                    f.peers.aStore.secureKeyPair.signKeyPair.privateKey,
                )
            val unknown = ClipboardFileManifestVerifier { _, _ -> null }
            assertFailsWith<IllegalArgumentException> { unknown.verify(signed, f.event, f.metadata) }
        }

    @Test
    fun `changed blob hash tree and node order cannot reuse a valid signature`() =
        runTest {
            val f = manifestFixture()
            val signed =
                f.verifier.sign(
                    f.manifest,
                    f.event,
                    f.metadata,
                    f.peers.aStore.secureKeyPair.signKeyPair.privateKey,
                )
            val nodes = f.manifest.nodes.toMutableList()
            val index = nodes.indexOfFirst { !it.slot.directory }
            nodes[index] = nodes[index].copy(blob = requireNotNull(nodes[index].blob).copy(sha256 = "0".repeat(64)))
            assertFailsWith<IllegalArgumentException> {
                f.verifier.verify(signed.copy(manifest = f.manifest.copy(nodes = nodes)), f.event, f.metadata)
            }
            assertFailsWith<IllegalArgumentException> {
                f.verifier.verify(
                    signed.copy(manifest = f.manifest.copy(nodes = f.manifest.nodes.reversed())),
                    f.event,
                    f.metadata,
                )
            }
            val wrongTree =
                f.manifest.nodes.mapIndexed { i, node ->
                    if (i ==
                        index
                    ) {
                        node.copy(slot = node.slot.copy(parent = 999))
                    } else {
                        node
                    }
                }
            assertFailsWith<IllegalArgumentException> {
                f.verifier.verify(signed.copy(manifest = f.manifest.copy(nodes = wrongTree)), f.event, f.metadata)
            }
        }

    @Test
    fun `metadata capture event clipboard and protocol version are bound`() =
        runTest {
            val f = manifestFixture()
            val signed =
                f.verifier.sign(
                    f.manifest,
                    f.event,
                    f.metadata,
                    f.peers.aStore.secureKeyPair.signKeyPair.privateKey,
                )
            assertFailsWith<IllegalArgumentException> {
                f.verifier.verify(
                    signed,
                    f.event,
                    f.metadata + byteArrayOf(32),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                f.verifier.verify(signed.copy(manifest = f.manifest.copy(clipboard = "other")), f.event, f.metadata)
            }
            assertFailsWith<IllegalArgumentException> {
                f.verifier.verify(signed.copy(manifest = f.manifest.copy(version = 2)), f.event, f.metadata)
            }
            val wrongEvent = f.event.copy(change = f.event.change.copy(id = ClipboardEventId("a", 99)))
            assertFailsWith<IllegalArgumentException> { f.verifier.verify(signed, wrongEvent, f.metadata) }
        }

    @Test
    fun `malformed original and manifest signatures reject without enrolling keys`() =
        runTest {
            val f = manifestFixture()
            val signed =
                f.verifier.sign(
                    f.manifest,
                    f.event,
                    f.metadata,
                    f.peers.aStore.secureKeyPair.signKeyPair.privateKey,
                )
            assertFailsWith<IllegalArgumentException> {
                f.verifier.verify(
                    signed.copy(signature = "AAAA"),
                    f.event,
                    f.metadata,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                f.verifier.verify(
                    signed,
                    f.event.copy(signature = "AAAA"),
                    f.metadata,
                )
            }
        }
}
