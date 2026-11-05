package com.crosspaste.sync.journal

import com.crosspaste.Database
import com.crosspaste.secure.SecureStore
import com.crosspaste.utils.DateUtils.nowEpochMilliseconds
import com.crosspaste.utils.getCodecsUtils
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

data class ClipboardSetupConnection(
    val peer: String,
    val state: ClipboardInvitationState,
)

data class ClipboardSetupState(
    val clipboard: String? = null,
    val enabled: Boolean = false,
    val members: List<String> = emptyList(),
    val connections: List<ClipboardSetupConnection> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

/** Local setup actions only. A transport acknowledgement never substitutes for recipient consent. */
class ClipboardSetupController(
    private val database: Database,
    private val localDevice: String,
    private val secureStore: SecureStore,
    private val members: ClipboardMembershipStore,
    private val sender: ClipboardInvitationSender,
    private val recording: () -> Boolean,
    private val now: () -> Long = ::nowEpochMilliseconds,
) {
    private data class Pending(
        val offer: ClipboardInvitation,
        val created: Long,
    )

    private val mutex = Mutex()
    private val offers = mutableMapOf<String, Pending>()
    private val connections = mutableMapOf<String, ClipboardInvitationState>()
    private val mutableState = MutableStateFlow(ClipboardSetupState())
    val state = mutableState.asStateFlow()

    private fun refreshLocal() {
        val workspaces = database.sharedClipboardDatabaseQueries.getWorkspaces().executeAsList()
        require(workspaces.size <= 1) { "Multiple clipboards require explicit selection" }
        val workspace = workspaces.singleOrNull()
        mutableState.value =
            mutableState.value.copy(
                clipboard = workspace?.clipboardId,
                enabled = workspace?.enabled == true,
                members =
                    workspace
                        ?.let {
                            members.memberKeys(it.clipboardId).map { member ->
                                member.deviceId
                            }
                        }.orEmpty(),
                connections = connections.map { ClipboardSetupConnection(it.key, it.value) },
            )
    }

    private suspend fun action(
        failure: String,
        block: suspend () -> Unit,
    ) {
        if (!mutex.tryLock()) return
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        try {
            block()
            refreshLocal()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = failure)
            try {
                refreshLocal()
            } catch (_: Exception) {
                // Keep last valid state.
            }
        } finally {
            mutableState.value = mutableState.value.copy(busy = false)
            mutex.unlock()
        }
    }

    suspend fun refresh() =
        action("Couldn't refresh shared clipboard status.") {
            refreshLocal()
            for ((peer, pending) in offers.toMap()) {
                if (connections[peer] != ClipboardInvitationState.PENDING) continue
                if (now() - pending.created >= 10 * 60 * 1000L) {
                    connections[peer] = ClipboardInvitationState.EXPIRED
                    offers.remove(peer)
                } else {
                    connections[peer] = sender.check(pending.offer)
                }
            }
        }

    suspend fun create() =
        action("Couldn't set up the shared clipboard.") {
            refreshLocal()
            if (mutableState.value.clipboard != null) return@action
            val clipboard = "shared-" + getCodecsUtils().base64Encode(CryptographyRandom.nextBytes(18))
            members.createClipboard(
                clipboard,
                localDevice,
                secureStore.secureKeyPair.signKeyPair.publicKey,
                recording(),
            )
        }

    suspend fun resume() =
        action("Couldn't resume this clipboard.") {
            refreshLocal()
            val clipboard = requireNotNull(mutableState.value.clipboard)
            val workspace = database.sharedClipboardDatabaseQueries.getWorkspace(clipboard).executeAsOne()
            requireNotNull(members.signingKey(clipboard, localDevice))
            database.sharedClipboardDatabaseQueries.putWorkspace(clipboard, true, workspace.contributeLocal)
        }

    suspend fun connect(peer: String) =
        action("Couldn't reach that device. Check its CrossPaste version and connection, then try again.") {
            refreshLocal()
            val current = mutableState.value
            require(current.enabled && peer != localDevice && secureStore.existCryptPublicKey(peer))
            require(peer !in current.members)
            sender.send(requireNotNull(current.clipboard), peer) { offer ->
                offers[peer] = Pending(offer, now())
                connections[peer] = ClipboardInvitationState.PENDING
                refreshLocal()
            }
        }
}
