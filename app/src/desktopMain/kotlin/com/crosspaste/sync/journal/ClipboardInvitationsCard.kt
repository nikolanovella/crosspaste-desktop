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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.crosspaste.config.DesktopConfigManager
import com.crosspaste.sync.SyncManager
import com.crosspaste.ui.settings.SettingSectionCard
import com.crosspaste.ui.theme.AppUISize.medium
import com.crosspaste.ui.theme.AppUISize.small
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** Receiving-device consent stays in the existing themed Devices page. */
@Composable
internal fun ClipboardInvitationsCard() {
    val service = koinInject<ClipboardInvitationService>()
    val config = koinInject<DesktopConfigManager>()
    val sync = koinInject<SyncManager>()
    val invitations by service.invitations.collectAsState()
    val devices by sync.realTimeSyncRuntimeInfos.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf(false) }
    if (invitations.isEmpty() && !failure) return

    SettingSectionCard {
        Column(Modifier.fillMaxWidth().padding(medium), verticalArrangement = Arrangement.spacedBy(small)) {
            Text("Shared clipboard invitations", style = MaterialTheme.typography.titleMedium)
            invitations.forEach { invitation ->
                val name =
                    devices.firstOrNull { it.appInstanceId == invitation.inviter }?.getDeviceDisplayName()
                        ?: "a paired device"
                Text("Join the clipboard shared by $name", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Includes ${invitation.members.size} approved device(s). Use your current copy and paste preferences.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(small)) {
                    Button(enabled = busy == null, onClick = {
                        if (busy != null) return@Button
                        busy = invitation.id
                        failure = false
                        scope.launch {
                            try {
                                val contribute = config.getCurrentConfig().enablePasteboardListening
                                withContext(Dispatchers.IO) { service.accept(invitation.id, contribute) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                failure = true
                            } finally {
                                busy = null
                            }
                        }
                    }) { Text(if (busy == invitation.id) "Joining…" else "Join clipboard") }
                    TextButton(enabled = busy == null, onClick = {
                        if (busy != null) return@TextButton
                        busy = invitation.id
                        failure = false
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { service.decline(invitation.id) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                failure = true
                            } finally {
                                busy = null
                            }
                        }
                    }) { Text("Not now") }
                }
            }
            if (failure) {
                Text(
                    "Couldn't complete this invitation.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { failure = false }) { Text("Dismiss") }
            }
        }
    }
}
