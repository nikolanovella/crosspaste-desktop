package com.crosspaste.sync.journal

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/** Stores original signatures with events. The signed journal authenticates them again on open. */
class DesktopSignedClipboardStore(
    private val path: Path,
    private val clipboard: String,
) : SignedClipboardStore {
    @Synchronized
    fun load(): SignedClipboardSnapshot {
        if (!Files.exists(path)) return SignedClipboardSnapshot(clipboard)
        require(Files.size(path) <= MAX_BYTES)
        val snapshot = Json.decodeFromString<SignedClipboardSnapshot>(Files.readString(path))
        require(snapshot.clipboard == clipboard)
        return snapshot
    }

    @Synchronized
    override fun save(snapshot: SignedClipboardSnapshot) {
        require(snapshot.clipboard == clipboard)
        val bytes = Json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val target = path.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".signed-clipboard-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val MAX_BYTES = 32L * 1024 * 1024
    }
}
