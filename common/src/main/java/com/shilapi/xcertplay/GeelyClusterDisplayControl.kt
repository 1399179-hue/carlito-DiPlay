package com.shilapi.xcertplay

import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Asks the car to hand its instrument cluster over to the navigation projection.
 *
 * On a Geely/ECARX head unit the cluster's projection display is owned by the vehicle, not by the
 * app: it stays hidden until the car's own navigation service says otherwise. The ECARX DIM
 * protocol exposes exactly that switch over Binder, so a third-party app can turn projection on and
 * restore it afterwards - no vendor SDK and no ADB shell are involved.
 *
 * Wire format, measured from a working 领克 03 build:
 *
 *  - the binder is the system service `DimNaviService`, obtained through the hidden
 *    `android.os.ServiceManager.checkService(String)`;
 *  - its interface descriptor is `ecarx.dimprotocol.custom.service.IDimNaviService`;
 *  - transaction 9 takes one int (1 = show the navigation projection, 0 = restore the cluster)
 *    and answers with an int, 1 meaning accepted;
 *  - transaction 10 takes nothing and answers with the same int, so the current state can be read
 *    back before deciding to change it.
 *
 * The service name and descriptor are validated before any transaction, because a wrong
 * `checkService` result would otherwise be transacted blindly.
 */
internal object GeelyClusterDisplayControl {
    private const val TAG = "xcertplay-usb"
    private const val SERVICE_NAME = "DimNaviService"
    private const val DESCRIPTOR = "ecarx.dimprotocol.custom.service.IDimNaviService"
    private const val TRANSACTION_SET_SHOWN = 9
    private const val TRANSACTION_IS_SHOWN = 10

    @Volatile private var cached: IBinder? = null
    @Volatile private var probed = false
    @Volatile private var lastFailure = "NONE"

    /**
     * The projection binder, or null when this head unit does not expose it.
     *
     * `ServiceManager` is a hidden API, so it is reached by reflection; the lookup result is cached
     * because `checkService` is not free and the answer cannot change while the process lives.
     */
    private fun binder(): IBinder? {
        cached?.let { if (it.isBinderAlive) return it }
        synchronized(this) {
            cached?.let { if (it.isBinderAlive) return it }
            if (probed && lastFailure != "NONE") return null
            probed = true
            val service = runCatching {
                Class.forName("android.os.ServiceManager")
                    .getMethod("checkService", String::class.java)
                    .invoke(null, SERVICE_NAME) as? IBinder
            }.getOrElse {
                lastFailure = "checkService ${it.javaClass.simpleName}: ${it.message?.take(120)}"
                null
            }
            if (service == null) {
                lastFailure = "service_not_published"
                return null
            }
            val descriptor = runCatching { service.interfaceDescriptor }.getOrNull()
            if (descriptor != DESCRIPTOR) {
                lastFailure = "unexpected_descriptor=$descriptor"
                return null
            }
            lastFailure = "NONE"
            cached = service
            return service
        }
    }

    /** True when this head unit exposes the ECARX cluster projection switch. */
    fun available(): Boolean = binder() != null

    /**
     * True while this process is the one that switched projection on.
     *
     * The car's projection mode is global, so releasing it without having taken it would switch off
     * a cluster another app or the vehicle itself is using.
     */
    private val holdsProjection = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Switches the cluster projection on.
     *
     * Returns true when projection is on and this process is responsible for restoring it. Safe to
     * call repeatedly. A false result means the caller keeps whatever fallback it had - the cluster
     * simply stays as it was.
     */
    fun acquire(): Boolean {
        if (!available()) return false
        if (holdsProjection.get()) return true
        if (!setShown(true)) return false
        holdsProjection.set(true)
        return true
    }

    /** Restores the cluster. Only does anything when [acquire] succeeded. */
    fun release() {
        if (!holdsProjection.compareAndSet(true, false)) return
        setShown(false)
    }

    /** Current projection state, or null when it cannot be read. */
    fun isShown(): Boolean? = call(TRANSACTION_IS_SHOWN, null)?.let { it != 0 }

    /**
     * Shows or restores the cluster's navigation projection.
     *
     * Returns true when the car accepted the change. A false result means the caller must keep its
     * own fallback: the cluster will simply stay as it was.
     */
    fun setShown(shown: Boolean): Boolean =
        call(TRANSACTION_SET_SHOWN) { writeInt(if (shown) 1 else 0) } == 1

    fun diagnostics(): String =
        "geelyCluster available=${binder() != null} shown=${isShown() ?: "unknown"} " +
            "held=${holdsProjection.get()} failure=$lastFailure"

    private fun call(code: Int, write: (Parcel.() -> Unit)? = null): Int? {
        val service = binder() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            write?.invoke(data)
            if (!service.transact(code, data, reply, 0)) {
                lastFailure = "transaction=$code unsupported"
                return null
            }
            // A service that answered with an empty reply would make readException throw.
            if (reply.dataAvail() < 4) {
                lastFailure = "transaction=$code empty_reply"
                return null
            }
            reply.readException()
            reply.readInt()
        } catch (error: Throwable) {
            lastFailure = "transaction=$code ${error.javaClass.simpleName}: ${error.message?.take(120)}"
            Log.w(TAG, "geely cluster projection transaction failed", error)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
