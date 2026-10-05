package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WirelessHostAddressTest {
    /**
     * The handset is handed this address as `0x4301 wirelessIP`; a link-local IPv6 value there
     * leaves the iPhone unable to dial back, so IPv4 has to win even when `fe80::` comes first.
     */
    @Test fun advertisedHostAddressPrefersIpv4EvenWhenLinkLocalComesFirst() {
        val ipv4 = ip("192.168.43.1")
        assertEquals(ipv4, wirelessHostAddress(listOf(ip("fe80::1234"), ipv4), 7))
        assertEquals(ipv4, wirelessHostAddress(listOf(ipv4), 7))
    }

    @Test fun advertisedHostAddressFallsBackToScopedLinkLocalOnlyWithoutIpv4() {
        val result = wirelessHostAddress(listOf(ip("::1"), ip("2001:db8::1"), ip("fe80::1234")), 7)
        assertTrue(result is Inet6Address)
        assertEquals(7, (result as Inet6Address).scopeId)
    }

    @Test fun replacesScopeFromAnotherInterface() {
        val wrongScope = Inet6Address.getByAddress(null, ip("fe80::1234").address, 3)
        assertEquals(8, (wirelessHostAddress(listOf(wrongScope), 8) as Inet6Address).scopeId)
    }

    @Test fun rejectsUnusableAddresses() {
        assertNull(wirelessHostAddress(listOf(ip("0.0.0.0"), ip("127.0.0.1"), ip("224.0.0.251")), 7))
        // No interface index means the link-local address cannot be scoped, so it is not usable.
        assertNull(wirelessHostAddress(listOf(ip("fe80::1234")), 0))
        // A global IPv6 address is not a dialable replacement for a missing IPv4 lease.
        assertNull(wirelessHostAddress(listOf(ip("2001:db8::1")), 7))
    }

    @Test fun stationDiscoveryListsIpv4FirstThenScopedLinkLocal() {
        val addresses = listOf(ip("fe80::1234"), ip("192.168.128.10"), ip("2001:db8::1"))
        val hosts = existingWifiHostAddresses(addresses, 7)
        assertEquals(2, hosts.size)
        assertEquals(ip("192.168.128.10"), hosts.first())
        assertEquals(7, (hosts.last() as Inet6Address).scopeId)
        // The advertised host address is the IPv4 one; the link-local entry only rides along as a
        // secondary listener address.
        assertEquals(ip("192.168.128.10"), wirelessHostAddress(addresses, 7))
    }

    @Test fun stationDiscoveryRejectsUnusableAddressesAndUnscopedIpv6() {
        assertEquals(emptyList<InetAddress>(), existingWifiHostAddresses(
            listOf(ip("0.0.0.0"), ip("127.0.0.1"), ip("169.254.1.2"), ip("224.0.0.251"), ip("2001:db8::1")), 7))
        assertEquals(listOf(ip("192.0.2.10")), existingWifiHostAddresses(
            listOf(ip("fe80::1"), ip("192.0.2.10")), 0))
        assertEquals(7, (existingWifiHostAddresses(listOf(ip("fe80::1")), 7).single() as Inet6Address).scopeId)
    }

    private fun ip(value: String) = InetAddress.getByName(value)
}
