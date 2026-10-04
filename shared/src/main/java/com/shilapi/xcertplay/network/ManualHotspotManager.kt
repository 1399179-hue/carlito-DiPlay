package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Attaches to a hotspot that is already running on this device.
 *
 * The hotspot remains owned by the system. This manager only locates its interface and reads the
 * channel/security data that the public Android APIs expose. Some vendors hide the current SoftAP
 * configuration, in which case the caller-supplied credentials remain the fallback and the iAP2
 * channel is reported as zero ("auto"). When Android exposes the live password, it takes precedence
 * so a stale saved password cannot keep the iPhone off the active hotspot.
 */
class ManualHotspotManager(
    context: Context,
    ssid: String?,
    passphrase: String?,
    band: ManualHotspotBand,
    channel: Int,
    security: ManualHotspotSecurity,
    private val preferSystemConfiguration: Boolean = false,
    private val onDiagnostic: (String) -> Unit = {},
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val expectedSsid = ssid?.takeIf { it.isNotBlank() }
    private val passphrase = passphrase.orEmpty()
    private val expectedBand = band
    private val expectedChannel = channel
    private val expectedSecurity = security.toIap2Security()
    private val linkPreferences = appContext.getSharedPreferences(LINK_PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var availableInterfaces = emptyList<LocalHotspotInterface>()

    @Volatile
    private var closed = false

    init {
        require(expectedSsid == null || '\u0000' !in expectedSsid) { "ssid must not contain U+0000" }
        require('\u0000' !in this.passphrase) { "passphrase must not contain U+0000" }
        require(this.passphrase.isEmpty() || this.passphrase.length in 8..63) {
            "passphrase must be empty or between 8 and 63 characters"
        }
        require(channel in 0..196) { "channel must be 0 or in 1..196" }
        require(preferSystemConfiguration && expectedSsid == null ||
            expectedSecurity == Iap2WirelessSecurity.NONE || this.passphrase.isNotEmpty()) {
            "passphrase is required for secured manual hotspots"
        }
        require(preferSystemConfiguration && expectedSsid == null ||
            expectedSecurity != Iap2WirelessSecurity.NONE || this.passphrase.isEmpty()) {
            "passphrase must be empty for open manual hotspots"
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ManualHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val deadlineNanos = deadlineAfter(timeoutMillis)
        val apConfiguration = readApConfiguration()
        if (!preferSystemConfiguration && apConfiguration != null &&
            expectedSsid != null && apConfiguration.ssid != expectedSsid
        ) {
            throw IOException(
                "Manual hotspot SSID does not match the active local AP configuration: " +
                    "'${apConfiguration.ssid}'",
            )
        }
        if (!preferSystemConfiguration) validateApConfiguration(apConfiguration)
        val activeSsid = when {
            preferSystemConfiguration && apConfiguration != null -> apConfiguration.ssid
            expectedSsid != null -> expectedSsid
            apConfiguration != null -> apConfiguration.ssid
            else -> throw IOException("The active hotspot configuration is not readable")
        }

        var lastReason = "local hotspot interface was not found"
        while (true) {
            check(!closed) { "ManualHotspotManager is closed" }
            val localInterfaces = findLocalHotspotInterfaces()
            val localInterface = localInterfaces.firstOrNull()
            if (localInterface != null) {
                availableInterfaces = localInterfaces
                val connectionFrequency = frequencyFromConnectionInfo(activeSsid)
                val scanFrequency = frequencyFromScanResult(localInterface, activeSsid)
                val channel = observedManualHotspotChannel(
                    apChannel = apConfiguration?.channel ?: 0,
                    connectionFrequencyMHz = connectionFrequency,
                    scanFrequencyMHz = scanFrequency,
                    apFrequencyMHz = apConfiguration?.frequencyMHz,
                )
                val frequencyMHz = when {
                    apConfiguration?.frequencyMHz != null -> apConfiguration.frequencyMHz
                    connectionFrequency != null -> connectionFrequency
                    scanFrequency != null -> scanFrequency
                    else -> null
                }
                val security = apConfiguration?.security ?: expectedSecurity
                val systemPassphrase = apConfiguration?.passphrase
                    ?.takeIf { it.length in 8..63 && '\u0000' !in it }
                val effectivePassphrase = when (security) {
                    Iap2WirelessSecurity.NONE -> ""
                    else -> systemPassphrase ?: passphrase
                }
                onDiagnostic("Manual hotspot configReadable=${apConfiguration != null} " +
                    "security=$security channelKnown=${channel > 0} " +
                    "credentialsSource=${if (systemPassphrase != null) "system" else "saved"} " +
                    "hardwareAddressKnown=${localInterface.hardwareAddress != null} iface=${localInterface.name} " +
                    "tethered=${localInterface.tethered} " +
                    "family=${if (localInterface.hostAddress is Inet6Address) "IPv6" else "IPv4"}")
                if (security != Iap2WirelessSecurity.NONE && effectivePassphrase.isEmpty()) {
                    throw IOException("Manual hotspot is secured but no passphrase was provided")
                }

                if (channel == 0) {
                    Log.w(
                        TAG,
                        "Could not read the active hotspot channel from Android public APIs; " +
                            "reporting iAP2 channel 0 (auto) instead of configured channel " +
                            "$expectedChannel",
                    )
                }
                val observedBandLabel = wifiBandLabel(apConfiguration?.band)
                return WirelessHotspotInfo(
                    ssid = activeSsid,
                    passphrase = effectivePassphrase,
                    security = security,
                    channel = channel,
                    frequencyMHz = frequencyMHz,
                    bssid = localInterface.hardwareAddress,
                    interfaceName = localInterface.name,
                    hostAddress = localInterface.hostAddress,
                    bandLabel = when (expectedBand) {
                        ManualHotspotBand.GHZ_2_4 -> "2.4 GHz"
                        ManualHotspotBand.GHZ_5 -> "5 GHz"
                        ManualHotspotBand.AUTO ->
                            frequencyMHz?.let(::bandLabel) ?: observedBandLabel ?: "Auto"
                    },
                    backend = WirelessHotspotBackend.MANUAL_HOTSPOT,
                    linkCandidates = localInterfaces.map {
                        WirelessLinkCandidate(it.name, it.hostAddress, it.hardwareAddress, it.score)
                    },
                )
            }

            val remainingNanos = remainingNanos(deadlineNanos)
            if (remainingNanos <= 0) {
                throw IOException(
                    "Timed out after ${timeoutMillis}ms waiting for the manual hotspot: " +
                        lastReason,
                )
            }
            sleep(minOf(remainingNanos, INTERFACE_POLL_NANOS))
        }
    }

    override fun close() {
        closed = true
    }

    override fun onLinkAccepted(address: InetAddress) {
        val selected = availableInterfaces.firstOrNull {
            it.hostAddress.hostAddress == address.hostAddress
        } ?: return
        linkPreferences.edit().putString(LINK_PREF_LAST_INTERFACE, selected.name).apply()
        onDiagnostic("Manual hotspot link confirmed iface=${selected.name} " +
            "family=${if (address is Inet6Address) "IPv6" else "IPv4"}")
    }

    private fun validateApConfiguration(configuration: ManualApConfiguration?) {
        configuration ?: return
        if (expectedChannel > 0 && configuration.channel > 0 &&
            configuration.channel != expectedChannel
        ) {
            throw IOException(
                "Manual hotspot channel ${configuration.channel} does not match configured " +
                "channel $expectedChannel",
            )
        }
        val actualBand = when (configuration.band) {
            1 -> ManualHotspotBand.GHZ_2_4
            2 -> ManualHotspotBand.GHZ_5
            else -> null
        }
        if (actualBand != null && expectedBand != ManualHotspotBand.AUTO &&
            actualBand != expectedBand
        ) {
            throw IOException(
                "Manual hotspot band ${wifiBandLabel(configuration.band)} does not match " +
                    "configured band ${wifiBandLabel(if (expectedBand == ManualHotspotBand.GHZ_2_4) 1 else 2)}",
            )
        }
        // WPA2 vs WPA3 variants are fine: the live security is what the iPhone is told (see start()).
        // Only an open/secured mismatch means the saved password cannot be right.
        if ((configuration.security == Iap2WirelessSecurity.NONE) != (expectedSecurity == Iap2WirelessSecurity.NONE)) {
            throw IOException(
                "Manual hotspot security ${configuration.security} does not match configured " +
                    "security $expectedSecurity",
            )
        }
        val frequency = configuration.frequencyMHz ?: return
        when (expectedBand) {
            ManualHotspotBand.GHZ_2_4 -> if (frequency !in 2_400..2_500) {
                throw IOException("Manual hotspot is not running on 2.4 GHz")
            }
            ManualHotspotBand.GHZ_5 -> if (frequency !in 5_150..5_895) {
                throw IOException("Manual hotspot is not running on 5 GHz")
            }
            ManualHotspotBand.AUTO -> Unit
        }
    }

    private fun findLocalHotspotInterfaces(): List<LocalHotspotInterface> {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: SocketException) {
            null
        } ?: return emptyList()
        val primaryInterface = connectivityManager?.activeNetwork
            ?.let { connectivityManager.getLinkProperties(it)?.interfaceName }
        val tetheredInterfaces = tetheredInterfaceNames()
        val preferredInterface = linkPreferences.getString(LINK_PREF_LAST_INTERFACE, null)
        return Collections.list(interfaces)
            .asSequence()
            .filter { isUsableInterface(it, primaryInterface, tetheredInterfaces) }
            .mapNotNull { networkInterface ->
                networkInterface.hotspotAddress()?.let { address ->
                    LocalHotspotInterface(
                        name = networkInterface.name,
                        hostAddress = address,
                        hardwareAddress = runCatching { networkInterface.hardwareAddress?.toMacAddressString() }
                            .getOrNull()?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" }
                            ?: HotspotInterfaceBssid.read(networkInterface.name),
                        tethered = networkInterface.name in tetheredInterfaces,
                        score = interfaceScore(networkInterface.name, address) +
                            (if (networkInterface.name in tetheredInterfaces) 1_000 else 0) +
                            (if (networkInterface.name == preferredInterface) 500 else 0),
                    )
                }
            }
            .sortedByDescending(LocalHotspotInterface::score)
            .distinctBy { it.hostAddress.hostAddress }
            .take(MAX_LINK_CANDIDATES)
            .toList()
    }

    private fun isUsableInterface(
        networkInterface: NetworkInterface,
        primaryInterface: String?,
        tetheredInterfaces: Set<String>,
    ): Boolean = try {
        (networkInterface.name != primaryInterface || networkInterface.name in tetheredInterfaces) &&
            !networkInterface.isLoopback &&
            networkInterface.isUp &&
            EXCLUDED_INTERFACE_PREFIXES.none { networkInterface.name.startsWith(it) }
    } catch (_: SocketException) {
        false
    }

    @SuppressLint("PrivateApi")
    private fun tetheredInterfaceNames(): Set<String> {
        val manager = connectivityManager ?: return emptySet()
        return runCatching {
            val method = ConnectivityManager::class.java.getDeclaredMethod("getTetheredIfaces")
            method.isAccessible = true
            (method.invoke(manager) as? Array<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        }.getOrDefault(emptySet())
    }

    private fun interfaceScore(name: String, address: InetAddress): Int {
        var score = when {
            name.startsWith("ap") || name.contains("softap", ignoreCase = true) -> 100
            name.startsWith("vt") -> 90
            name.startsWith("p2p") -> 80
            name.startsWith("wlan") -> 70
            else -> 0
        }
        if (address is Inet4Address) {
            score += 200
            val bytes = address.address
            when {
                bytes[0] == 192.toByte() && bytes[1] == 168.toByte() -> score += 30
                address.isSiteLocalAddress -> score += 20
            }
        }
        if (address is Inet6Address && address.isLinkLocalAddress) score += 15
        return score
    }

    private fun NetworkInterface.hotspotAddress(): InetAddress? {
        val addresses = Collections.list(inetAddresses)
        return addresses.firstOrNull {
            it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
                !it.isAnyLocalAddress && !it.isMulticastAddress
        } ?: wirelessHostAddress(addresses, index)
    }

    private fun frequencyFromConnectionInfo(activeSsid: String): Int? {
        val connectionInfo = try {
            wifiManager.connectionInfo
        } catch (_: SecurityException) {
            null
        } ?: return null
        if (unquote(connectionInfo.ssid) != activeSsid) return null
        return connectionInfo.frequency.takeIf { it > 0 }
    }

    private fun frequencyFromScanResult(localInterface: LocalHotspotInterface, activeSsid: String): Int? {
        val localBssid = localInterface.hardwareAddress ?: return null
        val scanResults = try {
            wifiManager.scanResults
        } catch (_: SecurityException) {
            return null
        }
        return scanResults.firstOrNull { result ->
            result.SSID == activeSsid &&
                result.BSSID.equals(localBssid, ignoreCase = true) &&
                result.frequency > 0
        }?.frequency
    }

    @SuppressLint("PrivateApi")
    private fun readApConfiguration(): ManualApConfiguration? =
        readSoftApConfiguration() ?: readLegacyApConfiguration()

    @SuppressLint("PrivateApi")
    private fun readSoftApConfiguration(): ManualApConfiguration? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val method = WifiManager::class.java.getMethod("getSoftApConfiguration")
            val configuration = method.invoke(wifiManager) as? SoftApConfiguration
                ?: return null
            val ssid = configuration.ssid ?: return null
            val bandAndChannel = when {
                Build.VERSION.SDK_INT >= 36 -> {
                    val channels = configuration.channels
                    if (channels.size() == 0) null else channels.keyAt(0) to channels.valueAt(0)
                }
                else -> {
                    val band = (
                        SoftApConfiguration::class.java
                            .getMethod("getBand")
                            .invoke(configuration) as? Number
                        )?.toInt()
                    val channel = SoftApConfiguration::class.java
                        .getMethod("getChannel")
                        .invoke(configuration) as? Number
                    if (band == null || channel == null) null else band to channel.toInt()
                }
            }
            val band = bandAndChannel?.first
            val channel = bandAndChannel?.second ?: 0
            ManualApConfiguration(
                ssid = ssid,
                band = band,
                channel = channel,
                frequencyMHz = wifiChannelToFrequencyMhz(channel, band),
                security = mapSoftApSecurity(configuration.securityType),
                passphrase = configuration.passphrase,
            )
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("PrivateApi")
    private fun readLegacyApConfiguration(): ManualApConfiguration? {
        return try {
            val method = WifiManager::class.java.getMethod("getWifiApConfiguration")
            val configuration = method.invoke(wifiManager) as? WifiConfiguration
                ?: return null
            val ssid = unquote(configuration.SSID) ?: return null
            val channel = try {
                WifiConfiguration::class.java.getField("apChannel").getInt(configuration)
            } catch (_: ReflectiveOperationException) {
                0
            }
            val band = try {
                legacyHotspotBandToSoftApBand(WifiConfiguration::class.java.getField("apBand").getInt(configuration))
            } catch (_: ReflectiveOperationException) {
                null
            }
            ManualApConfiguration(
                ssid = ssid,
                band = band,
                channel = channel,
                frequencyMHz = wifiChannelToFrequencyMhz(channel, band),
                security = mapWifiConfigurationSecurity(configuration),
                passphrase = unquote(configuration.preSharedKey),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun mapSoftApSecurity(securityType: Int): Iap2WirelessSecurity = when (securityType) {
        SoftApConfiguration.SECURITY_TYPE_OPEN -> Iap2WirelessSecurity.NONE
        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> Iap2WirelessSecurity.WPA_WPA2
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION ->
            Iap2WirelessSecurity.WPA3_TRANSITION
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> Iap2WirelessSecurity.WPA3_ONLY
        else -> Iap2WirelessSecurity.WPA_WPA2
    }

    private fun mapWifiConfigurationSecurity(
        configuration: WifiConfiguration,
    ): Iap2WirelessSecurity {
        val keyManagement = configuration.allowedKeyManagement ?: return Iap2WirelessSecurity.NONE
        val open = keyManagement.get(WifiConfiguration.KeyMgmt.NONE)
        val wpa2 = keyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
        val sae = keyManagement.get(WifiConfiguration.KeyMgmt.SAE)
        return when {
            open && !wpa2 && !sae -> Iap2WirelessSecurity.NONE
            wpa2 && sae -> Iap2WirelessSecurity.WPA3_TRANSITION
            wpa2 -> Iap2WirelessSecurity.WPA_WPA2
            sae -> Iap2WirelessSecurity.WPA3_ONLY
            else -> Iap2WirelessSecurity.WPA_WPA2
        }
    }

    private fun bandLabel(frequencyMHz: Int): String = when (frequencyMHz) {
        in 2400..2500 -> "2.4 GHz"
        in 5150..5895 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> "Unknown band"
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    private fun ByteArray.toMacAddressString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun sleep(nanos: Long) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for the manual hotspot", interrupted)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun remainingNanos(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private class ManualApConfiguration(
        val ssid: String,
        val band: Int?,
        val channel: Int,
        val frequencyMHz: Int?,
        val security: Iap2WirelessSecurity,
        val passphrase: String?,
    )

    private class LocalHotspotInterface(
        val name: String,
        val hostAddress: InetAddress,
        val hardwareAddress: String?,
        val tethered: Boolean,
        val score: Int,
    )

    private companion object {
        const val TAG = "xcertplay-usb"
        const val LINK_PREFS = "wireless_link_preferences"
        const val LINK_PREF_LAST_INTERFACE = "manual_hotspot_last_interface"
        const val MAX_LINK_CANDIDATES = 4
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val INTERFACE_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(250)
        val EXCLUDED_INTERFACE_PREFIXES = listOf(
            "lo",
            "dummy",
            "rmnet",
            "r_rmnet",
            "tun",
            "ppp",
            "sit",
            "ip6",
            "bond",
        )
    }
}

private fun ManualHotspotSecurity.toIap2Security(): Iap2WirelessSecurity = when (this) {
    ManualHotspotSecurity.OPEN -> Iap2WirelessSecurity.NONE
    ManualHotspotSecurity.WPA2 -> Iap2WirelessSecurity.WPA_WPA2
    ManualHotspotSecurity.WPA3_TRANSITION -> Iap2WirelessSecurity.WPA3_TRANSITION
    ManualHotspotSecurity.WPA3 -> Iap2WirelessSecurity.WPA3_ONLY
}
