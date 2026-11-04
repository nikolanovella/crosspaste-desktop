package com.crosspaste.workbench.platform

import com.crosspaste.platform.macos.MacAppUtils
import com.crosspaste.platform.macos.api.MacosApi
import com.sun.jna.ptr.IntByReference
import java.awt.event.KeyEvent

class ClipboardPlatformMac : ClipboardPlatform {
    override val primaryKey = KeyEvent.VK_META

    override fun sequence(): Int =
        MacosApi.INSTANCE.getPasteboardChangeCount(-1, IntByReference(), IntByReference(), IntByReference())

    override fun concealed(): Boolean {
        val concealed = IntByReference()
        MacosApi.INSTANCE.getPasteboardChangeCount(-1, IntByReference(), IntByReference(), concealed)
        return concealed.value != 0
    }

    override fun captureTarget(): () -> Boolean {
        val bundle = MacAppUtils.getCurrentActiveAppInfo()?.substringBefore('\n')
        return {
            if (bundle == null) {
                false
            } else {
                MacAppUtils.searchToBack(bundle)
                MacAppUtils.getCurrentActiveAppInfo()?.substringBefore('\n') == bundle
            }
        }
    }
}
