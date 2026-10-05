package com.shilapi.xcertplay.network

import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal data class HotspotInterfaceSnapshot(
    val name: String,
    val index: Int,
    val up: Boolean,
    val addresses: List<InetAddress>,
    val wireless: Boolean,
)

internal data class HotspotNetworkSnapshot(
    val interfaces: List<HotspotInterfaceSnapshot>,
    val apInterfaces: Set<String>?,
    val wifiUpstreams: Set<String>?,
    val defaultInterface: String?,
    val consistent: Boolean = true,
    val apEnabled: Boolean? = true,
)

internal data class HotspotSelection(val name: String, val index: Int, val address: InetAddress) {
    fun sameAddress(other: HotspotSelection): Boolean = name == other.name && index == other.index &&
        address.address.contentEquals(other.address.address) &&
        (address as? Inet6Address)?.scopeId == (other.address as? Inet6Address)?.scopeId
}

internal fun selectHotspotInterface(snapshot: HotspotNetworkSnapshot, log: (String) -> Unit): HotspotSelection? {
    if (!snapshot.consistent || snapshot.apEnabled == false) {
        log("hotspot sample rejected: network_changed=${!snapshot.consistent} apEnabled=${snapshot.apEnabled}")
        return null
    }
    return snapshot.interfaces.mapNotNull { iface ->
        val owned = snapshot.apInterfaces?.contains(iface.name) == true
        val upstream = snapshot.wifiUpstreams?.contains(iface.name) == true
        // Fork fix (e26203e, kept across the 0.2.12 merge): a manual AP hands the iPhone an IPv4
        // gateway address and iOS dials back over IPv4. Announcing/binding the interface's scoped
        // link-local IPv6 (fe80::) put that address into 0x4301 wirelessIP, which iOS cannot use,
        // leaving tcpAccepted=0 and the session stalled at WiFi_discovery_or_AirPlay_TCP. Accept
        // only an IPv4 host address; a null result keeps the readiness loop sampling until the
        // AP's IPv4 is configured. This intentionally diverges from upstream's manual-AP == P2P
        // link-local parity (WirelessHostAddress.wirelessHostAddress still prefers link-local).
        val address = iface.addresses.firstOrNull {
            it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
                !it.isAnyLocalAddress && !it.isMulticastAddress
        }
        val reason = when {
            !iface.up || iface.index <= 0 -> "interface_down"
            address == null -> "address_unavailable"
            owned -> "platform_ap"
            snapshot.apInterfaces != null -> "not_platform_ap"
            upstream -> "wifi_upstream"
            snapshot.defaultInterface == iface.name -> "default_network_without_ap_evidence"
            snapshot.wifiUpstreams == null -> "upstream_unobservable"
            !iface.wireless -> "no_ap_evidence"
            else -> "wireless_non_upstream"
        }
        log("hotspot candidate iface=${iface.name} index=${iface.index} " +
            "family=${if (address is Inet6Address) "IPv6" else if (address != null) "IPv4" else "none"} " +
            "scope=${(address as? Inet6Address)?.scopeId ?: 0} evidence=$reason " +
            "ap=${snapshot.apInterfaces?.let { if (owned) "yes" else "no" } ?: "unobservable"} " +
            "defaultConflict=${owned && (upstream || snapshot.defaultInterface == iface.name)}")
        if (reason != "platform_ap" && reason != "wireless_non_upstream") null
        else (if (owned) 100 else 0) to HotspotSelection(iface.name, iface.index, address!!)
    }.sortedWith(compareByDescending<Pair<Int, HotspotSelection>> { it.first }.thenBy { it.second.name })
        .firstOrNull()?.second
}

internal class ManualHotspotReadiness(
    private val sample: () -> HotspotNetworkSnapshot,
    private val cancelled: () -> Boolean,
    private val pause: (Long) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
) {
    fun await(timeoutMillis: Long): HotspotSelection {
        val deadline = nowMillis() + timeoutMillis
        var previous: HotspotSelection? = null
        var stable = 0
        while (true) {
            if (cancelled()) throw InterruptedIOException("Hotspot readiness cancelled")
            if (nowMillis() >= deadline) throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_NOT_READY, "Hotspot network is not ready",
            )
            val selected = selectHotspotInterface(sample(), log)
            if (cancelled()) throw InterruptedIOException("Hotspot readiness cancelled")
            stable = if (selected != null && previous?.sameAddress(selected) == true) stable + 1 else 1
            previous = selected
            if (selected != null && stable >= WirelessStartupPolicy.STABLE_SAMPLES && nowMillis() < deadline) {
                log("hotspot interface confirmed iface=${selected.name} index=${selected.index} atMs=${nowMillis()}")
                return selected
            }
            pause(minOf(WirelessStartupPolicy.INTERFACE_POLL_MILLIS, (deadline - nowMillis()).coerceAtLeast(1)))
        }
    }
}
