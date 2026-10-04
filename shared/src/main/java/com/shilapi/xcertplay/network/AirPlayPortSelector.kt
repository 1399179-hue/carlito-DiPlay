package com.shilapi.xcertplay.network

import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Binds the AirPlay control listener, falling back when the preferred port is already taken.
 *
 * Some head units ship a factory CarPlay daemon that permanently listens on the default AirPlay
 * port (7000) on every interface, so binding DiPlay's listener fails with EADDRINUSE. The bound
 * port is advertised to the iPhone through Bonjour and iAP2, so any free port works.
 */
object AirPlayPortSelector {
    data class BoundServers(val port: Int, val servers: List<ServerSocket>)

    /** Ports tried, in order, after the preferred port; an ephemeral port is the last resort. */
    val FALLBACK_PORTS: IntRange = 7001..7010

    fun bind(
        address: InetAddress,
        preferredPort: Int,
        fallbackPorts: Iterable<Int> = FALLBACK_PORTS,
        onFallback: (busyPort: Int, boundPort: Int) -> Unit = { _, _ -> },
    ): ServerSocket {
        tryBind(address, preferredPort)?.let { return it }
        for (port in fallbackPorts) {
            if (port == preferredPort) continue
            tryBind(address, port)?.let { server ->
                return reportFallback(server, preferredPort, onFallback)
            }
        }
        return reportFallback(bindPort(address, 0), preferredPort, onFallback)
    }

    /** Binds one common port on as many candidate addresses as possible. */
    fun bindAll(
        addresses: List<InetAddress>,
        preferredPort: Int,
        fallbackPorts: Iterable<Int> = FALLBACK_PORTS,
        onFallback: (busyPort: Int, boundPort: Int) -> Unit = { _, _ -> },
    ): BoundServers {
        val candidates = addresses.distinctBy { it.hostAddress }
        require(candidates.isNotEmpty()) { "addresses must not be empty" }
        var best: BoundServers? = null
        try {
            for (port in (listOf(preferredPort) + fallbackPorts.filter { it != preferredPort }).distinct()) {
                val attempt = bindAvailable(candidates, port)
                if (attempt.servers.size > (best?.servers?.size ?: 0)) {
                    best?.servers?.forEach { it.close() }
                    best = attempt
                } else {
                    attempt.servers.forEach { it.close() }
                }
                if (best?.servers?.size == candidates.size) break
            }
            if (best == null) {
                val first = bindPort(candidates.first(), 0)
                val port = first.localPort
                best = BoundServers(port, listOf(first) + bindAvailable(candidates.drop(1), port).servers)
            }
            if (best.port != preferredPort) onFallback(preferredPort, best.port)
            return best
        } catch (error: Throwable) {
            best?.servers?.forEach { runCatching { it.close() } }
            throw error
        }
    }

    private fun bindAvailable(addresses: List<InetAddress>, port: Int): BoundServers {
        val servers = addresses.mapNotNull { address -> tryBind(address, port) }
        return BoundServers(port, servers)
    }

    private fun tryBind(address: InetAddress, port: Int): ServerSocket? = try {
        bindPort(address, port)
    } catch (_: BindException) {
        null
    }

    private fun bindPort(address: InetAddress, port: Int): ServerSocket {
        val server = ServerSocket()
        return try {
            server.bind(InetSocketAddress(address, port))
            server
        } catch (error: Throwable) {
            closeAfterFailure(server, error)
            throw error
        }
    }

    private fun reportFallback(
        server: ServerSocket,
        preferredPort: Int,
        onFallback: (Int, Int) -> Unit,
    ): ServerSocket = try {
        onFallback(preferredPort, server.localPort)
        server
    } catch (error: Throwable) {
        // Ownership transfers to the caller only after notification succeeds.
        closeAfterFailure(server, error)
        throw error
    }

    private fun closeAfterFailure(server: ServerSocket, error: Throwable) {
        try {
            server.close()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
    }
}
