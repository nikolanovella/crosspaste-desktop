package com.crosspaste.transport.bluetooth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.crosspaste.platform.Platform
import com.crosspaste.sync.SyncManager
import com.crosspaste.ui.settings.SettingSectionCard
import com.crosspaste.ui.theme.AppUISize.medium
import com.crosspaste.ui.theme.AppUISize.small
import org.koin.compose.koinInject

/** Lives in Devices, using the existing theme and cards rather than a second UI. */
@Composable
internal fun BluetoothDevicesCard() {
    val platform = koinInject<Platform>()
    if (!platform.isWindows() && !platform.isMacos()) return
    val controller = koinInject<BluetoothTransportController>()
    val syncManager = koinInject<SyncManager>()
    val devices by syncManager.realTimeSyncRuntimeInfos.collectAsState()
    val connection by controller.state.collectAsState()
    val candidates = devices.filter { if (platform.isWindows()) it.platform.isMacos() else it.platform.isWindows() }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = candidates.firstOrNull { it.appInstanceId == selectedId } ?: candidates.firstOrNull()
    var address by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    SettingSectionCard {
        Column(Modifier.fillMaxWidth().padding(medium), verticalArrangement = Arrangement.spacedBy(small)) {
            Text("Bluetooth · Experimental", style = MaterialTheme.typography.titleMedium)
            Text(
                "Use your paired Mac and Windows PC without Wi-Fi. Pair them in CrossPaste and in Bluetooth settings first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { menuOpen = true }, enabled = !connection.active && candidates.isNotEmpty()) {
                Text(selected?.getDeviceDisplayName() ?: "No paired desktop device")
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    candidates.forEach { device ->
                        DropdownMenuItem(
                            text = { Text(device.getDeviceDisplayName()) },
                            onClick = {
                                selectedId = device.appInstanceId
                                menuOpen = false
                            },
                        )
                    }
                }
            }
            if (platform.isWindows()) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Mac Bluetooth address") },
                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                    singleLine = true,
                    enabled = !connection.active,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(connection.message, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(small)) {
                Button(
                    enabled = !connection.active && selected != null && (!platform.isWindows() || address.isNotBlank()),
                    onClick = {
                        selected?.let {
                            controller.connect(it.appInstanceId, if (platform.isWindows()) address.trim() else null)
                        }
                    },
                ) {
                    Text(if (platform.isWindows()) "Connect" else "Listen for Windows")
                }
                TextButton(onClick = controller::disconnect, enabled = connection.active) { Text("Disconnect") }
            }
            Text(
                "Start listening on Mac, then connect from Windows. This does not change your recording or pasting switches.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
