package com.shilapi.xcertplay.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayMdnsResponderTest {
    private val instance = "xcertplay._airplay._tcp.local"
    private val controlInstance = "xcertplay._carplay-ctrl._tcp.local"
    private val host = "xcertplay.local"
    private val txt = CarPlayMdnsProtocol.encodeTxt(mapOf("deviceid" to "e2342002dc5b"))
    private val ipv4 = byteArrayOf(192.toByte(), 168.toByte(), 231.toByte(), 111)
    private val ipv6 = ByteArray(16) { if (it == 15) 1 else 0 }
    private val services = listOf(
        CarPlayMdnsProtocol.MdnsService(
            CarPlayMdnsProtocol.AIRPLAY_SERVICE_TYPE,
            instance,
            txt,
        ),
        CarPlayMdnsProtocol.MdnsService(
            CarPlayMdnsProtocol.CARPLAY_CONTROL_SERVICE_TYPE,
            controlInstance,
            txt,
        ),
    )

    @Test
    fun encodeNameWritesLengthPrefixedLabels() {
        assertArrayEquals(
            byteArrayOf(8) + "_airplay".toByteArray() +
                byteArrayOf(4) + "_tcp".toByteArray() +
                byteArrayOf(5) + "local".toByteArray() +
                byteArrayOf(0),
            CarPlayMdnsProtocol.encodeName("_airplay._tcp.local."),
        )
    }

    @Test
    fun encodeTxtPrefixesEveryEntryWithItsLength() {
        val encoded = CarPlayMdnsProtocol.encodeTxt(linkedMapOf("a" to "1", "bb" to "22"))
        // "a=1" is three bytes, "bb=22" is five: the prefix counts the whole key=value entry.
        assertArrayEquals(
            byteArrayOf(3, 'a'.code.toByte(), '='.code.toByte(), '1'.code.toByte(), 5, 'b'.code.toByte(), 'b'.code.toByte(), '='.code.toByte(), '2'.code.toByte(), '2'.code.toByte()),
            encoded,
        )
    }

    @Test
    fun encodeTxtOfNothingIsASingleEmptyEntry() {
        assertArrayEquals(byteArrayOf(0), CarPlayMdnsProtocol.encodeTxt(emptyMap()))
    }

    @Test
    fun questionsParsesNamesAndTypes() {
        val packet = query(
            question("_airplay._tcp.local", CarPlayMdnsProtocol.TYPE_ANY),
        )
        assertEquals(
            listOf(CarPlayMdnsProtocol.Question("_airplay._tcp.local", CarPlayMdnsProtocol.TYPE_ANY)),
            CarPlayMdnsProtocol.questions(packet, packet.size),
        )
    }

    @Test
    fun questionsFollowsCompressionPointers() {
        val first = CarPlayMdnsProtocol.encodeName("_airplay._tcp.local")
        val packet = (
            header(questionCount = 2) + first + byteArrayOf(0, 33, 0, 1) +
                byteArrayOf(0xc0.toByte(), 0x0c) + byteArrayOf(0, 12, 0, 1)
            )
        assertEquals(
            listOf(
                CarPlayMdnsProtocol.Question("_airplay._tcp.local", CarPlayMdnsProtocol.TYPE_SRV),
                CarPlayMdnsProtocol.Question("_airplay._tcp.local", CarPlayMdnsProtocol.TYPE_PTR),
            ),
            CarPlayMdnsProtocol.questions(packet, packet.size),
        )
    }

    @Test
    fun answersForServiceQueryCoversEveryRecordFamily() {
        val records = answersFor("_airplay._tcp.local", CarPlayMdnsProtocol.TYPE_ANY)
        assertEquals(
            setOf(
                CarPlayMdnsProtocol.TYPE_PTR,
                CarPlayMdnsProtocol.TYPE_SRV,
                CarPlayMdnsProtocol.TYPE_TXT,
                CarPlayMdnsProtocol.TYPE_A,
                CarPlayMdnsProtocol.TYPE_AAAA,
            ),
            records.map { it.type }.toSet(),
        )
        val srv = records.single { it.type == CarPlayMdnsProtocol.TYPE_SRV }
        assertTrue(srv.cacheFlush)
        // Full rdata: priority, weight, port, target. A short rdata here would make the handset
        // read the port out of the target name and connect nowhere.
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0x1b, 0x58) + CarPlayMdnsProtocol.encodeName(host),
            srv.data,
        )
        assertEquals(instance, srv.name)
        val ptr = records.single { it.type == CarPlayMdnsProtocol.TYPE_PTR }
        assertEquals("_airplay._tcp.local", ptr.name)
        assertFalse(ptr.cacheFlush)
        val address = records.single { it.type == CarPlayMdnsProtocol.TYPE_A }
        assertArrayEquals(ipv4, address.data)
    }

    @Test
    fun answersForHostQueryReturnsAddressesOnly() {
        val records = answersFor("xcertplay.local", CarPlayMdnsProtocol.TYPE_A)
        assertEquals(
            setOf(CarPlayMdnsProtocol.TYPE_A, CarPlayMdnsProtocol.TYPE_AAAA),
            records.map { it.type }.toSet(),
        )
    }

    @Test
    fun answersForServicesMetaPointsAtEveryOwnedService() {
        val records = answersFor(
            CarPlayMdnsProtocol.SERVICES_META_TYPE,
            CarPlayMdnsProtocol.TYPE_PTR,
        )
        assertTrue(records.all { it.type == CarPlayMdnsProtocol.TYPE_PTR })
        val advertised = records.map { String(it.data.copyOfRange(1, 1 + it.data[0].toInt())) }
        assertEquals(
            listOf("_airplay._tcp.local", "_carplay-ctrl._tcp.local"),
            advertised,
        )
    }

    @Test
    fun answersForCarPlayControlQueryIsAnswered() {
        // The handset browses _carplay-ctrl._tcp before offering the CarPlay entry. Answering only
        // AirPlay leaves it with nothing to show, which is what forces a manual tap.
        val records = answersFor(
            CarPlayMdnsProtocol.CARPLAY_CONTROL_SERVICE_TYPE,
            CarPlayMdnsProtocol.TYPE_PTR,
        )
        assertEquals(
            setOf(
                CarPlayMdnsProtocol.TYPE_PTR,
                CarPlayMdnsProtocol.TYPE_SRV,
                CarPlayMdnsProtocol.TYPE_TXT,
                CarPlayMdnsProtocol.TYPE_A,
                CarPlayMdnsProtocol.TYPE_AAAA,
            ),
            records.map { it.type }.toSet(),
        )
        val ptr = records.single { it.type == CarPlayMdnsProtocol.TYPE_PTR }
        assertEquals("_carplay-ctrl._tcp.local", ptr.name)
        assertEquals(controlInstance, records.single { it.type == CarPlayMdnsProtocol.TYPE_SRV }.name)
    }

    @Test
    fun answersForCarPlayControlInstanceQueryIsAnswered() {
        val records = answersFor(controlInstance, CarPlayMdnsProtocol.TYPE_SRV)
        assertEquals(
            setOf(CarPlayMdnsProtocol.TYPE_SRV, CarPlayMdnsProtocol.TYPE_TXT),
            records.map { it.type }.toSet(),
        )
    }

    @Test
    fun answersForUnrelatedQueryStaySilent() {
        assertEquals(emptyList<CarPlayMdnsRecord>(), answersFor("_raop._tcp.local", CarPlayMdnsProtocol.TYPE_PTR))
        assertEquals(emptyList<CarPlayMdnsRecord>(), answersFor("_airplay._tcp.local", CarPlayMdnsProtocol.TYPE_A))
    }

    @Test
    fun encodeResponseCarriesCountsAndCacheFlushBits() {
        val questions = listOf(
            CarPlayMdnsProtocol.Question("_airplay._tcp.local", CarPlayMdnsProtocol.TYPE_ANY),
        )
        val records = CarPlayMdnsProtocol.answersFor(
            questions = questions,
            services = services,
            hostName = host,
            port = 7000,
            ipv4 = ipv4,
            ipv6 = null,
        )
        val response = CarPlayMdnsProtocol.encodeResponse(0, questions, records)
        assertEquals(4, records.size)
        assertEquals(0, response[0].toInt())
        assertEquals(0, response[1].toInt())
        // QR and AA set, RCODE zero.
        assertEquals(0x84, response[2].toInt() and 0xff)
        assertEquals(0x00, response[3].toInt() and 0xff)
        // Every count is big endian, so the low byte carries the value for small numbers.
        assertEquals(0, response[4].toInt() and 0xff)
        assertEquals(questions.size, response[5].toInt() and 0xff)
        assertEquals(0, response[6].toInt() and 0xff)
        assertEquals(records.size, response[7].toInt() and 0xff)
        assertEquals(0, response[8].toInt() and 0xff)
        assertEquals(0, response[9].toInt() and 0xff)
        assertEquals(0, response[10].toInt() and 0xff)
        assertEquals(0, response[11].toInt() and 0xff)
        assertTrue(response.size > 12)
    }

    @Test
    fun announcementEchoesNoQuestionsAndCarriesEveryRecord() {
        val records = CarPlayMdnsProtocol.answersFor(
            questions = listOf(
                CarPlayMdnsProtocol.Question("_airplay._tcp.local", CarPlayMdnsProtocol.TYPE_ANY),
            ),
            services = services,
            hostName = host,
            port = 7000,
            ipv4 = ipv4,
            ipv6 = null,
        )
        val announcement = CarPlayMdnsProtocol.encodeAnnouncement(records)
        // The announcement echoes no questions, only answers.
        assertEquals(0, announcement[4].toInt() and 0xff)
        assertEquals(0, announcement[5].toInt() and 0xff)
        assertEquals(0, announcement[6].toInt() and 0xff)
        assertEquals(records.size, announcement[7].toInt() and 0xff)
        // An unsolicited announcement still must not cache-flush the shared service PTR.
        assertTrue(records.filter { it.type != CarPlayMdnsProtocol.TYPE_PTR }.all { it.cacheFlush })
        assertFalse(records.first { it.type == CarPlayMdnsProtocol.TYPE_PTR }.cacheFlush)
    }

    private fun answersFor(name: String, type: Int) = CarPlayMdnsProtocol.answersFor(
        questions = listOf(CarPlayMdnsProtocol.Question(name, type)),
        services = services,
        hostName = host,
        port = 7000,
        ipv4 = ipv4,
        ipv6 = ipv6,
    )

    private fun query(question: ByteArray): ByteArray = header(questionCount = 1) + question

    private fun header(questionCount: Int): ByteArray = byteArrayOf(
        0, 0,
        0, 0,
        0, questionCount.toByte(),
        0, 0,
        0, 0,
        0, 0,
    )

    private fun question(name: String, type: Int): ByteArray =
        CarPlayMdnsProtocol.encodeName(name) + byteArrayOf(0, type.toByte(), 0, 1)
}

class HardwareAddressGuardTest {
    @Test
    fun acceptsRealAddresses() {
        assertEquals("e2:34:20:02:dc:5b", usableHardwareAddressOrNull("e2:34:20:02:dc:5b"))
        assertEquals("E0:34:20:02:DC:5B", usableHardwareAddressOrNull(" E0:34:20:02:DC:5B "))
    }

    @Test
    fun rejectsTheWholeAndroidPlaceholderFamily() {
        assertNull(usableHardwareAddressOrNull("02:00:00:00:00:00"))
        assertNull(usableHardwareAddressOrNull("02:00:00:00:00:01"))
        assertNull(usableHardwareAddressOrNull("02:00:00:00:00:02"))
        assertNull(usableHardwareAddressOrNull("02:00:00:00:00:ff"))
    }

    @Test
    fun rejectsZeroAndMalformedValues() {
        assertNull(usableHardwareAddressOrNull("00:00:00:00:00:00"))
        assertNull(usableHardwareAddressOrNull(""))
        assertNull(usableHardwareAddressOrNull(null))
        assertNull(usableHardwareAddressOrNull("e2:34:20:02:dc"))
        assertNull(usableHardwareAddressOrNull("not-a-mac"))
    }
}

class InterfaceHardwareAddressTest {
    @Test
    fun formatsARealFrameworkAnswer() {
        assertEquals(
            "e2:34:20:02:dc:5b",
            hardwareAddressText(byteArrayOf(0xe2.toByte(), 0x34, 0x20, 0x02, 0xdc.toByte(), 0x5b)),
        )
    }

    @Test
    fun refusesToFormatAnEmptyOrAllZeroAnswer() {
        assertNull(hardwareAddressText(null))
        assertNull(hardwareAddressText(ByteArray(0)))
        assertNull(hardwareAddressText(ByteArray(6)))
    }

    @Test
    fun keepsAPlaceholderSoTheCallerKnowsTheFrameworkAnswered() {
        // The distinction matters: a placeholder is "the framework said something, it is just not
        // usable", which is the signal that the sysfs read is worth doing. Collapsing it to null
        // here would hide that signal.
        assertEquals("02:00:00:00:00:02", hardwareAddressText(byteArrayOf(0x02, 0, 0, 0, 0, 0x02)))
    }

    @Test
    fun fallsBackToSysfsWhenTheFrameworkReportsAPlaceholder() {
        // The defect this covers: judging the sysfs read by "the framework returned null, empty or
        // all-zero" lets the masked 02:00:00:00:00:02 take the framework branch, be rejected by
        // the guard, and leave the sysfs address already skipped. The real address was readable
        // the whole time and the handset was told to pair with a MAC that is not on the link.
        assertEquals(
            "e2:34:20:02:dc:5b",
            resolveInterfaceHardwareAddress("02:00:00:00:00:02", "e2:34:20:02:dc:5b"),
        )
    }

    @Test
    fun prefersAUsableFrameworkAnswerOverSysfs() {
        assertEquals(
            "e2:34:20:02:dc:5b",
            resolveInterfaceHardwareAddress("e2:34:20:02:dc:5b", "11:22:33:44:55:66"),
        )
    }

    @Test
    fun fallsBackToSysfsWhenTheFrameworkAnsweredNothing() {
        assertEquals(
            "e2:34:20:02:dc:5b",
            resolveInterfaceHardwareAddress(null, "e2:34:20:02:dc:5b"),
        )
    }

    @Test
    fun reportsNothingWhenNeitherSourceIsUsable() {
        assertNull(resolveInterfaceHardwareAddress("02:00:00:00:00:02", null))
        assertNull(resolveInterfaceHardwareAddress(null, "02:00:00:00:00:02"))
        assertNull(resolveInterfaceHardwareAddress(null, "00:00:00:00:00:00"))
        assertNull(resolveInterfaceHardwareAddress("02:00:00:00:00:02", "not-a-mac"))
    }
}
