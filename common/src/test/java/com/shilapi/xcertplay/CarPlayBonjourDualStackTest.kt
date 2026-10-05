package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.PairingStore
import java.net.ServerSocket
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.util.ReflectionHelpers
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayBonjourDualStackTest {
    private val config = AirPlayConfig(deviceName = "DiPlay", deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02", sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720))
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test-pairing")

    @Test fun dualLanThenSingleP2pThenDualLanServesAirPlayInfo() {
        val controller = Robolectric.buildService(CarPlayVpnService::class.java).create()
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        var activeSessions = 0
        try {
            val service = controller.get()
            for (extras in listOf(listOf(InetAddress.getByName("127.0.0.1")), emptyList(),
                                  listOf(InetAddress.getByName("127.0.0.1")))) {
                assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(
                    InetAddress.getByName("::1"), config.copy(port = 0), identity, PairingStore(), null,
                    object : AirPlaySessionListener {
                        override fun onDebugLog(message: String) { events.add(message) }
                        override fun onSessionActive(session: com.shilapi.xcertplay.airplay.AirPlaySession) { activeSessions++ }
                    }, object : AirPlayMediaHandler {}, extras))
                for (address in listOf(InetAddress.getByName("::1")) + extras) {
                    java.net.Socket(address, service.boundPort()!!).use { socket ->
                        socket.soTimeout = 3000
                        socket.getOutputStream().write("GET /info HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray())
                        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        assertEquals("HTTP/1.1 200 OK", input.readLine())
                        var length = 0
                        while (true) {
                            val line = input.readLine()
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:")) length = line.substringAfter(':').trim().toInt()
                        }
                        val body = CharArray(length)
                        var offset = 0
                        while (offset < length) { val read = input.read(body, offset, length - offset); assertTrue(read > 0); offset += read }
                        assertTrue(String(body).startsWith("bplist00"))
                    }
                }
                assertTrue(service.isAttached())
                service.detach()
            }
            assertEquals(0, activeSessions)
            assertEquals(5, events.count { it.startsWith("airplay rx GET /info") })
        } finally { controller.destroy() }
    }

    @Test fun serviceDetachesBothSamePortListenersOnAndroid10() {
        val controller = Robolectric.buildService(CarPlayVpnService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(
                InetAddress.getByName("::1"), config.copy(port = 0), identity, PairingStore(), null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
                listOf(InetAddress.getByName("127.0.0.1"))))
            val primary = ReflectionHelpers.getField<ServerSocket>(service, "serverSocket")
            val extra = ReflectionHelpers.getField<List<ServerSocket>>(service, "additionalServers").single()
            assertEquals(primary.localPort, extra.localPort)
            service.detach()
            assertTrue(primary.isClosed)
            assertTrue(extra.isClosed)
            assertFalse(service.isAttached())
            service.detach()
        } finally { controller.destroy() }
    }
}
