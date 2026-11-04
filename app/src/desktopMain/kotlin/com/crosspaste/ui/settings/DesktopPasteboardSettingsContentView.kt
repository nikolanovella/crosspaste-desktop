package com.crosspaste.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.Block
import com.crosspaste.config.DesktopConfigManager
import com.crosspaste.platform.Platform
import com.crosspaste.ui.LocalThemeExtState
import com.crosspaste.ui.NavigationManager
import com.crosspaste.ui.SourceControl
import com.crosspaste.ui.base.IconData
import com.crosspaste.ui.theme.AppUISize.xxxxLarge
import org.koin.compose.koinInject

@Composable
fun DesktopPasteboardSettingsContentView(platform: Platform) {
    val isWindows = remember { platform.isWindows() }
    val configManager = koinInject<DesktopConfigManager>()
    val config by configManager.config.collectAsState()
    PasteboardSettingsContentView(
        recordingTitle = "add_my_copies",
        recordingSubtitle = "add_my_copies_desc",
        recordingExtContent = {
            ClipboardSourcesRow()
        },
        pastingExtContent = {
            HorizontalDivider(modifier = Modifier.padding(start = xxxxLarge))
            SettingListSwitchItem(
                title = "use_shared_clipboard",
                subtitle = "use_shared_clipboard_desc",
                checked = config.useSharedClipboardForPaste,
            ) { configManager.updateConfig("useSharedClipboardForPaste", it) }
            if (isWindows) {
                WindowsPasteboardSettingsContentView()
            }
        },
    )
}

/**
 * Per-app exclusion is the fine-grained counterpart of the monitoring switch,
 * so it lives in the Recording card rather than under Extension.
 */
@Composable
private fun ClipboardSourcesRow() {
    val navigationManager = koinInject<NavigationManager>()
    val themeExt = LocalThemeExtState.current

    HorizontalDivider(modifier = Modifier.padding(start = xxxxLarge))
    SettingListItem(
        title = "source_control_settings",
        subtitle = "source_control_settings_desc",
        icon = IconData(MaterialSymbols.Rounded.Block, themeExt.redIconColor),
    ) {
        navigationManager.navigate(SourceControl)
    }
}
