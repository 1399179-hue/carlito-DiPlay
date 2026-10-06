package com.shilapi.xcertplay.airplay

import java.net.Socket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bonjour responder probes our own control port with `GET /ctrl-int/1/connect` and closes
 * again. That connection must never be mistaken for a handset that dropped, because rebuilding the
 * stack on it produced an endless reconnect loop while the iPhone was still pairing.
 */
class AirPlaySessionProbeTest {
    @Test
    fun aFreshConnectionCarriesNoCarPlaySession() {
        assertFalse(testSession().carriedCarPlaySession)
    }

    @Test
    fun closingAProbeNeverClaimsItWasASession() {
        val session = testSession()
        session.close()
        assertFalse(session.carriedCarPlaySession)
    }

    @Test
    fun aSessionThatReachedSetupIsRememberedAsRealTraffic() {
        val session = testSession()
        val flag = AirPlaySession::class.java.getDeclaredField("carriedCarPlayTraffic").apply {
            isAccessible = true
        }
        flag.set(session, java.util.concurrent.atomic.AtomicBoolean(true))
        assertTrue(session.carriedCarPlaySession)
    }

    private fun testSession(): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )
}
