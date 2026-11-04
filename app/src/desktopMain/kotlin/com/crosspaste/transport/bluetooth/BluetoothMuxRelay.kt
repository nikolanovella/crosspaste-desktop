package com.crosspaste.transport.bluetooth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Carries both peers' existing HTTP connections across one RFCOMM channel.
 *
 * The local listener binds loopback only. Remote OPEN frames can connect only to
 * the owning CrossPaste server port supplied by the app, never arbitrary hosts.
 * CrossPaste's existing pairing and authenticated/encrypted requests remain in
 * force above this relay. The framing handshake does NOT authenticate a device.
 * No instance is created automatically; native adapters must opt in explicitly.
 */
internal class BluetoothMuxRelay(
    private val input: InputStream,
    private val output: OutputStream,
    private val appPort: Int,
    private val connector: Boolean,
    private val closeTransport: () -> Unit,
) : Closeable {
    // Idle keep-alive reads must not consume the application's shared IO quota.
    // This elastic view has enough slots for the bounded stream pumps and reader.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(MAX_STREAMS * 2 + 4))
    private val writeMutex = Mutex()
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val streamSlots = Semaphore(MAX_STREAMS)
    private val nextId = AtomicInteger(if (connector) 1 else 2)
    private var lastRemoteId = 0
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val stopped = CompletableDeferred<Unit>()
    private val remoteHello = CompletableDeferred<Unit>()
    private val ready = CompletableDeferred<InetSocketAddress>()
    private var server: ServerSocket? = null

    private inner class Stream(
        val id: Int,
        val socket: Socket,
    ) {
        val inbound = Channel<ByteArray>(WINDOW)
        val credits = Semaphore(WINDOW)
        val outstanding = AtomicInteger()
        val ended = AtomicInteger()
        val closed = AtomicBoolean()
        val remoteFin = AtomicBoolean()
        val job = SupervisorJob(scope.coroutineContext[Job])
        val streamScope = CoroutineScope(scope.coroutineContext + job)
    }

    init {
        require(appPort in 1..65535)
    }

    suspend fun start(): InetSocketAddress {
        if (!started.compareAndSet(false, true)) return ready.await()
        scope.launch {
            try {
                send(BluetoothFrameCodec.Frame(BluetoothFrameCodec.HELLO, 0, hello(connector)))
                scope.launch { readFrames() }
                withTimeout(10.seconds) { remoteHello.await() }
                val listener = ServerSocket(0, MAX_STREAMS, InetAddress.getByName("127.0.0.1"))
                server = listener
                if (closed.get()) {
                    listener.close()
                    throw IOException("Bluetooth transport closed during startup")
                }
                ready.complete(listener.localSocketAddress as InetSocketAddress)
                acceptLocal(listener)
            } catch (error: Exception) {
                ready.completeExceptionally(error)
                close()
            }
        }
        return ready.await()
    }

    suspend fun awaitClosed() = stopped.await()

    private suspend fun send(frame: BluetoothFrameCodec.Frame) {
        writeMutex.withLock {
            if (closed.get()) throw IOException("Bluetooth transport is closed")
            BluetoothFrameCodec.write(output, frame)
        }
    }

    private suspend fun readFrames() {
        try {
            while (!closed.get()) {
                val frame = BluetoothFrameCodec.read(input) ?: throw IOException("Bluetooth peer disconnected")
                if (!remoteHello.isCompleted) {
                    if (frame.type != BluetoothFrameCodec.HELLO || !frame.payload.contentEquals(hello(!connector))) {
                        throw IOException("Incompatible Bluetooth transport handshake")
                    }
                    remoteHello.complete(Unit)
                    continue
                }
                when (frame.type) {
                    BluetoothFrameCodec.HELLO -> throw IOException("Repeated Bluetooth transport handshake")
                    BluetoothFrameCodec.OPEN -> openRemote(frame.streamId)
                    BluetoothFrameCodec.DATA -> {
                        val stream = streams[frame.streamId]
                        if (stream != null) {
                            if (frame.payload.isEmpty() ||
                                stream.remoteFin.get() ||
                                stream.inbound.trySend(frame.payload).isFailure
                            ) {
                                reset(stream)
                            }
                        }
                    }
                    BluetoothFrameCodec.FIN -> {
                        streams[frame.streamId]?.let { stream ->
                            if (stream.remoteFin.compareAndSet(false, true)) stream.inbound.close()
                        }
                    }
                    BluetoothFrameCodec.RESET -> streams[frame.streamId]?.let { dispose(it) }
                    BluetoothFrameCodec.CREDIT -> {
                        streams[frame.streamId]?.let { stream ->
                            if (stream.outstanding.getAndDecrement() <= 0) {
                                throw IOException("Invalid Bluetooth transport credit")
                            }
                            stream.credits.release()
                        }
                    }
                }
            }
        } catch (error: Exception) {
            remoteHello.completeExceptionally(error)
            ready.completeExceptionally(error)
        } finally {
            close()
        }
    }

    private suspend fun acceptLocal(listener: ServerSocket) {
        while (!closed.get()) {
            val socket = listener.accept()
            if (!streamSlots.tryAcquire()) {
                socket.close()
                continue
            }
            val id = nextId.getAndAdd(2)
            if (id <= 0) {
                streamSlots.release()
                socket.close()
                throw IOException("Bluetooth stream identifiers exhausted")
            }
            val stream = Stream(id, socket)
            streams[id] = stream
            if (closed.get()) {
                dispose(stream)
                return
            }
            send(BluetoothFrameCodec.Frame(BluetoothFrameCodec.OPEN, id))
            pump(stream)
        }
    }

    private suspend fun openRemote(id: Int) {
        if (id <= lastRemoteId || id % 2 == (if (connector) 1 else 0) || streams.containsKey(id)) {
            throw IOException("Invalid remote Bluetooth stream identifier")
        }
        lastRemoteId = id
        if (!streamSlots.tryAcquire()) {
            send(BluetoothFrameCodec.Frame(BluetoothFrameCodec.RESET, id))
            return
        }
        val stream = Stream(id, Socket())
        streams[id] = stream
        if (closed.get()) {
            dispose(stream)
            return
        }
        stream.streamScope.launch {
            try {
                stream.socket.connect(InetSocketAddress("127.0.0.1", appPort), 5000)
                pump(stream)
            } catch (_: Exception) {
                reset(stream)
            }
        }
    }

    private fun pump(stream: Stream) {
        stream.streamScope.launch {
            try {
                val buffer = ByteArray(BluetoothFrameCodec.MAX_PAYLOAD)
                val source = stream.socket.getInputStream()
                while (!stream.closed.get()) {
                    val count = source.read(buffer)
                    if (count == -1) break
                    if (count == 0) continue
                    stream.credits.acquire()
                    stream.outstanding.incrementAndGet()
                    send(BluetoothFrameCodec.Frame(BluetoothFrameCodec.DATA, stream.id, buffer.copyOf(count)))
                }
                send(BluetoothFrameCodec.Frame(BluetoothFrameCodec.FIN, stream.id))
                finished(stream)
            } catch (_: Exception) {
                reset(stream)
            }
        }
        stream.streamScope.launch {
            try {
                val destination = stream.socket.getOutputStream()
                for (bytes in stream.inbound) {
                    destination.write(bytes)
                    destination.flush()
                    send(BluetoothFrameCodec.Frame(BluetoothFrameCodec.CREDIT, stream.id))
                }
                if (!stream.socket.isClosed) stream.socket.shutdownOutput()
                finished(stream)
            } catch (_: Exception) {
                reset(stream)
            }
        }
    }

    private fun finished(stream: Stream) {
        if (stream.ended.incrementAndGet() == 2) dispose(stream)
    }

    private suspend fun reset(stream: Stream) {
        if (dispose(stream) && !closed.get()) {
            scope.launch { runCatching { send(BluetoothFrameCodec.Frame(BluetoothFrameCodec.RESET, stream.id)) } }
        }
    }

    private fun dispose(stream: Stream): Boolean {
        if (!stream.closed.compareAndSet(false, true)) return false
        streams.remove(stream.id, stream)
        streamSlots.release()
        stream.job.cancel()
        stream.inbound.cancel()
        runCatching { stream.socket.close() }
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready.completeExceptionally(IOException("Bluetooth transport closed"))
        remoteHello.completeExceptionally(IOException("Bluetooth transport closed"))
        runCatching { server?.close() }
        streams.values.forEach { dispose(it) }
        runCatching { closeTransport() }
        runCatching { input.close() }
        runCatching { output.close() }
        scope.cancel()
        stopped.complete(Unit)
    }

    companion object {
        private const val WINDOW = 4
        private const val MAX_STREAMS = 16

        private fun hello(connector: Boolean): ByteArray =
            "CROSSPASTE_BT/1/${if (connector) "connector" else "acceptor"}".toByteArray(Charsets.US_ASCII)
    }
}
