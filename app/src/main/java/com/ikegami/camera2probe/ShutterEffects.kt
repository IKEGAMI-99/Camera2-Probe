package com.ikegami.camera2probe

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** Visual shutter feedback that does not interfere with the proven camera click handler. */
object ShutterEffects {
    fun install(activity: Activity) {
        val button = activity.findViewById<View>(R.id.captureButton) ?: return
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    button.animate().scaleX(0.95f).scaleY(0.95f).setDuration(45L).start()
                }
                MotionEvent.ACTION_UP -> {
                    button.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
                    showFlash(activity)
                    showPulse(activity, button)
                }
                MotionEvent.ACTION_CANCEL -> {
                    button.animate().scaleX(1f).scaleY(1f).setDuration(80L).start()
                }
            }
            false // preserve the Activity's existing OnClickListener and actual capture action.
        }
    }

    private fun showFlash(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val flash = View(activity).apply {
            setBackgroundColor(Color.rgb(210, 255, 248))
            alpha = 0f
            isClickable = false
            isFocusable = false
        }
        root.addView(
            flash,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        flash.animate().alpha(0.20f).setDuration(35L).withEndAction {
            flash.animate().alpha(0f).setDuration(135L).withEndAction {
                try { root.removeView(flash) } catch (_: Throwable) {}
            }.start()
        }.start()
    }

    private fun showPulse(activity: Activity, button: View) {
        val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        val density = activity.resources.displayMetrics.density
        val location = IntArray(2)
        val rootLocation = IntArray(2)
        button.getLocationOnScreen(location)
        root.getLocationOnScreen(rootLocation)

        val size = (button.width.coerceAtLeast(button.height) + 24f * density).roundToInt()
        val left = location[0] - rootLocation[0] + button.width / 2 - size / 2
        val top = location[1] - rootLocation[1] + button.height / 2 - size / 2
        val stroke = (2.3f * density).roundToInt().coerceAtLeast(2)

        val pulse = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(stroke, Color.rgb(91, 255, 220))
            }
            alpha = 0.95f
            scaleX = 0.72f
            scaleY = 0.72f
            isClickable = false
        }
        root.addView(
            pulse,
            FrameLayout.LayoutParams(size, size).apply {
                leftMargin = left
                topMargin = top
            }
        )
        pulse.animate()
            .scaleX(1.65f)
            .scaleY(1.65f)
            .alpha(0f)
            .setDuration(300L)
            .withEndAction {
                try { root.removeView(pulse) } catch (_: Throwable) {}
            }
            .start()
    }
}
