package com.crosspaste.transport.bluetooth

import com.crosspaste.app.AppInfo
import com.crosspaste.dto.sync.SyncInfo
import com.crosspaste.net.Server
import com.crosspaste.net.clientapi.SuccessResult
import com.crosspaste.net.clientapi.SyncClientApi
import com.crosspaste.net.ws.WsSessionManager
import com.crosspaste.platform.Platform
import com.crosspaste.secure.SecureStore
import com.crosspaste.sync.SyncManager
import com.crosspaste.utils.HostAndPort
import com.crosspaste.utils.PeerTransportRoutes
import com.crosspaste.utils.buildUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

internal data class BluetoothConnectionState(
    val active: Boolean = false,
    val message: String = "Bluetooth is off",
    val peerId: String? = null,
)

/** Optional link for an already paired CrossPaste device. Existing trust is never replaced. */
internal class BluetoothTransportController(
    private val platform: Platform,
    private val appInfo: AppInfo,
    private val secureStore: SecureStore,
    private val syncClientApi: SyncClientApi,
    private val syncManager: SyncManager,
    private val server: Lazy<Server>,
    private val wsSessionManager: WsSessionManager,
) : Closeable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(BluetoothConnectionState())
    val state = mutableState.asStateFlow()
    private var current: Connection? = null
    private val closed = AtomicBoolean()
    private val shutdownHook = Thread({ close() }, "CrossPaste-Bluetooth-cleanup")

    private class Connection(
        val peerId: String,
    ) {
        lateinit var job: Job

        @Volatile
        var session: NativeBluetoothSession? = null

        @Volatile
        var route: PeerTransportRoutes.Lease? = null
    }

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    fun connect(
        peerId: String,
        address: String?,
    ) {
        synchronized(lock) {
            check(!closed.get()) { "Bluetooth controller is closed" }
            stopLocked()
            val next = Connection(peerId)
            current = next
            publish(next, "Preparing Bluetooth")
            next.job = scope.launch(start = CoroutineStart.LAZY) { runConnection(next, address) }
            next.job.start()
        }
    }

    private suspend fun runConnection(
        connection: Connection,
        address: String?,
    ) {
        var session: NativeBluetoothSession? = null
        var route: PeerTransportRoutes.Lease? = null
        try {
            require(platform.isWindows() || platform.isMacos()) { "Bluetooth is available on Windows and Mac" }
            require(connection.peerId != appInfo.appInstanceId) { "Choose another device" }
            require(secureStore.existCryptPublicKey(connection.peerId)) { "Pair this device in CrossPaste first" }
            val appPort = withTimeout(10.seconds) { server.value.portFlow.first { it > 0 } }
            val resources = System.getProperty("compose.application.resources.dir")
            require(!resources.isNullOrBlank()) { "Use the packaged Bluetooth build" }
            val name = if (platform.isWindows()) "WindowsBluetoothTransport.exe" else "MacosBluetoothTransport"
            val executable = Path.of(resources, "bluetooth", name)
            require(Files.isRegularFile(executable)) { "Bluetooth adapter is not installed in this build" }
            session =
                NativeBluetoothSession.open(executable, appPort, platform.isWindows(), address) { status ->
                    when (status) {
                        BluetoothNativeState.CONNECTING ->
                            publish(
                                connection,
                                "Connecting to the paired Bluetooth device",
                            )
                        BluetoothNativeState.LISTENING -> publish(connection, "Waiting for the paired Windows device")
                        BluetoothNativeState.CONNECTED -> publish(connection, "Verifying CrossPaste device")
                        BluetoothNativeState.DISCONNECTED -> Unit
                    }
                }
            connection.session = session
            val endpoint = session.start()
            val result =
                syncClientApi.syncInfo(timeout = 30_000L) {
                    buildUrl(HostAndPort("127.0.0.1", endpoint.port))
                }
            val remote = (result as? SuccessResult)?.getResult<SyncInfo>()
            require(
                remote?.appInfo?.appInstanceId == connection.peerId,
            ) { "The Bluetooth device is not the selected peer" }
            // Metadata is a candidate identity only; the existing encrypted resolver
            // authenticates it with the saved key. Do not import keys or LAN addresses.
            synchronized(lock) {
                if (current !== connection) throw CancellationException("Bluetooth connection replaced")
                route = PeerTransportRoutes.registerLoopback(connection.peerId, endpoint.port)
                connection.route = route
            }
            // Force only this peer off its old LAN WebSocket so the encrypted
            // resolver establishes the replacement over the new route.
            wsSessionManager.closeSession(connection.peerId)
            syncManager.fastReconnect(connection.peerId)
            publish(connection, "Bluetooth link ready — CrossPaste is verifying sync")
            session.awaitClosed()
            publish(connection, "Bluetooth disconnected", active = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // No raw native stderr, device addresses or clipboard payloads in the UI.
            val message = if (error is IllegalArgumentException) error.message else "Bluetooth could not connect"
            publish(connection, message ?: "Bluetooth could not connect", active = false)
        } finally {
            route?.release()
            session?.close()
            synchronized(lock) {
                if (current === connection) {
                    current = null
                    syncManager.fastReconnect(connection.peerId)
                }
            }
        }
    }

    private fun publish(
        connection: Connection,
        message: String,
        active: Boolean = true,
    ) {
        synchronized(lock) {
            if (current ===
                connection
            ) {
                mutableState.value = BluetoothConnectionState(active, message, connection.peerId)
            }
        }
    }

    private fun stopLocked() {
        current?.let {
            current = null
            it.route?.release()
            it.session?.close()
            it.job.cancel()
            syncManager.fastReconnect(it.peerId)
        }
    }

    fun disconnect() {
        synchronized(lock) {
            stopLocked()
            mutableState.value = BluetoothConnectionState()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        disconnect()
        scope.cancel()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }
}
