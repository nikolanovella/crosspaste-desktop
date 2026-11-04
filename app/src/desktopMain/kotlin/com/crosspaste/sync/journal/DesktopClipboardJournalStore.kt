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

/** Dedicated journal metadata file; never reads or replaces CrossPaste's existing database. */
class DesktopClipboardJournalStore(
    private val path: Path,
    private val clipboard: String,
) : ClipboardJournalStore {
    private val json = Json

    @Synchronized
    fun load(): ClipboardJournalSnapshot {
        if (!Files.exists(path)) return ClipboardJournalSnapshot(clipboard)
        require(Files.size(path) <= MAX_BYTES) { "Clipboard journal exceeds storage limit" }
        val restored = json.decodeFromString<ClipboardJournalSnapshot>(Files.readString(path))
        require(restored.clipboard == clipboard) { "Wrong shared clipboard journal" }
        // Validate the entire graph before exposing persisted data. Corruption must never look empty.
        return ClipboardJournal("validation", restored) { }.snapshot()
    }

    @Synchronized
    override fun save(snapshot: ClipboardJournalSnapshot) {
        require(snapshot.clipboard == clipboard)
        val bytes = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Clipboard journal exceeds storage limit" }
        val target = path.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".clipboard-journal-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            // No non-atomic fallback: failure leaves the previous journal and in-memory cursor intact.
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val MAX_BYTES = 32L * 1024 * 1024
    }
}
