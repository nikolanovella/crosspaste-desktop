package com.crosspaste.workbench.platform

import com.github.kwhat.jnativehook.NativeInputEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import java.util.concurrent.ConcurrentHashMap

/** Only explicit clipboard actions are consumed. Native C/X/V stay untouched. */
object ClipboardShortcutConsumption {
    val actions = setOf("paste_plain_text", "quick_paste", "copy_tools", "clipboard_workbench", "show_main")
    private val held = ConcurrentHashMap.newKeySet<Int>()
    private val reserved = NativeInputEvent::class.java.getDeclaredField("reserved").apply { isAccessible = true }

    fun press(event: NativeKeyEvent): Boolean {
        reserved.setShort(event, 1)
        return held.add(event.keyCode)
    }

    fun release(event: NativeKeyEvent) {
        if (held.remove(event.keyCode)) reserved.setShort(event, 1)
    }
}
