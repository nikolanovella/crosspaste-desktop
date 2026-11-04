package com.crosspaste.transport.bluetooth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

internal enum class BluetoothNativeState { CONNECTING, LISTENING, CONNECTED, DISCONNECTED }

/** Owns only the helper process it starts. No system service or other app is touched. */
internal class NativeBluetoothSession internal constructor(
    private val process: Process,
    private val appPort: Int,
    private val connector: Boolean,
    private val onState: (BluetoothNativeState) -> Unit,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))
    private val connected = CompletableDeferred<Unit>()
    private val stopped = CompletableDeferred<Unit>()
    private val ready = CompletableDeferred<InetSocketAddress>()
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val nativeStopped = AtomicBoolean()

    @Volatile
    private var relay: BluetoothMuxRelay? = null

    init {
        scope.launch {
            try {
                val line = StringBuilder()
                process.errorStream.reader(Charsets.US_ASCII).use { reader ->
                    while (true) {
                        val character = reader.read()
                        if (character < 0) break
                        if (character == '\n'.code) {
                            acceptStatus(line.toString().trimEnd('\r'))
                            line.clear()
                        } else {
                            if (line.length >= 256) throw IOException("Invalid native Bluetooth status")
                            line.append(character.toChar())
                        }
                    }
                }
            } catch (error: Exception) {
                connected.completeExceptionally(error)
                close()
            }
        }
        scope.launch {
            try {
                val code = process.waitFor()
                connected.completeExceptionally(IOException("Bluetooth adapter exited before connecting (code $code)"))
            } finally {
                close()
            }
        }
    }

    private fun acceptStatus(status: String) {
        when {
            status == "BT_CONNECTED" -> connected.complete(Unit)
            status == "BT_LISTENING" -> publish(BluetoothNativeState.LISTENING)
            status.startsWith("BT_") && (status.contains("FAILED") || status.contains("UNAVAILABLE")) -> {
                // Never relay arbitrary native stderr, device addresses or payload.
                val code = status.substringAfter(':', "").toIntOrNull()
                connected.completeExceptionally(
                    IOException("Bluetooth connection unavailable${code?.let { " (code $it)" } ?: ""}"),
                )
            }
        }
    }

    suspend fun start(): InetSocketAddress {
        if (!started.compareAndSet(false, true)) return ready.await()
        try {
            if (connector) withTimeout(60.seconds) { connected.await() } else connected.await()
            if (closed.get()) throw IOException("Bluetooth adapter disconnected")
            val next = BluetoothMuxRelay(process.inputStream, process.outputStream, appPort, connector, ::stopNative)
            relay = next
            if (closed.get()) {
                next.close()
                throw IOException("Bluetooth adapter disconnected")
            }
            val endpoint = next.start()
            if (closed.get()) throw IOException("Bluetooth adapter disconnected")
            publish(BluetoothNativeState.CONNECTED)
            if (closed.get()) {
                publish(BluetoothNativeState.DISCONNECTED)
                throw IOException("Bluetooth adapter disconnected")
            }
            scope.launch {
                next.awaitClosed()
                close()
            }
            ready.complete(endpoint)
            return endpoint
        } catch (error: Exception) {
            ready.completeExceptionally(error)
            close()
            throw error
        }
    }

    suspend fun awaitClosed() = stopped.await()

    private fun publish(state: BluetoothNativeState) {
        runCatching { onState(state) }
    }

    private fun stopNative() {
        if (!nativeStopped.compareAndSet(false, true)) return
        runCatching { process.outputStream.close() }
        runCatching { process.destroy() }
        // Covers an adapter stuck inside a platform call. The owned child alone
        // is terminated; this does not enumerate or kill processes by name.
        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS).execute {
            if (process.isAlive) runCatching { process.destroyForcibly() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        relay?.close()
        stopNative()
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        connected.completeExceptionally(IOException("Bluetooth session closed"))
        ready.completeExceptionally(IOException("Bluetooth session closed"))
        publish(BluetoothNativeState.DISCONNECTED)
        stopped.complete(Unit)
        scope.cancel()
    }

    companion object {
        suspend fun open(
            executable: Path,
            appPort: Int,
            connector: Boolean,
            address: String? = null,
            onState: (BluetoothNativeState) -> Unit = {},
        ): NativeBluetoothSession {
            require(appPort in 1..65535)
            val arguments =
                if (connector) {
                    val normalized =
                        address?.replace('-', ':')?.uppercase()
                            ?: throw IllegalArgumentException("Choose a paired Bluetooth device")
                    require(
                        normalized.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")),
                    ) { "Invalid Bluetooth device address" }
                    require(
                        normalized != "00:00:00:00:00:00" && normalized != "FF:FF:FF:FF:FF:FF",
                    ) { "Invalid Bluetooth device address" }
                    listOf("--connect", normalized)
                } else {
                    require(address == null)
                    listOf("--listen")
                }
            if (connector) onState(BluetoothNativeState.CONNECTING)
            var child: Process? = null
            try {
                withContext(Dispatchers.IO) {
                    child =
                        ProcessBuilder(listOf(executable.toAbsolutePath().toString()) + arguments)
                            .directory(executable.toAbsolutePath().parent.toFile())
                            .start()
                }
                return NativeBluetoothSession(checkNotNull(child), appPort, connector, onState)
            } catch (error: Exception) {
                child?.destroyForcibly()
                throw error
            }
        }
    }
}
