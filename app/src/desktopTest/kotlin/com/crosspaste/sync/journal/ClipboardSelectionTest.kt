package com.crosspaste.sync.journal

import com.crosspaste.utils.CryptographyUtils
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClipboardSelectionTest {
    private val oldItem = ClipboardChange(ClipboardEventId("pc", 1), "shared", "old", 1, contentHash = "old")
    private val newItem = ClipboardChange(ClipboardEventId("mac", 1), "shared", "new", 1, contentHash = "new")
    private val oldSelection = ClipboardSelection("shared", "pc", 1, oldItem.id)
    private val newSelection = ClipboardSelection("shared", "mac", 2, newItem.id)

    @Test
    fun historyAloneDoesNotSelectAnItem() {
        assertEquals(
            ClipboardSelectionResolution.None,
            resolveClipboardSelection("shared", emptyList(), listOf(oldItem)),
        )
    }

    @Test
    fun missingTargetDoesNotFallBackAndResolvesWhenItArrives() {
        val selections = listOf(newSelection, oldSelection)
        assertEquals(
            ClipboardSelectionResolution.Pending(newSelection),
            resolveClipboardSelection("shared", selections, listOf(oldItem)),
        )
        assertEquals(
            ClipboardSelectionResolution.Ready(newSelection, newItem),
            resolveClipboardSelection("shared", selections.reversed(), listOf(newItem, oldItem)),
        )
    }

    @Test
    fun rejectsConflictingRecordsFromOneAuthorAtTheSameClock() {
        assertFailsWith<IllegalArgumentException> {
            resolveClipboardSelection(
                "shared",
                listOf(newSelection, newSelection.copy(target = oldItem.id)),
                listOf(newItem, oldItem),
            )
        }
    }

    @Test
    fun verifiesOriginalAuthorAndRejectsTamperedTarget() =
        runTest {
            val key = CryptographyUtils.generateSecureKeyPair().signKeyPair
            val authenticator =
                ClipboardSelectionAuthenticator("shared") { author ->
                    key.publicKey.takeIf { author == "mac" }
                }
            val signed = authenticator.sign(newSelection, key.privateKey)
            assertEquals(newSelection, authenticator.verify(signed))
            assertFailsWith<IllegalArgumentException> {
                authenticator.verify(signed.copy(selection = newSelection.copy(target = oldItem.id)))
            }
            assertFailsWith<IllegalArgumentException> {
                authenticator.verify(signed.copy(selection = newSelection.copy(author = "pc")))
            }
        }
}
