package com.crosspaste.transport.bluetooth

import com.crosspaste.test.IntegrationTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@IntegrationTest
class BluetoothMuxRelayTest {
    private class ApplicationServer(
        private val marker: Byte,
    ) : Closeable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort

        init {
            scope.launch {
                try {
                    while (!server.isClosed) {
                        val socket = server.accept()
                        sockets.add(socket)
                        scope.launch {
                            try {
                                socket.use {
                                    val request = it.getInputStream().readBytes()
                                    it.getOutputStream().apply {
                                        write(marker.toInt())
                                        write(request.reversedArray())
                                        flush()
                                    }
                                }
                            } finally {
                                sockets.remove(socket)
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Normal fixture shutdown closes accept().
                }
            }
        }

        override fun close() {
            server.close()
            sockets.forEach { it.close() }
            scope.cancel()
        }
    }

    private class PairRelay(
        firstPort: Int,
        secondPort: Int,
        sameRoles: Boolean = false,
    ) : Closeable {
        private val linkServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val firstSocket = Socket("127.0.0.1", linkServer.localPort)
        private val secondSocket = linkServer.accept()
        val first =
            BluetoothMuxRelay(
                firstSocket.getInputStream(),
                firstSocket.getOutputStream(),
                firstPort,
                true,
            ) { firstSocket.close() }
        val second =
            BluetoothMuxRelay(secondSocket.getInputStream(), secondSocket.getOutputStream(), secondPort, sameRoles) {
                secondSocket.close()
            }

        override fun close() {
            first.close()
            second.close()
            linkServer.close()
        }
    }

    private fun request(
        endpoint: InetSocketAddress,
        payload: ByteArray,
    ): ByteArray =
        Socket().use {
            it.soTimeout = 15000
            it.connect(endpoint, 5000)
            it.getOutputStream().write(payload)
            it.shutdownOutput()
            it.getInputStream().readBytes()
        }

    @Test
    fun `concurrent requests flow both ways past the credit window and preserve half closes`(): Unit =
        runBlocking {
            withTimeout(25.seconds) {
                ApplicationServer(11).use { appA ->
                    ApplicationServer(22).use { appB ->
                        PairRelay(appA.port, appB.port).use { pair ->
                            val first = async { pair.first.start() }
                            val second = async { pair.second.start() }
                            val endpointA = first.await()
                            val endpointB = second.await()
                            assertTrue(endpointA.address.isLoopbackAddress)
                            assertTrue(endpointB.address.isLoopbackAddress)
                            assertEquals(endpointA, pair.first.start())
                            (0 until 12)
                                .map { index ->
                                    async(Dispatchers.IO) {
                                        val payload = ByteArray(200_000 + index) { (it * 31 + index).toByte() }
                                        val endpoint = if (index % 2 == 0) endpointA else endpointB
                                        val marker: Byte = if (index % 2 == 0) 22 else 11
                                        assertContentEquals(
                                            byteArrayOf(marker) + payload.reversedArray(),
                                            request(endpoint, payload),
                                        )
                                    }
                                }.awaitAll()
                        }
                    }
                }
            }
        }

    @Test
    fun `same role peers are rejected before exposing a local relay endpoint`(): Unit =
        runBlocking {
            withTimeout(15.seconds) {
                PairRelay(9, 9, sameRoles = true).use { pair ->
                    val first = async { runCatching { pair.first.start() } }
                    val second = async { runCatching { pair.second.start() } }
                    assertTrue(first.await().exceptionOrNull() is IOException)
                    assertTrue(second.await().exceptionOrNull() is IOException)
                }
            }
        }

    @Test
    fun `closing relay terminates pending application socket without waiting for a response`(): Unit =
        runBlocking {
            withTimeout(15.seconds) {
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { silentApp ->
                    PairRelay(9, silentApp.localPort).use { pair ->
                        val first = async { pair.first.start() }
                        val second = async { pair.second.start() }
                        val endpoint = first.await()
                        second.await()
                        val accepted = CompletableDeferred<Socket>()
                        val accept = launch(Dispatchers.IO) { accepted.complete(silentApp.accept()) }
                        Socket().use { client ->
                            client.soTimeout = 5000
                            client.connect(endpoint)
                            client.getOutputStream().write(7)
                            accepted.await().use {
                                pair.first.close()
                                pair.first.awaitClosed()
                                assertEquals(-1, client.getInputStream().read())
                            }
                        }
                        accept.join()
                    }
                }
            }
        }
}
