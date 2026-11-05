package com.crosspaste.sync.journal

import com.crosspaste.utils.CROSS_PASTE_SEED
import com.crosspaste.utils.StreamingMurmurHash3
import com.crosspaste.utils.appendHex
import com.crosspaste.utils.getCodecsUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat

/** Private content-addressed storage. Network callers must separately prove a signed manifest reference. */
internal class DesktopClipboardBlobStore(
    root: Path,
) {
    private val root = root.toAbsolutePath().normalize()
    private val mutex = Mutex()

    suspend fun snapshot(
        source: Path,
        expectedSize: Long,
        expectedCaptureHash: String,
        maximumBytes: Long,
    ): ClipboardBlob = ingest(source, expectedSize, expectedCaptureHash, maximumBytes, null)

    suspend fun importVerified(
        source: Path,
        descriptor: ClipboardBlob,
        maximumBytes: Long,
    ): ClipboardBlob = ingest(source, descriptor.size, descriptor.captureHash, maximumBytes, descriptor.sha256)

    private suspend fun ingest(
        source: Path,
        expectedSize: Long,
        expectedCaptureHash: String,
        maximumBytes: Long,
        expectedSha256: String?,
    ): ClipboardBlob =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(expectedSize >= 0 && maximumBytes >= expectedSize)
                require(Regex("[0-9a-f]{32}").matches(expectedCaptureHash))
                require(Files.isRegularFile(source, NOFOLLOW_LINKS)) { "Clipboard source is not a regular file" }
                require(Files.size(source) == expectedSize) { "Clipboard source size changed" }
                val staging = root.resolve("staging")
                Files.createDirectories(staging)
                val temporary = Files.createTempFile(staging, "blob-", ".part")
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val capture = StreamingMurmurHash3(CROSS_PASTE_SEED)
                    var copied = 0L
                    FileChannel.open(source, READ, NOFOLLOW_LINKS).use { input ->
                        Files.newOutputStream(temporary).use { output ->
                            val buffer = ByteBuffer.allocate(CHUNK_BYTES)
                            while (true) {
                                buffer.clear()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                require(
                                    read.toLong() <= expectedSize - copied,
                                ) { "Clipboard source grew during snapshot" }
                                digest.update(buffer.array(), 0, read)
                                capture.update(buffer.array(), 0, read)
                                output.write(buffer.array(), 0, read)
                                copied += read
                            }
                        }
                    }
                    require(copied == expectedSize) { "Clipboard source shrank during snapshot" }
                    val legacy =
                        capture.finish().let {
                            buildString(32) {
                                appendHex(it[0])
                                appendHex(it[1])
                            }
                        }
                    require(legacy == expectedCaptureHash) { "Clipboard source content changed after capture" }
                    val hash = HexFormat.of().formatHex(digest.digest())
                    require(expectedSha256 == null || hash == expectedSha256) { "Received file SHA-256 did not verify" }
                    val descriptor = ClipboardBlob(hash, copied, legacy)
                    val target = path(hash)
                    Files.createDirectories(target.parent)
                    if (!verified(descriptor)) {
                        FileChannel.open(temporary, WRITE).use { it.force(true) }
                        Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
                    }
                    descriptor
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
        }

    suspend fun has(blob: ClipboardBlob): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock { verified(blob) }
        }

    suspend fun matches(
        source: Path,
        blob: ClipboardBlob,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (!Files.isRegularFile(source, NOFOLLOW_LINKS) ||
                Files.size(source) != blob.size
            ) {
                return@withContext false
            }
            val digest = MessageDigest.getInstance("SHA-256")
            FileChannel.open(source, READ, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate(CHUNK_BYTES)
                while (true) {
                    buffer.clear()
                    val read = channel.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer.array(), 0, read)
                }
            }
            HexFormat.of().formatHex(digest.digest()) == blob.sha256
        }

    suspend fun copyVerified(
        blob: ClipboardBlob,
        destination: Path,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(verified(blob)) { "Private file bytes are unavailable" }
            Files.copy(path(blob.sha256), destination)
            require(matches(destination, blob)) { "Landed file SHA-256 did not verify" }
            FileChannel.open(destination, WRITE).use { it.force(true) }
        }
    }

    /** Range reads do not allocate or hash the entire file. Receiver must verify full SHA-256 before publication. */
    suspend fun readChunk(
        hash: String,
        offset: Long,
        limit: Int = CHUNK_BYTES,
    ): ClipboardBlobChunk =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(offset >= 0 && limit in 1..CHUNK_BYTES)
                val source = path(hash)
                require(Files.isRegularFile(source, NOFOLLOW_LINKS)) { "Clipboard blob is unavailable" }
                FileChannel.open(source, READ, NOFOLLOW_LINKS).use { channel ->
                    val size = channel.size()
                    require(offset <= size)
                    val buffer = ByteBuffer.allocate(minOf(limit.toLong(), size - offset).toInt())
                    channel.position(offset)
                    while (buffer.hasRemaining()) {
                        check(channel.read(buffer) >= 0) { "Clipboard blob changed during read" }
                    }
                    ClipboardBlobChunk(hash, offset, size, getCodecsUtils().base64Encode(buffer.array()))
                }
            }
        }

    private fun path(hash: String): Path {
        require(Regex("[0-9a-f]{64}").matches(hash)) { "Invalid clipboard blob digest" }
        return root.resolve("blobs").resolve(hash.take(2)).resolve(hash)
    }

    private fun verified(blob: ClipboardBlob): Boolean {
        if (blob.size < 0) return false
        val source = path(blob.sha256)
        if (!Files.isRegularFile(source, NOFOLLOW_LINKS) || Files.size(source) != blob.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(source, READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(CHUNK_BYTES)
            while (true) {
                buffer.clear()
                val read = channel.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer.array(), 0, read)
            }
        }
        return HexFormat.of().formatHex(digest.digest()) == blob.sha256
    }

    companion object {
        const val CHUNK_BYTES = 1024 * 1024
    }
}
