package com.crosspaste.workbench.platform

import com.crosspaste.paste.PasswordManagerHints
import com.crosspaste.platform.windows.Win32ClipboardFormatProbe
import com.crosspaste.platform.windows.api.User32
import java.awt.event.KeyEvent

class ClipboardPlatformWindows : ClipboardPlatform {
    override val primaryKey = KeyEvent.VK_CONTROL

    override fun sequence(): Int = User32.INSTANCE.GetClipboardSequenceNumber()

    override fun concealed(): Boolean = PasswordManagerHints.isConcealedOnWindows(Win32ClipboardFormatProbe())

    override fun captureTarget(): () -> Boolean {
        val target = User32.INSTANCE.GetForegroundWindow()
        return {
            if (target == null || !User32.INSTANCE.IsWindow(target)) {
                false
            } else {
                User32.INSTANCE.SetForegroundWindow(target)
                User32.INSTANCE.GetForegroundWindow() == target
            }
        }
    }
}
