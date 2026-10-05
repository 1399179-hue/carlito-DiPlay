package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The address the handset is told to dial back on.
 *
 * The receiver hands this value to the iPhone as `0x4301 wirelessIP` and the iPhone then opens the
 * AirPlay port on it. iOS cannot route an unqualified link-local IPv6 address, so a `fe80::` value
 * makes the session associate and then go quiet with `tcpAccepted=0`, the handset stuck at
 * `WiFi_discovery_or_AirPlay_TCP`.
 *
 * Fork fix (kept across the 0.2.12 merge): IPv4 wins whenever the interface has one, and the scoped
 * link-local address is only a fallback for a platform that never assigns a lease. That is the same
 * rule the hotspot backends already apply — `ManualHotspotReadiness.selectHotspotInterface` accepts
 * an IPv4 host address only, and `LocalOnlyHotspotManager` documents the same precedence.
 *
 * This intentionally diverges from upstream's "manual AP == Wi-Fi Direct" link-local parity. Wi-Fi
 * Direct keeps its own link-local path inside `WifiP2pGroupManager`; this helper is reachable only
 * from `ExistingWifiManager`, where the handset sits on a routed LAN with a normal IPv4 lease.
 */
internal fun wirelessHostAddress(addresses: List<InetAddress>, interfaceIndex: Int): InetAddress? =
    routableIpv4(addresses) ?: scopedLinkLocalIpv6(addresses, interfaceIndex)

/** Station LAN discovery must cover IPv4 multicast as well as scoped link-local IPv6. */
internal fun existingWifiHostAddresses(addresses: List<InetAddress>, interfaceIndex: Int): List<InetAddress> =
    listOfNotNull(routableIpv4(addresses), scopedLinkLocalIpv6(addresses, interfaceIndex))

/**
 * An IPv4 address the handset can dial without a scope: not loopback, not `169.254/16`, not
 * `0.0.0.0` and not a multicast group.
 */
internal fun routableIpv4(addresses: List<InetAddress>): InetAddress? = addresses.firstOrNull {
    it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
        !it.isAnyLocalAddress && !it.isMulticastAddress
}

/**
 * The interface's link-local IPv6 address, re-scoped to [interfaceIndex]. Null when the platform
 * never assigned one, or when there is no interface index to scope it to.
 */
internal fun scopedLinkLocalIpv6(addresses: List<InetAddress>, interfaceIndex: Int): Inet6Address? {
    if (interfaceIndex <= 0) return null
    val linkLocal = addresses.filterIsInstance<Inet6Address>()
        .firstOrNull { it.isLinkLocalAddress } ?: return null
    if (linkLocal.scopeId == interfaceIndex) return linkLocal
    return try {
        Inet6Address.getByAddress(null, linkLocal.address, interfaceIndex)
    } catch (_: Exception) {
        null
    }
}
