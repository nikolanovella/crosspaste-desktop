package com.crosspaste.sync.journal

import com.crosspaste.utils.CryptographyUtils
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SignedClipboardJournalTest {
    @Test
    fun `relay preserves original signatures and offline revisions`() =
        runTest {
            val aKey = CryptographyUtils.generateSecureKeyPair().signKeyPair
            val bKey = CryptographyUtils.generateSecureKeyPair().signKeyPair
            val relayKey = CryptographyUtils.generateSecureKeyPair().signKeyPair
            val members = mapOf("a" to aKey.publicKey, "b" to bKey.publicKey, "relay" to relayKey.publicKey)
            var durable = SignedClipboardSnapshot("shared")
            val a =
                SignedClipboardJournal.open(
                    "a",
                    aKey.privateKey,
                    { members[it] },
                    SignedClipboardSnapshot("shared"),
                ) { }
            val b =
                SignedClipboardJournal.open(
                    "b",
                    bKey.privateKey,
                    { members[it] },
                    SignedClipboardSnapshot("shared"),
                ) { }
            val relay =
                SignedClipboardJournal.open("relay", relayKey.privateKey, { members[it] }, durable) {
                    durable =
                        it
                }
            val original = a.append("entry", "one")
            b.ingest(listOf(original))
            val editA = a.append("entry", "a-edit", setOf(original.change.id))
            val editB = b.append("entry", "b-edit", setOf(original.change.id))
            relay.ingest(b.missing(emptyMap()))
            relay.ingest(a.missing(relay.cursor()))
            a.ingest(relay.missing(a.cursor()))
            assertEquals(setOf(editA.change, editB.change), a.heads("entry").toSet())
            val restarted = SignedClipboardJournal.open("relay", relayKey.privateKey, { members[it] }, durable) { }
            assertEquals(relay.missing(emptyMap()), restarted.missing(emptyMap()))
            assertEquals(original.signature, restarted.missing(emptyMap()).first().signature)
        }

    @Test
    fun `tampered payload author clipboard and parents reject complete batch`() =
        runTest {
            val key = CryptographyUtils.generateSecureKeyPair().signKeyPair
            val attacker = CryptographyUtils.generateSecureKeyPair().signKeyPair
            val a =
                SignedClipboardJournal.open("a", key.privateKey, {
                    if (it ==
                        "a"
                    ) {
                        key.publicKey
                    } else {
                        null
                    }
                }, SignedClipboardSnapshot("shared")) { }
            val original = a.append("entry", "one")
            val next = a.append("entry", "two", setOf(original.change.id))
            val b =
                SignedClipboardJournal.open("b", attacker.privateKey, {
                    if (it ==
                        "a"
                    ) {
                        key.publicKey
                    } else {
                        null
                    }
                }, SignedClipboardSnapshot("shared")) { }
            listOf(
                next.copy(change = next.change.copy(contentHash = "modified")),
                next.copy(change = next.change.copy(id = next.change.id.copy(device = "unknown"))),
                next.copy(change = next.change.copy(clipboard = "foreign")),
                next.copy(change = next.change.copy(parents = emptySet())),
            ).forEach { forged ->
                assertFailsWith<IllegalArgumentException> { b.ingest(listOf(original, forged)) }
                assertEquals(emptyMap(), b.cursor())
            }
            assertFailsWith<IllegalArgumentException> {
                SignedClipboardJournal.open("b", attacker.privateKey, {
                    attacker.publicKey
                }, SignedClipboardSnapshot("shared", listOf(original))) { }
            }
        }

    @Test
    fun `failed signed persistence does not consume sequence`() =
        runTest {
            val key = CryptographyUtils.generateSecureKeyPair().signKeyPair
            var fail = true
            val journal =
                SignedClipboardJournal.open("a", key.privateKey, {
                    key.publicKey
                }, SignedClipboardSnapshot("shared")) { if (fail) error("disk full") }
            assertFailsWith<IllegalStateException> { journal.append("entry", "one") }
            fail = false
            assertEquals(
                1L,
                journal
                    .append("entry", "one")
                    .change.id.sequence,
            )
        }

    @Test
    fun `canonical bytes are stable across parent set iteration order and separate every field`() {
        val one = ClipboardEventId("a", 1)
        val two = ClipboardEventId("b", 1)
        val change = ClipboardChange(ClipboardEventId("a", 2), "shared", "entry", 3, linkedSetOf(one, two), "hash")
        assertContentEquals(
            SignedClipboardJournal.signingBytes(change),
            SignedClipboardJournal.signingBytes(change.copy(parents = linkedSetOf(two, one))),
        )
        val variants =
            listOf(
                change.copy(entry = "other"),
                change.copy(clock = 4),
                change.copy(contentHash = "other"),
                change.copy(deleted = true),
            )
        assertEquals(
            5,
            (variants + change).map { SignedClipboardJournal.signingBytes(it).decodeToString() }.toSet().size,
        )
    }
}
