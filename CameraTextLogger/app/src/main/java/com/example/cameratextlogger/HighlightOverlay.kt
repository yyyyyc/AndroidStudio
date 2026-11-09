package com.example.cameratextlogger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Draws boxes over found text on top of other apps, like a highlighter pen. Touches pass through
 * to the app underneath. Needs the "Display over other apps" permission; use on the main thread.
 */
class HighlightOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: BoxesView? = null

    /** Replaces the boxes shown with [boxes], in screen pixels. */
    fun show(boxes: List<HighlightBox>) {
        val current = view ?: if (boxes.isEmpty()) return else add() ?: return
        // Redrawing changes the screen, which sends the scanner another screenshot: only when needed.
        if (current.boxes == boxes) return
        current.boxes = boxes
        current.invalidate()
    }

    fun clear() = show(emptyList())

    fun remove() {
        view?.let { windowManager.removeView(it) }
        view = null
    }

    private fun add(): BoxesView? {
        if (!canDraw(context)) return null
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Android 12 blocks touches through overlays more opaque than this.
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        }
        return try {
            BoxesView(context).also {
                windowManager.addView(it, params)
                view = it
            }
        } catch (e: RuntimeException) {
            // The permission can be withdrawn while a session runs.
            Log.w(TAG, "Could not show highlights", e)
            null
        }
    }

    private class BoxesView(context: Context) : View(context) {

        var boxes: List<HighlightBox> = emptyList()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x73FFEB3B }
        private val padding = 4 * resources.displayMetrics.density
        private val location = IntArray(2)

        override fun onDraw(canvas: Canvas) {
            // The boxes are in screen pixels; the window may not start at the screen's corner.
            getLocationOnScreen(location)
            canvas.translate(-location[0].toFloat(), -location[1].toFloat())
            for (box in boxes) {
                canvas.drawRoundRect(
                    box.left - padding, box.top - padding, box.right + padding, box.bottom + padding,
                    padding, padding, paint,
                )
            }
        }
    }

    companion object {
        fun canDraw(context: Context) = Settings.canDrawOverlays(context)
    }
}
