package com.crosspaste.sync.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.crosspaste.app.AppInfo
import com.crosspaste.db.sync.SyncState
import com.crosspaste.sync.SyncManager
import com.crosspaste.ui.settings.SettingSectionCard
import com.crosspaste.ui.theme.AppUISize.medium
import com.crosspaste.ui.theme.AppUISize.small
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** Extends the existing Devices page and its theme; no separate setup window. */
@Composable
internal fun ClipboardSetupCard() {
    val controller = koinInject<ClipboardSetupController>()
    val sync = koinInject<SyncManager>()
    val local = koinInject<AppInfo>().appInstanceId
    val state by controller.state.collectAsState()
    val devices by sync.realTimeSyncRuntimeInfos.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) {
        while (isActive) {
            withContext(Dispatchers.IO) { controller.refresh() }
            delay(2000)
        }
    }
    SettingSectionCard {
        Column(Modifier.fillMaxWidth().padding(medium), verticalArrangement = Arrangement.spacedBy(small)) {
            Text("Shared clipboard", style = MaterialTheme.typography.titleMedium)
            Text(
                "One clipboard, available on your connected devices. Your copy and paste choices stay in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.clipboard == null) {
                Button(
                    enabled = !state.busy,
                    onClick = { scope.launch { withContext(Dispatchers.IO) { controller.create() } } },
                ) {
                    Text("Set up shared clipboard")
                }
            } else if (!state.enabled) {
                Text("Shared history is paused on this device.", style = MaterialTheme.typography.bodyMedium)
                Button(
                    enabled = !state.busy,
                    onClick = { scope.launch { withContext(Dispatchers.IO) { controller.resume() } } },
                ) {
                    Text("Resume shared history")
                }
            } else {
                Text("${state.members.size} devices in this clipboard", style = MaterialTheme.typography.bodyMedium)
                val candidates = devices.filter { it.appInstanceId != local }
                candidates.forEach { device ->
                    val peer = device.appInstanceId
                    val connection = state.connections.firstOrNull { it.peer == peer }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(device.getDeviceDisplayName(), style = MaterialTheme.typography.bodyMedium)
                            val label =
                                when {
                                    peer in state.members ->
                                        if (device.connectState ==
                                            SyncState.CONNECTED
                                        ) {
                                            "Connected"
                                        } else {
                                            "Offline"
                                        }
                                    connection?.state == ClipboardInvitationState.PENDING -> {
                                        "Waiting for the device to accept"
                                    }
                                    connection?.state == ClipboardInvitationState.DECLINED -> "Connection declined"
                                    connection?.state == ClipboardInvitationState.EXPIRED -> {
                                        "Connection request expired"
                                    }
                                    else -> "Not connected to this clipboard"
                                }
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (peer !in state.members && connection?.state != ClipboardInvitationState.PENDING) {
                            TextButton(
                                enabled = !state.busy && device.connectState == SyncState.CONNECTED,
                                onClick = { scope.launch { withContext(Dispatchers.IO) { controller.connect(peer) } } },
                            ) {
                                Text("Connect")
                            }
                        }
                    }
                }
                if (candidates.isEmpty()) {
                    Text(
                        "Pair another device below to connect it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            state.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
