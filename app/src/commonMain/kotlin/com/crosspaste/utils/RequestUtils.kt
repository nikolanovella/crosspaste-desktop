package com.crosspaste.utils

import io.ktor.http.*

data class HostAndPort(
    val host: String,
    val port: Int,
    val peerId: String? = null,
)

fun URLBuilder.buildUrl(hostAndPort: HostAndPort) {
    val endpoint = PeerTransportRoutes.resolve(hostAndPort)
    this.protocol = URLProtocol.HTTP
    this.host = endpoint.host
    this.port = endpoint.port
}

fun URLBuilder.buildUrl(vararg paths: String) {
    this.path(*paths)
}
