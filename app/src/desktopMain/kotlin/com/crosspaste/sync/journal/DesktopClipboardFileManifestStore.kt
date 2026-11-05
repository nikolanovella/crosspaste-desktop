package com.crosspaste.sync.journal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat

/** Private immutable manifests; every load is revalidated against current approved membership. */
internal class DesktopClipboardFileManifestStore(
    root: Path,
    private val verifier: ClipboardFileManifestVerifier,
) {
    private val root = root.toAbsolutePath().normalize()
    private val mutex = Mutex()

    suspend fun load(
        event: SignedClipboardChange,
        metadata: ByteArray,
    ): SignedClipboardFileManifest? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                verifier.verifyCapture(event, metadata)
                read(path(event))?.also { verifier.verify(it, event, metadata) }
            }
        }

    suspend fun save(
        signed: SignedClipboardFileManifest,
        event: SignedClipboardChange,
        metadata: ByteArray,
    ): SignedClipboardFileManifest =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val verified = verifier.verify(signed, event, metadata)
                val stable = signed.copy(manifest = verified.manifest)
                val target = path(event)
                val existing = read(target)
                if (existing != null) {
                    verifier.verify(existing, event, metadata)
                    require(existing.manifest == stable.manifest) { "Conflicting immutable file manifest" }
                    return@withLock existing
                }
                val bytes = Json.encodeToString(stable).encodeToByteArray()
                require(bytes.size <= MAX_BYTES) { "File manifest exceeds storage budget" }
                Files.createDirectories(target.parent)
                val temporary = Files.createTempFile(target.parent, "manifest-", ".part")
                try {
                    FileChannel.open(temporary, WRITE).use { channel ->
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) channel.write(buffer)
                        channel.force(true)
                    }
                    Files.move(temporary, target, ATOMIC_MOVE)
                    stable
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
        }

    private fun path(event: SignedClipboardChange): Path {
        val change = event.change
        val key =
            Json
                .encodeToString(
                    listOf(change.clipboard, change.id.device, change.id.sequence.toString(), change.contentHash),
                ).encodeToByteArray()
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key))
        return root.resolve(hash.take(2)).resolve("$hash.json")
    }

    private fun read(path: Path): SignedClipboardFileManifest? {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Manifest is not a regular file" }
        return FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            require(size in 1..MAX_BYTES.toLong()) { "Invalid manifest size" }
            val buffer = ByteBuffer.allocate(size.toInt())
            while (buffer.hasRemaining()) require(channel.read(buffer) >= 0) { "Manifest was truncated" }
            require(channel.read(ByteBuffer.allocate(1)) < 0) { "Manifest grew while reading" }
            Json.decodeFromString<SignedClipboardFileManifest>(buffer.array().decodeToString())
        }
    }

    companion object {
        private const val MAX_BYTES = 32 * 1024 * 1024
    }
}
