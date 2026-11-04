package com.crosspaste.listener

import com.crosspaste.app.AppFileType
import com.crosspaste.path.AppPathProvider
import com.crosspaste.platform.Platform
import com.crosspaste.utils.DesktopResourceUtils
import com.crosspaste.utils.getFileUtils
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.Properties

class DesktopShortcutKeys(
    private val appPathProvider: AppPathProvider,
    private val platform: Platform,
    private val shortcutKeysLoader: ShortcutKeysLoader,
) : ShortcutKeys {

    companion object {
        const val PASTE = "paste"
        const val PASTE_PLAIN_TEXT = "paste_plain_text"
        const val PASTE_PRIMARY_TYPE = "paste_primary_type"
        const val PASTE_LOCAL_LAST = "paste_local_last"
        const val PASTE_REMOTE_LAST = "paste_remote_last"
        const val COPY_TOOLS = "copy_tools"
        const val QUICK_PASTE = "quick_paste"
        const val CLIPBOARD_WORKBENCH = "clipboard_workbench"
        const val SHOW_MAIN = "show_main"

        // Currently, this action is not only used to show the search window
        // but also to close the search window. However, this key is used in user configuration
        // so it remains unchanged
        const val SHOW_SEARCH = "show_search"
        const val SHOW_PASTE_PANEL = "show_paste_panel"
        const val HIDE_WINDOW = "hide_window"
        const val TOGGLE_PASTEBOARD_MONITORING = "toggle_pasteboard_monitoring"
        const val TOGGLE_ENCRYPT = "toggle_encrypt"
    }

    private val logger: KLogger = KotlinLogging.logger {}

    private val fileUtils = getFileUtils()

    private val _shortcutKeysCore: MutableStateFlow<ShortcutKeysCore> =
        MutableStateFlow(defaultKeysCore())

    override var shortcutKeysCore: StateFlow<ShortcutKeysCore> = _shortcutKeysCore

    init {
        runCatching {
            loadKeysCore()?.let {
                _shortcutKeysCore.value = it
            }
        }.onFailure {
            _shortcutKeysCore.value = defaultKeysCore()
        }
    }

    private fun defaultKeysCore(): ShortcutKeysCore = shortcutKeysLoader.load(platform.name)

    private fun loadKeysCore(): ShortcutKeysCore? =
        runCatching {
            val shortcutKeysPropertiesPath =
                appPathProvider
                    .resolve("shortcut-keys.properties", AppFileType.USER)

            val platformProperties =
                DesktopResourceUtils.loadProperties(
                    "shortcut_keys/${platform.name}.properties",
                )

            if (!fileUtils.existFile(shortcutKeysPropertiesPath)) {
                writeProperties(platformProperties, shortcutKeysPropertiesPath)
            } else {
                val properties = Properties()
                InputStreamReader(shortcutKeysPropertiesPath.toFile().inputStream(), StandardCharsets.UTF_8)
                    .use { inputStreamReader -> properties.load(inputStreamReader) }
                for (key in platformProperties.keys) {
                    if (!properties.containsKey(key)) {
                        properties.setProperty(key.toString(), platformProperties.getProperty(key.toString()))
                    }
                }
                if (properties.getProperty("workbench_shortcuts_v1") != "true") {
                    migrateWorkbenchShortcuts(properties, platformProperties)
                }
                writeProperties(properties, shortcutKeysPropertiesPath)
            }

            val path = shortcutKeysPropertiesPath.toFile().toOkioPath()

            shortcutKeysLoader.load(path)
        }.onFailure { e ->
            logger.error(e) { "Failed to load shortcut keys" }
        }.getOrNull()

    private fun writeProperties(
        properties: Properties,
        path: Path,
    ) {
        path.toFile().outputStream().use { fileOutputStream ->
            OutputStreamWriter(fileOutputStream, Charsets.UTF_8).use { writer ->
                properties.store(writer, Date().toString())
            }
        }
    }

    override fun update(
        keyName: String,
        keys: List<KeyboardKey>,
    ) {
        runCatching {
            val shortcutKeysPropertiesPath =
                appPathProvider
                    .resolve("shortcut-keys.properties", AppFileType.USER)

            val properties = Properties()

            InputStreamReader(shortcutKeysPropertiesPath.toFile().inputStream(), StandardCharsets.UTF_8)
                .use { inputStreamReader -> properties.load(inputStreamReader) }

            properties.setProperty(keyName, keys.joinToString("+") { "${it.code}" })

            FileOutputStream(shortcutKeysPropertiesPath.toFile()).use { fileOutputStream ->
                OutputStreamWriter(fileOutputStream, Charsets.UTF_8).use { writer ->
                    properties.store(writer, "Comments")
                }
            }
            _shortcutKeysCore.value = shortcutKeysLoader.load(shortcutKeysPropertiesPath)
        }.onFailure { e ->
            logger.error(e) { "Failed to update shortcut keys" }
        }
    }
}

internal fun migrateWorkbenchShortcuts(
    properties: Properties,
    defaults: Properties,
) {
    for ((key, oldDefault) in mapOf("paste_plain_text" to "", "show_main" to "42+3675+51")) {
        if (properties.getProperty(key) == oldDefault) properties.setProperty(key, defaults.getProperty(key))
    }
    properties.setProperty("workbench_shortcuts_v1", "true")
}
