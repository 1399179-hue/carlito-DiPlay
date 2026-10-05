package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display

/**
 * Finds the instrument-cluster display a Geely head unit exposes for navigation projection.
 *
 * The cluster is published as an ordinary presentation display, so no vendor API is needed to
 * enumerate it - only [DisplayManager.getDisplays] with `DISPLAY_CATEGORY_PRESENTATION`. What the
 * car controls is whether that display is *shown*; see [GeelyClusterDisplayControl].
 *
 * A 领克 03 publishes it as uniqueId `local:2` at 1920x720, which is preferred when present. The
 * fallback accepts the largest other presentation display, because the uniqueId is firmware data
 * and a different Geely variant may well use another one.
 */
internal object GeelyClusterDisplayTargets {
    private const val TAG = "xcertplay-usb"

    /** Measured on a 领克 03; used as a preference, never as a hard requirement. */
    const val LYNK_03_UNIQUE_ID = "local:2"
    const val LYNK_03_WIDTH = 1920
    const val LYNK_03_HEIGHT = 720

    /** Smallest cluster worth projecting to; below this the display is not a cluster. */
    private const val MIN_WIDTH = 480
    private const val MIN_HEIGHT = 240

    data class Target(
        val display: Display,
        val uniqueId: String,
        val widthPixels: Int,
        val heightPixels: Int,
    ) {
        val isLynk03: Boolean
            get() = isLynk03Cluster(uniqueId, widthPixels, heightPixels)
    }

    /**
     * The cluster display, or null when this head unit has none.
     *
     * Gated on the projection service: a presentation display alone is not enough, because the car
     * only reveals it once projection is switched on, and showing a Presentation on a hidden
     * display would leave a window nobody can see.
     */
    fun find(context: Context): Target? {
        if (!GeelyClusterDisplayControl.available()) return null
        val candidates = candidates(context)
        return candidates.firstOrNull { it.isLynk03 }
            ?: candidates.maxByOrNull { it.widthPixels.toLong() * it.heightPixels }
    }

    fun candidates(context: Context): List<Target> =
        displays(context).mapNotNull { snapshot(it) }

    /** Every presentation display, for the log line that explains why nothing was found. */
    fun describe(context: Context): String {
        val all = displays(context).joinToString {
            val size = Point().also { point -> it.getRealSize(point) }
            "${it.name}[${uniqueIdOf(it) ?: "?"}] ${size.x}x${size.y} state=${it.state}"
        }
        return "projection=${GeelyClusterDisplayControl.available()} presentation=[$all]"
    }

    private fun displays(context: Context): List<Display> = runCatching {
        context.getSystemService(DisplayManager::class.java)
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.toList()
            .orEmpty()
    }.getOrElse {
        Log.w(TAG, "geely cluster display enumeration failed", it)
        emptyList()
    }

    private fun snapshot(display: Display): Target? = runCatching {
        if (!display.isValid || display.state != Display.STATE_ON) return null
        val size = Point().also { display.getRealSize(it) }
        if (size.x < MIN_WIDTH || size.y < MIN_HEIGHT) return null
        val uniqueId = uniqueIdOf(display) ?: return null
        Target(display, uniqueId, size.x, size.y)
    }.getOrNull()

    /** `Display.getUniqueId` is hidden on older platforms, so it is reached by reflection. */
    private fun uniqueIdOf(display: Display): String? = runCatching {
        Display::class.java.getMethod("getUniqueId").invoke(display) as? String
    }.getOrNull()
}

/**
 * True for the cluster display a 领克 03 publishes.
 *
 * The uniqueId and size are firmware data taken from a working build. They are only used to
 * *prefer* one candidate over another, never to reject the only candidate, so a different firmware
 * that renumbers the display still projects.
 */
internal fun isLynk03Cluster(uniqueId: String, widthPixels: Int, heightPixels: Int): Boolean =
    uniqueId == GeelyClusterDisplayTargets.LYNK_03_UNIQUE_ID &&
        widthPixels == GeelyClusterDisplayTargets.LYNK_03_WIDTH &&
        heightPixels == GeelyClusterDisplayTargets.LYNK_03_HEIGHT
