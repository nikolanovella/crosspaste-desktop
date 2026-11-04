package com.crosspaste.utils

import io.ktor.http.URLBuilder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class PeerTransportRoutesTest {
    @Test
    fun `only the explicit peer is routed and original LAN endpoint stays immutable`() {
        val original = HostAndPort("192.0.2.10", 13129, "route-test-a")
        val lease = PeerTransportRoutes.registerLoopback("route-test-a", 23456)
        try {
            val url = URLBuilder().apply { buildUrl(original) }
            assertEquals("127.0.0.1", url.host)
            assertEquals(23456, url.port)
            assertEquals("192.0.2.10", original.host)
            assertEquals(13129, original.port)
            val other = original.copy(peerId = "route-test-b")
            assertSame(other, PeerTransportRoutes.resolve(other))
            val unknown = original.copy(peerId = null)
            assertSame(unknown, PeerTransportRoutes.resolve(unknown))
        } finally {
            lease.release()
        }
        assertSame(original, PeerTransportRoutes.resolve(original))
    }

    @Test
    fun `closing an old transport cannot delete a replacement route`() {
        val first = PeerTransportRoutes.registerLoopback("route-test-replace", 23457)
        val second = PeerTransportRoutes.registerLoopback("route-test-replace", 23458)
        try {
            first.release()
            assertEquals(23458, PeerTransportRoutes.resolve(HostAndPort("old", 1, "route-test-replace")).port)
        } finally {
            first.release()
            second.release()
        }
        assertFalse(PeerTransportRoutes.hasRoute("route-test-replace"))
    }

    @Test
    fun `invalid identity or local port cannot register a route`() {
        assertFailsWith<IllegalArgumentException> { PeerTransportRoutes.registerLoopback(" ", 1) }
        assertFailsWith<IllegalArgumentException> { PeerTransportRoutes.registerLoopback("route-test-invalid", 0) }
        assertFailsWith<IllegalArgumentException> { PeerTransportRoutes.registerLoopback("route-test-invalid", 65536) }
        assertFalse(PeerTransportRoutes.hasRoute("route-test-invalid"))
    }

    @Test
    fun `Bluetooth timeouts do not change LAN or unrelated loopback requests`() {
        val lease = PeerTransportRoutes.registerLoopback("route-test-timeout", 23459)
        try {
            assertEquals(30_000L, PeerTransportRoutes.requestTimeoutMillis("127.0.0.1", 23459, 3000))
            assertEquals(300_000L, PeerTransportRoutes.requestTimeoutMillis("127.0.0.1", 23459, 3000, binary = true))
            assertEquals(400_000L, PeerTransportRoutes.requestTimeoutMillis("127.0.0.1", 23459, 400_000))
            assertEquals(3000L, PeerTransportRoutes.requestTimeoutMillis("192.0.2.10", 23459, 3000))
            assertEquals(3000L, PeerTransportRoutes.requestTimeoutMillis("127.0.0.1", 23460, 3000))
        } finally {
            lease.release()
        }
        assertEquals(3000L, PeerTransportRoutes.requestTimeoutMillis("127.0.0.1", 23459, 3000))
    }
}
