package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.shilapi.xcertplay.hud.CarPlayHudGuidance
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.min

/** CarPlay maneuver projection for a Geely head unit's secondary HUD display. */
internal object GeelyHudProjection : DisplayManager.DisplayListener {
    private const val TAG = "DiPlay-GeelyHud"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activityRef: WeakReference<Activity>? = null
    private var displayManager: DisplayManager? = null
    private var windowManager: WindowManager? = null
    private var hudView: GeelyHudGuidanceView? = null
    private var attachedDisplayId = Display.INVALID_DISPLAY
    private var guidance: CarPlayHudGuidance? = null

    fun attach(activity: Activity) {
        mainHandler.post {
            if (activityRef?.get() !== activity) {
                detachWindow()
                displayManager?.unregisterDisplayListener(this)
                activityRef = WeakReference(activity)
                displayManager = activity.getSystemService(DisplayManager::class.java)
                displayManager?.registerDisplayListener(this, mainHandler)
            }
            refresh()
        }
    }

    fun detach(activity: Activity) {
        mainHandler.post {
            if (activityRef?.get() !== activity) return@post
            detachWindow()
            displayManager?.unregisterDisplayListener(this)
            displayManager = null
            activityRef = null
        }
    }

    fun update(value: CarPlayHudGuidance?) {
        mainHandler.post {
            guidance = value
            refresh()
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        AirPlayPersistence.saveGeelyHudEnabled(context, enabled)
        if (enabled && context is Activity && !Settings.canDrawOverlays(context)) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }.onFailure { Log.w(TAG, "Could not open HUD display permission settings", it) }
        }
        mainHandler.post(::refresh)
    }

    override fun onDisplayAdded(displayId: Int) = refresh()
    override fun onDisplayRemoved(displayId: Int) = refresh()
    override fun onDisplayChanged(displayId: Int) = refresh()

    private fun refresh() {
        val activity = activityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            detachWindow()
            return
        }
        if (!AirPlayPersistence.loadGeelyHudEnabled(activity) ||
            guidance == null ||
            !Settings.canDrawOverlays(activity)
        ) {
            detachWindow()
            return
        }
        val display = displayManager?.displays
            ?.filter {
                it.displayId != Display.DEFAULT_DISPLAY &&
                    it.state != Display.STATE_OFF &&
                    !it.name.contains("com.byd.containerservice", ignoreCase = true) &&
                    !it.name.contains("ClusterProjectionDisplay", ignoreCase = true)
            }
            ?.let { displays ->
                displays.firstOrNull { it.name.contains("hud", ignoreCase = true) }
                    ?: displays.firstOrNull()
            }
        if (display == null) {
            detachWindow()
            return
        }
        if (attachedDisplayId != display.displayId) {
            detachWindow()
            val displayContext = activity.createDisplayContext(display)
            val manager = displayContext.getSystemService(WindowManager::class.java) ?: return
            val view = GeelyHudGuidanceView(displayContext)
            val params = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "DiPlay HUD Navigation"
            }
            try {
                manager.addView(view, params)
                windowManager = manager
                hudView = view
                attachedDisplayId = display.displayId
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not open the HUD display", error)
                runCatching { manager.removeViewImmediate(view) }
                return
            }
        }
        hudView?.guidance = guidance
    }

    private fun detachWindow() {
        val current = hudView
        hudView = null
        runCatching { current?.let { windowManager?.removeViewImmediate(it) } }
        windowManager = null
        attachedDisplayId = Display.INVALID_DISPLAY
    }
}

private class GeelyHudGuidanceView(context: Context) : View(context) {
    var guidance: CarPlayHudGuidance? = null
        set(value) {
            field = value
            invalidate()
        }

    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(115, 255, 198)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val route = guidance ?: return
        val heightScale = min(height.toFloat(), width * 0.45f)
        val centerY = height * 0.5f
        val iconSize = heightScale * 0.47f
        val left = width * 0.22f
        accent.textSize = iconSize
        val symbol = when (route.maneuverCode) {
            1 -> "←"
            2 -> "→"
            3 -> "↖"
            5 -> "↗"
            7 -> "↶"
            8 -> "↷"
            11 -> "↑"
            else -> ""
        }
        if (symbol.isNotEmpty()) {
            val metrics = accent.fontMetrics
            canvas.drawText(symbol, left - accent.measureText(symbol) / 2f, centerY - (metrics.ascent + metrics.descent) / 2f, accent)
        }

        val distance = if (route.distanceMeters >= 1_000) {
            String.format(Locale.getDefault(), "%.1f km", route.distanceMeters / 1_000f)
        } else {
            String.format(Locale.getDefault(), "%d m", route.distanceMeters.coerceAtLeast(0))
        }
        val textLeft = width * 0.43f
        accent.textSize = heightScale * 0.19f
        canvas.drawText(distance, textLeft, centerY + accent.textSize * 0.18f, accent)
        if (route.road.isNotBlank()) {
            roadPaint.textSize = heightScale * 0.095f
            val maxWidth = (width - textLeft - width * 0.07f).coerceAtLeast(0f)
            val road = TextUtils.ellipsize(route.road, android.text.TextPaint(roadPaint), maxWidth, TextUtils.TruncateAt.END)
            canvas.drawText(road.toString(), textLeft, centerY + accent.textSize * 0.75f, roadPaint)
        }
    }
}
