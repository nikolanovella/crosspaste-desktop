package com.crosspaste.sync.journal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** Temporary bytes are never published in history until complete and verified against the signed reference. */
class DesktopClipboardPayloadReceiver(
    private val transfer: ClipboardPayloadTransfer,
    private val history: ClipboardHistoryService,
    private val stagingDirectory: Path,
) {
    suspend fun receive(
        peer: String,
        clipboard: String,
        hash: String,
        maximumMetadataBytes: Int,
    ): Int =
        withContext(Dispatchers.IO) {
            require(maximumMetadataBytes > 0)
            Files.createDirectories(stagingDirectory)
            val temporary = Files.createTempFile(stagingDirectory, "clipboard-payload-", ".part")
            try {
                val length =
                    Files.newOutputStream(temporary).use { output ->
                        transfer.download(peer, clipboard, hash, maximumMetadataBytes) { output.write(it) }
                    }
                history.storePayload(clipboard, hash, Files.readAllBytes(temporary))
                length
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
}
