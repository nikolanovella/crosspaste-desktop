package com.crosspaste.workbench

import com.crosspaste.workbench.platform.ClipboardCaptureGate
import com.crosspaste.workbench.platform.ClipboardShortcutConsumption
import com.github.kwhat.jnativehook.NativeInputEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipboardShortcutTest {
    @Test
    fun `migration updates only untouched bindings`() {
        val defaults =
            Properties().apply {
                setProperty("paste_plain_text", "29+42+47")
                setProperty("show_main", "29+56+42+47")
            }
        val saved =
            Properties().apply {
                setProperty("paste_plain_text", "")
                setProperty("show_main", "29+19")
            }
        com.crosspaste.listener.migrateWorkbenchShortcuts(saved, defaults)
        assertEquals("29+42+47", saved.getProperty("paste_plain_text"))
        assertEquals("29+19", saved.getProperty("show_main"))
        assertEquals("true", saved.getProperty("workbench_shortcuts_v1"))
    }

    @Test
    fun `paired platform shortcuts share actions and reserve native paste`() {
        for ((platform, primary) in listOf("Windows" to 29, "Macos" to 3675)) {
            val properties = Properties()
            javaClass.getResourceAsStream("/shortcut_keys/$platform.properties")!!.use(properties::load)
            assertEquals("$primary+47", properties.getProperty("paste"))
            assertEquals("$primary+42+47", properties.getProperty("paste_plain_text"))
            assertEquals("$primary+56+46", properties.getProperty("copy_tools"))
            assertEquals("$primary+56+47", properties.getProperty("quick_paste"))
            assertTrue(properties.getProperty("clipboard_workbench").isNotEmpty())
        }
        assertFalse("paste" in ClipboardShortcutConsumption.actions)
    }

    @Test
    fun `custom shortcut press and release are consumed and autorepeat does not run twice`() {
        val press =
            NativeKeyEvent(
                NativeKeyEvent.NATIVE_KEY_PRESSED,
                NativeInputEvent.CTRL_MASK or NativeInputEvent.SHIFT_MASK,
                0,
                NativeKeyEvent.VC_V,
                NativeKeyEvent.CHAR_UNDEFINED,
            )
        val release =
            NativeKeyEvent(NativeKeyEvent.NATIVE_KEY_RELEASED, 0, 0, NativeKeyEvent.VC_V, NativeKeyEvent.CHAR_UNDEFINED)
        val reserved = NativeInputEvent::class.java.getDeclaredField("reserved").apply { isAccessible = true }
        assertTrue(ClipboardShortcutConsumption.press(press))
        assertFalse(ClipboardShortcutConsumption.press(press))
        assertEquals(1, reserved.getShort(press).toInt())
        ClipboardShortcutConsumption.release(release)
        assertEquals(1, reserved.getShort(release).toInt())
        assertTrue(ClipboardShortcutConsumption.press(press))
        ClipboardShortcutConsumption.release(release)
    }

    @Test
    fun `selection probe only ignores its own sequence after capture`() {
        ClipboardCaptureGate.active = true
        assertTrue(ClipboardCaptureGate.ignores(40))
        ClipboardCaptureGate.finish(41)
        assertTrue(ClipboardCaptureGate.ignores(41))
        assertFalse(ClipboardCaptureGate.ignores(42))
    }
}
