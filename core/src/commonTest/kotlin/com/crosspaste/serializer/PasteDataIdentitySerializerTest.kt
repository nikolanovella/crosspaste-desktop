package com.crosspaste.serializer

import com.crosspaste.paste.ClipboardCaptureIdentity
import com.crosspaste.paste.PasteCollection
import com.crosspaste.paste.PasteData
import com.crosspaste.paste.item.CreatePasteItemHelper.createTextPasteItem
import com.crosspaste.utils.getJsonUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PasteDataIdentitySerializerTest {
    @Serializable
    private data class LegacyView(
        val id: Long,
        val appInstanceId: String,
    )

    private fun paste(): PasteData {
        val item = createTextPasteItem(text = "clipboard identity")
        return PasteData(
            id = 42,
            appInstanceId = "relay",
            pasteAppearItem = item,
            pasteCollection = PasteCollection(emptyList()),
            pasteType = 0,
            size = item.size,
            hash = item.hash,
        )
    }

    @Test
    fun `capture identity roundtrips while older decoders ignore the optional extension`() {
        val json = getJsonUtils().JSON
        val identity = ClipboardCaptureIdentity("original", 7, "metadata", "content")
        val wire = json.encodeToString(paste().copy(captureIdentity = identity))
        assertEquals(identity, json.decodeFromString<PasteData>(wire).captureIdentity)
        assertEquals(LegacyView(42, "relay"), json.decodeFromString<LegacyView>(wire))
    }

    @Test
    fun `ordinary legacy pastes do not emit the identity field and still deserialize`() {
        val json = getJsonUtils().JSON
        val wire = json.encodeToString(paste())
        assertFalse("captureIdentity" in json.parseToJsonElement(wire).jsonObject)
        assertNull(json.decodeFromString<PasteData>(wire).captureIdentity)
    }
}
