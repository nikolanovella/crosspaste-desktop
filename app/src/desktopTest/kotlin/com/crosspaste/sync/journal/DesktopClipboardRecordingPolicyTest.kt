package com.crosspaste.sync.journal

import com.crosspaste.config.DesktopConfigManager
import com.crosspaste.db.TestDriverFactory
import com.crosspaste.db.createDatabase
import com.crosspaste.presist.OneFilePersist
import com.crosspaste.test.IntegrationTest
import com.crosspaste.utils.DesktopLocaleUtils
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@IntegrationTest
class DesktopClipboardRecordingPolicyTest {
    @Test
    fun `policy follows recording but never changes automatic paste other preferences or paused workspaces`(
        @TempDir directory: File,
    ) {
        val database = createDatabase(TestDriverFactory())
        val queries = database.sharedClipboardDatabaseQueries
        queries.putWorkspace("enabled", true, false)
        queries.putWorkspace("paused", false, false)
        val config =
            DesktopConfigManager(OneFilePersist(File(directory, "config.json").toOkioPath()), DesktopLocaleUtils)
        config.updateConfig("useSharedClipboardForPaste", false)
        val policy = DesktopClipboardRecordingPolicy(database) { config.getCurrentConfig().enablePasteboardListening }
        val initial = config.getCurrentConfig()
        policy.refresh()
        assertTrue(queries.getWorkspace("enabled").executeAsOne().contributeLocal)
        assertFalse(queries.getWorkspace("paused").executeAsOne().enabled)
        assertFalse(queries.getWorkspace("paused").executeAsOne().contributeLocal)
        assertEquals(initial, config.getCurrentConfig())
        config.updateConfig("enablePasteboardListening", false)
        val off = config.getCurrentConfig()
        policy.refresh()
        assertFalse(queries.getWorkspace("enabled").executeAsOne().contributeLocal)
        assertEquals(off, config.getCurrentConfig())
        config.updateConfig("enablePasteboardListening", true)
        policy.refresh()
        assertTrue(queries.getWorkspace("enabled").executeAsOne().contributeLocal)
        assertFalse(config.getCurrentConfig().useSharedClipboardForPaste)
        assertEquals(initial, config.getCurrentConfig())
    }

    @Test
    fun `refresh with no joined clipboards does not create any clipboard`() {
        val database = createDatabase(TestDriverFactory())
        DesktopClipboardRecordingPolicy(database) { true }.refresh()
        assertTrue(
            database.sharedClipboardDatabaseQueries
                .getWorkspaces()
                .executeAsList()
                .isEmpty(),
        )
    }
}
