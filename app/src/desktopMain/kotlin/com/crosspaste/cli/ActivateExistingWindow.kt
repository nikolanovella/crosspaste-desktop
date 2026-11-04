package com.crosspaste.cli

import com.crosspaste.utils.ioDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Relaunching the desktop app activates the existing instance, preserving its profile. */
suspend fun activateExistingWindow(endpointFile: CliEndpointFile): Boolean =
    withContext(ioDispatcher) {
        runCatching {
            val endpoint = Json.decodeFromString<CliEndpoint>(Files.readString(endpointFile.getPath()))
            withTimeoutOrNull(2.seconds) {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
                    socket.configureBlocking(false)
                    socket.connect(UnixDomainSocketAddress.of(endpoint.socketPath))
                    while (!socket.finishConnect()) delay(10.milliseconds)
                    val request =
                        ByteBuffer.wrap(
                            (
                                "POST /cli/ui/open HTTP/1.1\r\nHost: localhost\r\n" +
                                    "Content-Length: 0\r\nConnection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                        )
                    while (request.hasRemaining()) {
                        socket.write(request)
                        if (request.hasRemaining()) delay(10.milliseconds)
                    }
                    val response = ByteBuffer.allocate(256)
                    while (response.position() < 16) {
                        if (socket.read(response) < 0) break
                        delay(10.milliseconds)
                    }
                    String(response.array(), 0, response.position(), Charsets.US_ASCII).startsWith("HTTP/1.1 202")
                }
            } ?: false
        }.getOrDefault(false)
    }
