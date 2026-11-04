package com.crosspaste.workbench.platform

import com.crosspaste.platform.Platform
import java.awt.Robot
import java.awt.event.KeyEvent

interface ClipboardPlatform {
    val primaryKey: Int

    fun sequence(): Int

    fun concealed(): Boolean

    fun captureTarget(): () -> Boolean

    fun copySelection() {
        val robot = Robot()
        robot.keyPress(primaryKey)
        robot.keyPress(KeyEvent.VK_C)
        robot.keyRelease(KeyEvent.VK_C)
        robot.keyRelease(primaryKey)
    }
}

fun clipboardPlatform(platform: Platform): ClipboardPlatform =
    if (platform.isMacos()) ClipboardPlatformMac() else ClipboardPlatformWindows()

/** Probe copies must not enqueue large selected folders for automatic file sync. */
object ClipboardCaptureGate {
    @Volatile var active: Boolean = false

    @Volatile private var ignoredSequence: Int? = null

    fun finish(sequence: Int) {
        ignoredSequence = sequence
        active = false
    }

    fun ignores(sequence: Int): Boolean = active || ignoredSequence == sequence
}
