package com.crosspaste.sync.journal

import com.crosspaste.test.IntegrationTest
import com.crosspaste.utils.CryptographyUtils
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@IntegrationTest
class DesktopSignedClipboardStoreTest {
    @Test
    fun `signed disk history survives restart and tampered disk data fails verification`() =
        runTest {
            val directory = Files.createTempDirectory("crosspaste-signed-journal")
            val path = directory.resolve("journal.json")
            try {
                val key = CryptographyUtils.generateSecureKeyPair().signKeyPair
                val store = DesktopSignedClipboardStore(path, "shared")
                val journal = SignedClipboardJournal.open("a", key.privateKey, { key.publicKey }, store.load(), store)
                val first = journal.append("entry", "original")
                val restartedStore = DesktopSignedClipboardStore(path, "shared")
                val restarted =
                    SignedClipboardJournal.open("a", key.privateKey, {
                        key.publicKey
                    }, restartedStore.load(), restartedStore)
                assertEquals(listOf(first), restarted.missing(emptyMap()))
                assertEquals(
                    2L,
                    restarted
                        .append("next", "next-hash")
                        .change.id.sequence,
                )
                Files.writeString(path, Files.readString(path).replace("original", "tampered"))
                assertFailsWith<IllegalArgumentException> {
                    SignedClipboardJournal.open("a", key.privateKey, { key.publicKey }, store.load(), store)
                }
            } finally {
                Files.deleteIfExists(path)
                Files.delete(directory)
            }
        }
}
