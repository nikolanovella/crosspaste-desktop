package com.crosspaste.workbench

import com.crosspaste.config.CommonConfigManager
import com.crosspaste.config.DesktopAppConfig
import com.crosspaste.paste.PasteboardService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SharedClipboardParticipationTest {
    @Test
    fun `recording and shared paste are independent for all four combinations`() =
        runTest {
            for (capture in listOf(false, true)) {
                for (paste in listOf(false, true)) {
                    val manager = mockk<CommonConfigManager>()
                    every { manager.getCurrentConfig() } returns
                        DesktopAppConfig(
                            language = "en",
                            enablePasteboardListening = capture,
                            useSharedClipboardForPaste = paste,
                        )
                    val tasks = Channel<suspend () -> Result<Unit?>>(Channel.UNLIMITED)
                    val service =
                        object : PasteboardService by mockk(relaxed = true) {
                            override val configManager = manager
                            override val remotePasteboardChannel = tasks
                            override val serviceScope: CoroutineScope = backgroundScope
                            override val logger = KotlinLogging.logger {}

                            override fun startRemotePasteboardListener() =
                                super<PasteboardService>.startRemotePasteboardListener()
                        }
                    var writes = 0
                    service.startRemotePasteboardListener()
                    tasks.send {
                        writes++
                        Result.success(Unit)
                    }
                    runCurrent()
                    assertEquals(if (paste) 1 else 0, writes, "capture=$capture paste=$paste")
                    tasks.close()
                }
            }
        }

    @Test
    fun `paste policy is rechecked after queuing and does not disable recording`() =
        runTest {
            var config = DesktopAppConfig(language = "en")
            val manager = mockk<CommonConfigManager>()
            every { manager.getCurrentConfig() } answers { config }
            val tasks = Channel<suspend () -> Result<Unit?>>(Channel.UNLIMITED)
            val service =
                object : PasteboardService by mockk(relaxed = true) {
                    override val configManager = manager
                    override val remotePasteboardChannel = tasks
                    override val serviceScope: CoroutineScope = backgroundScope
                    override val logger = KotlinLogging.logger {}

                    override fun startRemotePasteboardListener() =
                        super<PasteboardService>.startRemotePasteboardListener()
                }
            var writes = 0
            service.startRemotePasteboardListener()
            tasks.send {
                writes++
                Result.success(Unit)
            }
            config = config.copy(useSharedClipboardForPaste = false)
            runCurrent()
            assertEquals(0, writes)
            assertTrue(config.enablePasteboardListening)
            config = config.copy(enablePasteboardListening = false, useSharedClipboardForPaste = true)
            tasks.send {
                writes++
                Result.success(Unit)
            }
            runCurrent()
            assertEquals(1, writes)
            tasks.close()
        }

    @Test
    fun `device participation survives config edits and serialization`() {
        val config = DesktopAppConfig(language = "en").copy("useSharedClipboardForPaste", false)
        assertFalse(config.useSharedClipboardForPaste)
        assertTrue(config.enablePasteboardListening)
        val restored = Json.decodeFromString<DesktopAppConfig>(Json.encodeToString(config))
        assertFalse(restored.useSharedClipboardForPaste)
        assertTrue(
            restored
                .copy(
                    "enablePasteboardListening",
                    false,
                ).copy("useSharedClipboardForPaste", true)
                .useSharedClipboardForPaste,
        )
    }
}
