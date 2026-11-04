package com.crosspaste.sync.journal

import com.crosspaste.db.TestDriverFactory
import com.crosspaste.db.createDatabase
import com.crosspaste.secure.SecureKeyPairSerializer
import com.crosspaste.utils.CryptographyUtils
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClipboardMembershipStoreTest {
    @Test
    fun `creation and member approval survive store recreation and verify authorship`() =
        runTest {
            val database = createDatabase(TestDriverFactory())
            val serializer = SecureKeyPairSerializer()
            val keys = CryptographyUtils.generateSecureKeyPair().signKeyPair
            val peer = CryptographyUtils.generateSecureKeyPair().signKeyPair
            val store = ClipboardMembershipStore(database, serializer)
            store.createClipboard("shared", "local", keys.publicKey, contributeLocal = false)
            store.approveMember("shared", "peer", peer.publicKey)
            store.approveMember("shared", "peer", peer.publicKey)
            val recreated = ClipboardMembershipStore(database, serializer)
            val journalStore = SqlSignedClipboardStore(database, "shared")
            val journal =
                SignedClipboardJournal.open("peer", peer.privateKey, {
                    recreated.signingKey("shared", it)
                }, journalStore.load(), journalStore)
            val saved = journal.append("entry", "hash")
            assertEquals(listOf(saved), journalStore.load().changes)
            assertEquals(
                false,
                database.sharedClipboardDatabaseQueries
                    .getWorkspace("shared")
                    .executeAsOne()
                    .contributeLocal,
            )
            assertEquals(
                2,
                database.sharedClipboardDatabaseQueries
                    .getMembers("shared")
                    .executeAsList()
                    .size,
            )
        }

    @Test
    fun `unapproved authors and cross clipboard identities are not trusted`() =
        runTest {
            val database = createDatabase(TestDriverFactory())
            val key = CryptographyUtils.generateSecureKeyPair().signKeyPair
            val store = ClipboardMembershipStore(database, SecureKeyPairSerializer())
            store.createClipboard("one", "local", key.publicKey, true)
            assertNotNull(store.signingKey("one", "local"))
            assertNull(store.signingKey("one", "unknown"))
            assertNull(store.signingKey("two", "local"))
            assertFailsWith<IllegalArgumentException> { store.approveMember("two", "peer", key.publicKey) }
        }

    @Test
    fun `key replacement and accidental clipboard recreation cannot overwrite saved membership`() =
        runTest {
            val database = createDatabase(TestDriverFactory())
            val one = CryptographyUtils.generateSecureKeyPair().signKeyPair
            val other = CryptographyUtils.generateSecureKeyPair().signKeyPair
            val store = ClipboardMembershipStore(database, SecureKeyPairSerializer())
            store.createClipboard("shared", "local", one.publicKey, true)
            val before = database.sharedClipboardDatabaseQueries.getMember("shared", "local").executeAsOne()
            assertFailsWith<IllegalArgumentException> { store.approveMember("shared", "local", other.publicKey) }
            assertFailsWith<IllegalArgumentException> {
                store.createClipboard(
                    "shared",
                    "replacement",
                    other.publicKey,
                    false,
                )
            }
            assertEquals(before, database.sharedClipboardDatabaseQueries.getMember("shared", "local").executeAsOne())
            assertNull(database.sharedClipboardDatabaseQueries.getMember("shared", "replacement").executeAsOneOrNull())
        }
}
