package com.crosspaste.net.clientapi

import com.crosspaste.net.PasteClient
import com.crosspaste.paste.ClipboardCaptureIdentity
import com.crosspaste.paste.PasteCollection
import com.crosspaste.paste.PasteData
import com.crosspaste.paste.item.CreatePasteItemHelper.createTextPasteItem
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasteClientApiIdentityTest {
    @Test
    fun `HTTP send decorates the outgoing copy without changing the caller's record`() =
        runTest {
            val client = mockk<PasteClient>()
            val response = mockk<HttpResponse>()
            every { response.status } returns HttpStatusCode.OK
            var sent: PasteData? = null
            coEvery { client.post(any<PasteData>(), any(), any(), any(), any()) } answers {
                sent = firstArg()
                response
            }
            val item = createTextPasteItem(text = "HTTP identity")
            val paste =
                PasteData(
                    id = 4,
                    appInstanceId = "local",
                    pasteAppearItem = item,
                    pasteCollection = PasteCollection(emptyList()),
                    pasteType = 0,
                    size = item.size,
                    hash = item.hash,
                )
            val identity = ClipboardCaptureIdentity("original", 2, "metadata", "content")
            val api =
                PasteClientApi(
                    client,
                    mockk(relaxed = true),
                    decorateOutgoing = { it.copy(captureIdentity = identity) },
                )
            assertTrue(api.sendPaste(paste, "peer") {} is SuccessResult)
            assertEquals(identity, sent?.captureIdentity)
            assertEquals(item, sent?.pasteAppearItem)
            assertNull(paste.captureIdentity)
        }
}
