package com.crosspaste.utils

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Ephemeral peer routes. Never persisted into a device's advertised LAN addresses. */
object PeerTransportRoutes {
    private val lock = SynchronizedObject()
    private val routes = mutableMapOf<String, Lease>()

    class Lease internal constructor(
        val peerId: String,
        val port: Int,
    ) {
        /** A stale session cannot remove a newer session's route. */
        fun release() {
            synchronized(lock) {
                if (routes[peerId] === this) routes.remove(peerId)
            }
        }
    }

    fun registerLoopback(
        peerId: String,
        port: Int,
    ): Lease {
        require(peerId.isNotBlank()) { "A transport route requires an explicit peer identity" }
        require(port in 1..65535) { "Invalid local transport port" }
        return synchronized(lock) {
            Lease(peerId, port).also { routes[peerId] = it }
        }
    }

    fun resolve(endpoint: HostAndPort): HostAndPort =
        synchronized(lock) {
            endpoint.peerId?.let(routes::get)?.let {
                HostAndPort("127.0.0.1", it.port, endpoint.peerId)
            } ?: endpoint
        }

    fun hasRoute(peerId: String): Boolean = synchronized(lock) { routes.containsKey(peerId) }

    fun requestTimeoutMillis(
        host: String,
        port: Int,
        requested: Long,
        binary: Boolean = false,
    ): Long =
        synchronized(lock) {
            if (host == "127.0.0.1" && routes.values.any { it.port == port }) {
                maxOf(requested, if (binary) 300_000L else 30_000L)
            } else {
                requested
            }
        }
}
