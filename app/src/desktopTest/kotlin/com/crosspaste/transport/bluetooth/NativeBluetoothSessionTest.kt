package com.crosspaste.transport.bluetooth

import com.crosspaste.test.IntegrationTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

@IntegrationTest
class NativeBluetoothSessionTest {
    private class OwnedProcess(
        private val socket: Socket,
        status: String,
    ) : Process() {
        private val exited = CountDownLatch(1)
        private val errors = ByteArrayInputStream(status.toByteArray(Charsets.US_ASCII))
        val destroyCount = AtomicInteger()

        override fun getInputStream() = socket.getInputStream()

        override fun getOutputStream() = socket.getOutputStream()

        override fun getErrorStream() = errors

        override fun waitFor(): Int {
            exited.await()
            return 0
        }

        override fun exitValue(): Int {
            if (isAlive) throw IllegalThreadStateException()
            return 0
        }

        override fun isAlive() = exited.count != 0L

        override fun destroy() {
            destroyCount.incrementAndGet()
            socket.close()
            exited.countDown()
        }
    }

    private fun pair(): Pair<Socket, Socket> =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val first = Socket("127.0.0.1", listener.localPort)
            first to listener.accept()
        }

    @Test
    fun `owned native pipes negotiate the relay and closing tears down only the owned process`(): Unit =
        runBlocking {
            withTimeout(20.seconds) {
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { app ->
                    val (native, remote) = pair()
                    val process = OwnedProcess(native, "BT_CONNECTED\n")
                    val states = CopyOnWriteArrayList<BluetoothNativeState>()
                    NativeBluetoothSession(process, 9, true, states::add).use { session ->
                        BluetoothMuxRelay(
                            remote.getInputStream(),
                            remote.getOutputStream(),
                            app.localPort,
                            false,
                        ) { remote.close() }.use { peer ->
                            val remoteStart = async { peer.start() }
                            val endpoint = session.start()
                            remoteStart.await()
                            assertEquals(endpoint, session.start())
                            val server =
                                async(Dispatchers.IO) {
                                    app.accept().use { socket ->
                                        val bytes = socket.getInputStream().readBytes()
                                        socket.getOutputStream().write(bytes.reversedArray())
                                    }
                                }
                            val payload = ByteArray(100_000) { (it * 17).toByte() }
                            val result =
                                async(Dispatchers.IO) {
                                    Socket().use { socket ->
                                        socket.soTimeout = 10000
                                        socket.connect(endpoint)
                                        socket.getOutputStream().write(payload)
                                        socket.shutdownOutput()
                                        socket.getInputStream().readBytes()
                                    }
                                }
                            assertContentEquals(payload.reversedArray(), result.await())
                            server.await()
                            session.close()
                            session.awaitClosed()
                            peer.awaitClosed()
                            assertFalse(process.isAlive)
                            assertEquals(1, process.destroyCount.get())
                            assertEquals(BluetoothNativeState.CONNECTED, states.first())
                            assertEquals(BluetoothNativeState.DISCONNECTED, states.last())
                        }
                    }
                }
            }
        }

    @Test
    fun `cancelling while waiting for native connection destroys the helper`(): Unit =
        runBlocking {
            withTimeout(10.seconds) {
                val (native, remote) = pair()
                remote.use {
                    val process = OwnedProcess(native, "BT_LISTENING\n")
                    NativeBluetoothSession(process, 9, false) {}.use { session ->
                        val start = async(start = CoroutineStart.UNDISPATCHED) { session.start() }
                        start.cancelAndJoin()
                        session.awaitClosed()
                        assertFalse(process.isAlive)
                        assertEquals(1, process.destroyCount.get())
                    }
                }
            }
        }

    @Test
    fun `invalid native status is bounded and callback errors cannot prevent cleanup`(): Unit =
        runBlocking {
            withTimeout(10.seconds) {
                val (native, remote) = pair()
                remote.use {
                    val process = OwnedProcess(native, "x".repeat(257) + "\n")
                    val owned =
                        NativeBluetoothSession(process, 9, true) { throw IllegalStateException("fixture listener") }
                    owned.use { session ->
                        assertFailsWith<IOException> { session.start() }
                        session.awaitClosed()
                        assertFalse(process.isAlive)
                        assertEquals(1, process.destroyCount.get())
                    }
                }
            }
        }
}
