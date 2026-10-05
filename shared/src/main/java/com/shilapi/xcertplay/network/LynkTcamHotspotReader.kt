package com.shilapi.xcertplay.network

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads the car's factory hotspot straight from the TCAM module.
 *
 * A Geely/ECARX head unit does not expose its hotspot through Android: `getWifiApState` and
 * `isWifiApEnabled` are gone from the platform, the sticky `WIFI_AP_STATE_CHANGED` broadcast is
 * never sent, and `/data/system/car/wifi_ap_state` does not exist on this firmware (the car's own
 * power service logs `ENOENT` for it). The result is that a manual-hotspot session has no real
 * SSID, passphrase, BSSID or channel to advertise, so it falls back to a placeholder identity and
 * the phone never joins.
 *
 * The TCAM service is the ingress the car itself uses. It is a plain Binder service with no SDK
 * jar and no platform signature needed, so it is reached through raw transactions:
 *
 *   component  : com.autolink.tcamservice / com.autolink.tcamservice.TcamService
 *   descriptor : com.autolink.adapterbinder.ITcamService
 *   transaction 12 -> AP state   (readInt; 11 and 13 mean enabled)
 *   transaction 15 -> AP config  (readInt status; then state, SSID, passphrase, unused,
 *                                 band, channel, unused)
 *
 * The passphrase comes back as plain text. Every read is guarded by `dataAvail()` so a firmware
 * that answers with a shorter parcel degrades to "unavailable" instead of reading garbage.
 */
internal object LynkTcamHotspotReader {

    private const val TAG = "xcertplay-usb"

    private const val TCAM_PACKAGE = "com.autolink.tcamservice"
    private const val TCAM_SERVICE = "com.autolink.tcamservice.TcamService"
    private const val TCAM_DESCRIPTOR = "com.autolink.adapterbinder.ITcamService"

    private const val TRANSACTION_AP_STATE = 12
    private const val TRANSACTION_AP_CONFIG = 15

    /** Bind timeout. The caller always has a longer budget than this, so it stays a bounded step. */
    private const val BIND_TIMEOUT_MILLIS = 4_500L

    private const val AP_STATE_ENABLING = 12
    private const val AP_STATE_ENABLED = 13

    /** What the TCAM module reported, or nulls when it could not be read. */
    data class Configuration(
        val enabled: Boolean?,
        val ssid: String?,
        val passphrase: String?,
        val band: Int?,
        val channel: Int?,
    ) {
        val hasCredentials: Boolean
            get() = !ssid.isNullOrBlank()
    }

    @Volatile
    private var cached: Configuration? = null

    @Volatile
    private var cacheAtNanos: Long = 0

    private val cacheTtlNanos = 5_000_000_000L

    /**
     * Reads the hotspot configuration, coalescing concurrent callers.
     *
     * Returns null when the module is not present or answers with something this build does not
     * understand - the caller then keeps its configured fallback rather than advertising junk.
     */
    fun read(context: Context): Configuration? {
        val now = System.nanoTime()
        cached?.let { if (now - cacheAtNanos < cacheTtlNanos) return it }
        val fresh = runCatching { readBlocking(context.applicationContext) }
            .onFailure { Log.i(TAG, "TCAM hotspot read failed (${it.javaClass.simpleName}: ${it.message})") }
            .getOrNull()
        if (fresh != null) {
            cached = fresh
            cacheAtNanos = now
        }
        return fresh
    }

    /** Drops the cache, so a settings change is reflected immediately. */
    fun invalidate() {
        cached = null
        cacheAtNanos = 0
    }

    private fun readBlocking(context: Context): Configuration? {
        val binder = awaitBinder(context) ?: return null
        val state = runCatching { readApState(binder) }.getOrNull()
        val config = runCatching { readApConfiguration(binder) }.getOrNull()
        return config?.copy(enabled = state?.let { isEnabledState(it) } ?: config.enabled)
            ?: state?.let { Configuration(isEnabledState(it), null, null, null, null) }
    }

    /** 11 = enabling, 13 = enabled; everything else is off or unknown. */
    internal fun isEnabledState(state: Int): Boolean =
        state == AP_STATE_ENABLED || state == AP_STATE_ENABLING

    /**
     * Binds and waits a bounded time for the connection.
     *
     * A plain latch instead of a coroutine: this module has no coroutines dependency, and the
     * wait must not happen on the main thread.
     */
    private fun awaitBinder(context: Context): IBinder? {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<IBinder?>(null)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                holder.set(service)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val intent = Intent().setComponent(ComponentName(TCAM_PACKAGE, TCAM_SERVICE))
        val bound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            Log.i(TAG, "TCAM service not present ($TCAM_PACKAGE/$TCAM_SERVICE)")
            return null
        }
        val connected = runCatching { latch.await(BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        runCatching { context.unbindService(connection) }
        if (!connected) Log.i(TAG, "TCAM bind timed out after ${BIND_TIMEOUT_MILLIS}ms")
        return holder.get()
    }

    private fun readApState(binder: IBinder): Int = transact(binder, TRANSACTION_AP_STATE) { reply ->
        require(reply.dataAvail() >= 4) { "Missing TCAM state" }
        reply.readInt()
    } ?: throw IllegalStateException("TCAM AP state unavailable")

    private fun readApConfiguration(binder: IBinder): Configuration? =
        transact(binder, TRANSACTION_AP_CONFIG) { reply ->
            require(reply.dataAvail() >= 4) { "Missing TCAM configuration header" }
            val status = reply.readInt()
            // Any non-zero status means "no configuration available", which is normal when the
            // hotspot is off. It is not an error.
            if (status != 0) return@transact null
            require(reply.dataAvail() >= 12) { "Incomplete TCAM configuration" }
            reply.readInt()                                   // state
            val ssid = reply.readString()
            val passphrase = reply.readString()
            reply.readString()                                // third value, unused by this build
            val band = reply.readInt()
            val channel = reply.readInt()
            reply.readInt()                                   // third value, unused by this build
            Configuration(
                enabled = null,
                ssid = ssid?.takeIf { it.isNotBlank() },
                passphrase = passphrase,
                band = band,
                channel = channel,
            )
        }

    /**
     * Runs one transaction with the vendored descriptor check.
     *
     * The descriptor is validated before anything is written, so a service that merely shares the
     * component name cannot be mistaken for the TCAM module.
     */
    private fun <T> transact(binder: IBinder, code: Int, read: (Parcel) -> T): T? {
        if (binder.interfaceDescriptor != TCAM_DESCRIPTOR) {
            Log.i(
                TAG,
                "TCAM descriptor mismatch: ${binder.interfaceDescriptor} != $TCAM_DESCRIPTOR",
            )
            return null
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TCAM_DESCRIPTOR)
            if (!binder.transact(code, data, reply, 0)) return null
            reply.readException()
            read(reply)
        } catch (e: Exception) {
            Log.i(TAG, "TCAM transaction $code failed (${e.javaClass.simpleName}: ${e.message})")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
