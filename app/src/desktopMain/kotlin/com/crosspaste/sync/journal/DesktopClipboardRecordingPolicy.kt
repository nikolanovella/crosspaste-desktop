package com.crosspaste.sync.journal

import com.crosspaste.Database

/** The desktop has one user-facing recording choice; this field is its per-clipboard mirror. */
internal class DesktopClipboardRecordingPolicy(
    private val database: Database,
    private val recording: () -> Boolean,
) {
    fun refresh() {
        database.sharedClipboardDatabaseQueries.followRecordingPreference(recording())
    }
}
