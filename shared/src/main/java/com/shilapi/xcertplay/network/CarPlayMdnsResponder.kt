package com.shilapi.xcertplay.network

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One DNS record as it appears in an mDNS message.
 *
 * [cacheFlush] must only be set on the records that are unique to this responder (SRV, TXT, A,
 * AAAA). Shared records such as the service PTR stay cache-flush free.
 */
internal data class CarPlayMdnsRecord(
    val name: String,
    val type: Int,
    val ttlSeconds: Long,
    val data: ByteArray,
    val cacheFlush: Boolean = false,
)

/** Pure mDNS message handling, kept free of Android APIs so it can be unit tested on the JVM. */
internal object CarPlayMdnsProtocol {
    const val GROUP_V4 = "224.0.0.251"
    const val GROUP_V6 = "ff02::fb"
    const val PORT = 5353

    const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local"
    const val SERVICES_META_TYPE = "_services._dns-sd._udp.local"

    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_AAAA = 28
    const val TYPE_SRV = 33
    const val TYPE_ANY = 255
    const val CLASS_IN = 1
    const val CLASS_CACHE_FLUSH = 0x8000

    /** Long enough for a browsing client to keep the entry across a reconnect. */
    const val TTL_HOST_SECONDS = 120L
    const val TTL_SERVICE_SECONDS = 4500L

    data class Question(val name: String, val type: Int)

    fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in name.trimEnd('.').split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(StandardCharsets.UTF_8)
            require(bytes.size <= 63) { "DNS label must not exceed 63 bytes: $label" }
            out.write(bytes.size)
            out.write(bytes, 0, bytes.size)
        }
        out.write(0)
        return out.toByteArray()
    }

    fun encodeTxt(records: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((key, value) in records) {
            val entry = "$key=$value".toByteArray(StandardCharsets.UTF_8)
            require(entry.size <= 255) { "DNS TXT entry must not exceed 255 bytes: $key" }
            out.write(entry.size)
            out.write(entry, 0, entry.size)
        }
        if (out.size() == 0) out.write(0)
        return out.toByteArray()
    }

    fun questions(packet: ByteArray, length: Int): List<Question> {
        if (length < DNS_HEADER_BYTES) return emptyList()
        val count = u16(packet, 4)
        val result = ArrayList<Question>(count)
        var offset = DNS_HEADER_BYTES
        repeat(count) {
            val (name, next) = readName(packet, length, offset) ?: return result
            offset = next
            if (offset + 4 > length) return result
            result += Question(name, u16(packet, offset))
            offset += 4
        }
        return result
    }

    /**
     * Builds the answer set for one query. Only records this responder owns are ever returned, so
     * a query for an unrelated service produces an empty list and no reply.
     */
    fun answersFor(
        questions: List<Question>,
        instance: String,
        hostName: String,
        port: Int,
        ipv4: ByteArray?,
        ipv6: ByteArray?,
        txt: ByteArray,
    ): List<CarPlayMdnsRecord> {
        if (questions.isEmpty()) return emptyList()
        val records = ArrayList<CarPlayMdnsRecord>(8)
        val instanceName = instance.trimEnd('.')
        val host = hostName.trimEnd('.')
        for (question in questions) {
            when (question.name.lowercase()) {
                AIRPLAY_SERVICE_TYPE -> {
                    if (question.type != TYPE_PTR && question.type != TYPE_ANY) continue
                    records += CarPlayMdnsRecord(
                        name = AIRPLAY_SERVICE_TYPE,
                        type = TYPE_PTR,
                        ttlSeconds = TTL_SERVICE_SECONDS,
                        data = encodeName(instanceName),
                    )
                    records += serviceRecords(instanceName, host, port, txt)
                    records += hostRecords(host, ipv4, ipv6)
                }

                SERVICES_META_TYPE -> {
                    if (question.type != TYPE_PTR && question.type != TYPE_ANY) continue
                    records += CarPlayMdnsRecord(
                        name = SERVICES_META_TYPE,
                        type = TYPE_PTR,
                        ttlSeconds = TTL_SERVICE_SECONDS,
                        data = encodeName(AIRPLAY_SERVICE_TYPE),
                    )
                }

                instanceName.lowercase() -> {
                    records += serviceRecords(instanceName, host, port, txt)
                    records += hostRecords(host, ipv4, ipv6)
                }

                host.lowercase() -> records += hostRecords(host, ipv4, ipv6)
            }
        }
        return records.distinctBy { it.type to it.name.lowercase() }
    }

    private fun serviceRecords(
        instanceName: String,
        hostName: String,
        port: Int,
        txt: ByteArray,
    ): List<CarPlayMdnsRecord> {
        val target = encodeName(hostName)
        val srv = ByteArrayOutputStream()
        // RFC 2782 rdata is priority, weight, port, then the target name. ByteArrayOutputStream
        // writes one byte per call, so each 16-bit field needs both halves written explicitly.
        writeU16(srv, 0)
        writeU16(srv, 0)
        writeU16(srv, port)
        srv.write(target, 0, target.size)
        return listOf(
            CarPlayMdnsRecord(
                name = instanceName,
                type = TYPE_SRV,
                ttlSeconds = TTL_HOST_SECONDS,
                data = srv.toByteArray(),
                cacheFlush = true,
            ),
            CarPlayMdnsRecord(
                name = instanceName,
                type = TYPE_TXT,
                ttlSeconds = TTL_SERVICE_SECONDS,
                data = txt,
                cacheFlush = true,
            ),
        )
    }

    private fun hostRecords(
        hostName: String,
        ipv4: ByteArray?,
        ipv6: ByteArray?,
    ): List<CarPlayMdnsRecord> = buildList {
        if (ipv4 != null && ipv4.size == 4) {
            add(
                CarPlayMdnsRecord(
                    name = hostName,
                    type = TYPE_A,
                    ttlSeconds = TTL_HOST_SECONDS,
                    data = ipv4,
                    cacheFlush = true,
                ),
            )
        }
        if (ipv6 != null && ipv6.size == 16) {
            add(
                CarPlayMdnsRecord(
                    name = hostName,
                    type = TYPE_AAAA,
                    ttlSeconds = TTL_HOST_SECONDS,
                    data = ipv6,
                    cacheFlush = true,
                ),
            )
        }
    }

    /**
     * Encodes a reply. [queryId] is echoed for legacy unicast queries and zeroed for multicast
     * ones, as RFC 6762 requires.
     */
    fun encodeResponse(
        queryId: Int,
        questions: List<Question>,
        records: List<CarPlayMdnsRecord>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((queryId ushr 8) and 0xff)
        out.write(queryId and 0xff)
        out.write(0x84)
        out.write(0x00)
        writeU16(out, questions.size)
        writeU16(out, records.size)
        writeU16(out, 0)
        writeU16(out, 0)
        for (question in questions) {
            out.write(encodeName(question.name))
            writeU16(out, question.type)
            writeU16(out, CLASS_IN)
        }
        for (record in records) {
            out.write(encodeName(record.name))
            writeU16(out, record.type)
            writeU16(out, if (record.cacheFlush) CLASS_IN or CLASS_CACHE_FLUSH else CLASS_IN)
            writeU32(out, record.ttlSeconds)
            writeU16(out, record.data.size)
            out.write(record.data)
        }
        return out.toByteArray()
    }

    /** An unsolicited announcement carrying every record with the cache-flush bit set. */
    fun encodeAnnouncement(records: List<CarPlayMdnsRecord>): ByteArray =
        encodeResponse(0, emptyList(), records.map { it.copy(cacheFlush = it.type != TYPE_PTR) })

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        writeU16(out, ((value ushr 16) and 0xffff).toInt())
        writeU16(out, (value and 0xffff).toInt())
    }

    private fun u16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)

    private fun readName(
        packet: ByteArray,
        length: Int,
        start: Int,
    ): Pair<String, Int>? {
        val name = StringBuilder()
        var offset = start
        var nextOffset = -1
        var hops = 0
        while (offset < length) {
            if (hops++ > MAX_COMPRESSION_HOPS) return null
            val labelLength = packet[offset].toInt() and 0xff
            if (labelLength == 0) {
                if (nextOffset < 0) nextOffset = offset + 1
                return name.toString() to nextOffset
            }
            if (labelLength and 0xc0 == 0xc0) {
                if (offset + 1 >= length) return null
                if (nextOffset < 0) nextOffset = offset + 2
                offset = ((labelLength and 0x3f) shl 8) or (packet[offset + 1].toInt() and 0xff)
                continue
            }
            if (offset + 1 + labelLength > length) return null
            if (name.isNotEmpty()) name.append('.')
            name.append(String(packet, offset + 1, labelLength, StandardCharsets.UTF_8))
            offset += 1 + labelLength
        }
        return null
    }

    private const val DNS_HEADER_BYTES = 12
    private const val MAX_COMPRESSION_HOPS = 32
}

/**
 * Advertises the accessory AirPlay service directly on the hotspot interface.
 *
 * The platform NSD daemon publishes through its own interface selection, which on several vendor
 * Wi-Fi Direct stacks never includes the group interface, so the handset that just joined the
 * group cannot discover AirPlay even though it associated successfully. This responder binds its
 * sockets to the hotspot interface explicitly, which removes that dependency, and logs every
 * inbound query so a session can be diagnosed from the accessory side alone.
 */
internal class CarPlayMdnsResponder(
    private val interfaceName: String,
    private val instanceName: String,
    private val hostName: String,
    private val port: Int,
    txt: Map<String, String>,
    private val log: (String) -> Unit,
) : Closeable {
    private val txtBytes = CarPlayMdnsProtocol.encodeTxt(txt)
    private val closed = AtomicBoolean(false)
    private val reportedNeighbours = HashSet<String>()

    private var socketV4: MulticastSocket? = null
    private var socketV6: MulticastSocket? = null
    private var worker: Thread? = null
    private var localAddresses: Set<String> = emptySet()

    /** @return true when at least one family is listening. */
    fun start(): Boolean {
        val networkInterface = try {
            NetworkInterface.getByName(interfaceName)
        } catch (error: Exception) {
            log("mdns responder could not resolve interface $interfaceName: ${error.message}")
            null
        }
        if (networkInterface == null) {
            log("mdns responder has no interface named $interfaceName")
            return false
        }
        localAddresses = addressesOf(networkInterface).mapNotNull { it.hostAddress }.toSet()
        val ipv4 = addressesOf(networkInterface).filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
        val ipv6Addresses = addressesOf(networkInterface).filterIsInstance<Inet6Address>()
            .filter { !it.isLoopbackAddress }
        val ipv6 = ipv6Addresses.firstOrNull { it.isLinkLocalAddress } ?: ipv6Addresses.firstOrNull()
        log(
            "mdns responder on $interfaceName ipv4=${ipv4?.hostAddress ?: "none"} " +
                "ipv6=${ipv6?.hostAddress ?: "none"} instance=$instanceName host=$hostName",
        )
        socketV4 = openSocket(CarPlayMdnsProtocol.GROUP_V4, networkInterface)
        socketV6 = if (ipv6 != null) openSocket(CarPlayMdnsProtocol.GROUP_V6, networkInterface) else null
        if (socketV4 == null && socketV6 == null) {
            log("mdns responder could not open any multicast socket on $interfaceName")
            return false
        }
        worker = Thread({ runLoop(ipv4?.address, ipv6?.address) }, WORKER_NAME).apply {
            isDaemon = true
            start()
        }
        announce(ipv4?.address, ipv6?.address)
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socketV4?.close() }
        runCatching { socketV6?.close() }
        socketV4 = null
        socketV6 = null
        val thread = worker
        worker = null
        if (thread != null && thread !== Thread.currentThread()) {
            thread.interrupt()
            runCatching { thread.join(JOIN_TIMEOUT_MILLIS) }
        }
    }

    private fun openSocket(group: String, networkInterface: NetworkInterface): MulticastSocket? = try {
        val socket = MulticastSocket(CarPlayMdnsProtocol.PORT)
        socket.reuseAddress = true
        socket.networkInterface = networkInterface
        socket.timeToLive = MULTICAST_TTL.toInt()
        // Java exposes this inverted: setLoopbackMode(true) *disables* loopback. Loopback stays on
        // so the accessory's own announcements are visible as proof the interface path works.
        socket.loopbackMode = false
        val groupAddress = InetAddress.getByName(group)
        socket.joinGroup(InetSocketAddress(groupAddress, CarPlayMdnsProtocol.PORT), networkInterface)
        log("mdns responder joined $group on ${networkInterface.name}")
        socket
    } catch (error: Exception) {
        log("mdns responder could not join $group on ${networkInterface.name}: ${error.message}")
        null
    }

    private fun announce(ipv4: ByteArray?, ipv6: ByteArray?) {
        if (ipv4 == null && ipv6 == null) return
        val records = CarPlayMdnsProtocol.answersFor(
            questions = listOf(
                CarPlayMdnsProtocol.Question(CarPlayMdnsProtocol.AIRPLAY_SERVICE_TYPE, CarPlayMdnsProtocol.TYPE_ANY),
            ),
            instance = instanceName,
            hostName = hostName,
            port = port,
            ipv4 = ipv4,
            ipv6 = ipv6,
            txt = txtBytes,
        )
        if (records.isEmpty()) return
        val packet = CarPlayMdnsProtocol.encodeAnnouncement(records)
        repeat(ANNOUNCEMENTS) { round ->
            if (closed.get()) return
            if (round > 0) Thread.sleep(ANNOUNCEMENT_INTERVAL_MILLIS)
            send(packet, null)
        }
        log("mdns responder announced ${records.size} records for $instanceName on $interfaceName")
    }

    private fun runLoop(ipv4: ByteArray?, ipv6: ByteArray?) {
        var lastNeighbourCheck = 0L
        while (!closed.get()) {
            for (socket in listOfNotNull(socketV4, socketV6)) {
                serveOnce(socket, ipv4, ipv6)
            }
            val now = System.currentTimeMillis()
            if (now - lastNeighbourCheck >= NEIGHBOUR_POLL_MILLIS) {
                lastNeighbourCheck = now
                reportNeighbours()
            }
        }
    }

    private fun serveOnce(socket: MulticastSocket, ipv4: ByteArray?, ipv6: ByteArray?) {
        val buffer = ByteArray(MAX_PACKET_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            socket.soTimeout = RECEIVE_TIMEOUT_MILLIS.toInt()
            socket.receive(packet)
        } catch (_: java.net.SocketTimeoutException) {
            return
        } catch (_: Exception) {
            // A closed socket ends the loop through the closed flag below.
            return
        }
        if (closed.get()) return
        val source = packet.address?.hostAddress ?: return
        val length = packet.length
        if (length < MIN_PACKET_BYTES) return
        if (isSelf(packet)) return
        // Never answer a response: only queries carry a cleared QR bit.
        if (packet.data[2].toInt() and QR_BIT != 0) return
        val questions = CarPlayMdnsProtocol.questions(packet.data, length)
        if (questions.isEmpty()) return
        val legacyUnicast = packet.port != CarPlayMdnsProtocol.PORT
        log(
            "mdns query from $source:${packet.port} " +
                questions.joinToString(" ") { "${it.name}/${it.type}" } +
                if (legacyUnicast) " (unicast)" else " (multicast)",
        )
        val records = CarPlayMdnsProtocol.answersFor(
            questions = questions,
            instance = instanceName,
            hostName = hostName,
            port = port,
            ipv4 = ipv4,
            ipv6 = ipv6,
            txt = txtBytes,
        )
        if (records.isEmpty()) return
        val queryId = ((packet.data[0].toInt() and 0xff) shl 8) or (packet.data[1].toInt() and 0xff)
        val response = CarPlayMdnsProtocol.encodeResponse(
            queryId = if (legacyUnicast) queryId else 0,
            questions = questions,
            records = records,
        )
        send(response, if (legacyUnicast) packet.socketAddress else null)
        log("mdns answered $source with ${records.size} records")
    }

    private fun send(payload: ByteArray, target: java.net.SocketAddress?) {
        for (socket in listOfNotNull(socketV4, socketV6)) {
            try {
                if (target != null) {
                    val address = target as InetSocketAddress
                    if ((address.address is Inet6Address) != (socket === socketV6)) continue
                    socket.send(DatagramPacket(payload, payload.size, address))
                } else {
                    val group = if (socket === socketV6) {
                        CarPlayMdnsProtocol.GROUP_V6
                    } else {
                        CarPlayMdnsProtocol.GROUP_V4
                    }
                    val address = InetSocketAddress(InetAddress.getByName(group), CarPlayMdnsProtocol.PORT)
                    socket.send(DatagramPacket(payload, payload.size, address))
                }
            } catch (_: Exception) {
                // A failed send must never abort the session; the next query retries.
            }
        }
    }

    private fun isSelf(packet: DatagramPacket): Boolean {
        val source = packet.address?.hostAddress ?: return true
        if (source in localAddresses) return true
        return packet.port == CarPlayMdnsProtocol.PORT && source.startsWith("fe80::") &&
            localAddresses.any { it.substringBefore('%') == source.substringBefore('%') }
    }

    /**
     * The hotspot link is the only place the handset can appear, so a neighbour entry here is the
     * proof that it obtained a lease and started using IP.
     */
    private fun reportNeighbours() {
        val table = try {
            File(PROC_NET_ARP).readLines()
        } catch (_: Exception) {
            return
        }
        for (line in table.drop(1)) {
            val columns = line.trim().split(Regex("\\s+"))
            if (columns.size < 6) continue
            val device = columns[5]
            if (device != interfaceName) continue
            val entry = "${columns[0]} ${columns[3]} $device"
            if (reportedNeighbours.add(entry)) {
                log("mdns link neighbour on $interfaceName: $entry")
            }
        }
    }

    private fun addressesOf(networkInterface: NetworkInterface): List<InetAddress> = try {
        java.util.Collections.list(networkInterface.inetAddresses)
    } catch (_: Exception) {
        emptyList()
    }

    private companion object {
        const val WORKER_NAME = "carplay-mdns"
        const val PROC_NET_ARP = "/proc/net/arp"
        const val MAX_PACKET_BYTES = 2048
        const val MIN_PACKET_BYTES = 12
        const val QR_BIT = 0x80
        const val RECEIVE_TIMEOUT_MILLIS = 400L
        const val JOIN_TIMEOUT_MILLIS = 1_000L
        const val ANNOUNCEMENTS = 3
        const val ANNOUNCEMENT_INTERVAL_MILLIS = 250L
        const val NEIGHBOUR_POLL_MILLIS = 2_000L
        const val MULTICAST_TTL = 255
    }
}
