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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.crosspaste.platform.Platform
import com.crosspaste.sync.SyncManager
import com.crosspaste.ui.settings.SettingSectionCard
import com.crosspaste.ui.theme.AppUISize.medium
import com.crosspaste.ui.theme.AppUISize.small
import com.sun.jna.platform.win32.Shell32
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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

    val scope = rememberCoroutineScope()
    var pairedDevices by remember { mutableStateOf<List<KnownBluetoothDevice>>(emptyList()) }
    var deviceMenuOpen by remember { mutableStateOf(false) }
    var inspecting by remember { mutableStateOf(false) }
    var inspectionMessage by remember { mutableStateOf("") }

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
                TextButton(
                    enabled = !connection.active && !inspecting,
                    onClick = {
                        inspecting = true
                        scope.launch {
                            try {
                                pairedDevices = inspectPairedWindowsBluetoothDevices()
                                inspectionMessage =
                                    if (pairedDevices.isEmpty()) "No paired Bluetooth devices found" else ""
                                deviceMenuOpen = pairedDevices.isNotEmpty()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                inspectionMessage = "Could not read paired devices. You can still enter the address."
                            } finally {
                                inspecting = false
                            }
                        }
                    },
                ) {
                    Text(if (inspecting) "Reading paired devices…" else "Choose a paired Bluetooth device")
                    DropdownMenu(expanded = deviceMenuOpen, onDismissRequest = { deviceMenuOpen = false }) {
                        pairedDevices.forEach { device ->
                            DropdownMenuItem(
                                text = { Text(device.name.ifBlank { device.address }) },
                                onClick = {
                                    address = device.address
                                    deviceMenuOpen = false
                                },
                            )
                        }
                    }
                }
                TextButton(
                    enabled = !connection.active,
                    onClick = {
                        val opened =
                            runCatching {
                                val result =
                                    Shell32.INSTANCE.ShellExecute(
                                        null,
                                        "open",
                                        "ms-settings:bluetooth",
                                        null,
                                        null,
                                        1,
                                    )
                                result.toLong() > 32
                            }.getOrDefault(false)
                        inspectionMessage =
                            if (opened) {
                                "Pair in Windows Settings, then choose the paired device here."
                            } else {
                                "Open Bluetooth & devices in Windows Settings to pair."
                            }
                    },
                ) {
                    Text("Open Windows Bluetooth settings")
                }
                if (inspectionMessage.isNotEmpty()) Text(inspectionMessage, style = MaterialTheme.typography.bodySmall)
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
