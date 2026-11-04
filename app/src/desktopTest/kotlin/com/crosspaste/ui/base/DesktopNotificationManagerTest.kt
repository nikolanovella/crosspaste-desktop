package com.crosspaste.ui.base

import com.crosspaste.app.DesktopAppWindowManager
import com.crosspaste.notification.Message
import com.crosspaste.notification.MessageType
import com.crosspaste.platform.Platform
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopNotificationManagerTest {

    private val message = Message(messageId = 1, title = "title", message = "body", messageType = MessageType.Info)

    private fun macosManagerWithHiddenWindow(postMacosNotification: (String, String) -> Boolean) =
        DesktopNotificationManager(
            appWindowManager =
                mockk<DesktopAppWindowManager> {
                    every { getCurrentMainWindowInfo().show } returns false
                },
            copywriter = mockk(relaxed = true),
            platform =
                mockk<Platform> {
                    every { isLinux() } returns false
                    every { isMacos() } returns true
                },
            soundService = mockk(relaxed = true),
            postMacosNotification = postMacosNotification,
        )

    @Test
    fun `hidden window on macOS posts to Notification Center`() {
        val posted = mutableListOf<Pair<String, String>>()
        val manager = macosManagerWithHiddenWindow { title, body -> posted.add(title to body) }

        manager.doSendNotification(message)

        assertEquals(listOf("title" to "body"), posted)
        assertEquals(emptyList(), manager.notificationList.value)
    }

    @Test
    fun `hidden window on macOS falls back to an in-app toast without Notification Center`() {
        val manager = macosManagerWithHiddenWindow { _, _ -> false }

        manager.doSendNotification(message)

        assertEquals(listOf(message), manager.notificationList.value)
    }
}
