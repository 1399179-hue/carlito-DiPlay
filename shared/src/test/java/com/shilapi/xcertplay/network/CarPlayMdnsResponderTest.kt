package com.shilapi.xcertplay.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayMdnsResponderTest {
    private val instance = "xcertplay._airplay._tcp.local"
    private val host = "xcertplay.local"
    private val txt = CarPlayMdnsProtocol.encodeTxt(mapOf("deviceid" to "e2342002dc5b"))
    private val ipv4 = byteArrayOf(192.toByte(), 168.toByte(), 231.toByte(), 111)
    private val ipv6 = ByteArray(16) { if (it == 15) 1 else 0 }

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
    fun answersForServicesMetaPointsAtAirPlay() {
        val records = answersFor(
            CarPlayMdnsProtocol.SERVICES_META_TYPE,
            CarPlayMdnsProtocol.TYPE_PTR,
        )
        val ptr = records.single()
        assertEquals(CarPlayMdnsProtocol.TYPE_PTR, ptr.type)
        assertEquals("_airplay", String(ptr.data, 1, 8))
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
            instance = instance,
            hostName = host,
            port = 7000,
            ipv4 = ipv4,
            ipv6 = null,
            txt = txt,
        )
        val response = CarPlayMdnsProtocol.encodeResponse(0, questions, records)
        assertEquals(4, records.size)
        assertEquals(0, response[0].toInt())
        assertEquals(0, response[1].toInt())
        assertEquals(0x84, response[2].toInt() and 0xff)
        assertEquals(0x00, response[3].toInt() and 0xff)
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
            instance = instance,
            hostName = host,
            port = 7000,
            ipv4 = ipv4,
            ipv6 = null,
            txt = txt,
        )
        val announcement = CarPlayMdnsProtocol.encodeAnnouncement(records)
        assertEquals(0, announcement[4].toInt() and 0xff)
        assertEquals(0, announcement[5].toInt() and 0xff)
        assertEquals(0, announcement[6].toInt() and 0xff)
        assertEquals(records.size, announcement[7].toInt() and 0xff)
        assertTrue(records.filter { it.type != CarPlayMdnsProtocol.TYPE_PTR }.all { it.cacheFlush })
        assertFalse(records.first { it.type == CarPlayMdnsProtocol.TYPE_PTR }.cacheFlush)
    }

    private fun answersFor(name: String, type: Int) = CarPlayMdnsProtocol.answersFor(
        questions = listOf(CarPlayMdnsProtocol.Question(name, type)),
        instance = instance,
        hostName = host,
        port = 7000,
        ipv4 = ipv4,
        ipv6 = ipv6,
        txt = txt,
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
