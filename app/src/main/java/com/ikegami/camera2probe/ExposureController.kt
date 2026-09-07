package com.ikegami.camera2probe

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.util.Range
import android.util.Rational
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Shared one-third-stop AE exposure compensation for all three physical cameras. */
object ExposureController {
    private const val PREFS = "tri_cam_exposure"
    private const val KEY_EV = "ev"
    private val panels = WeakHashMap<TriCamActivity, View>()
    private val pending = WeakHashMap<TriCamActivity, Runnable>()

    fun install(activity: TriCamActivity) {
        if (panels.containsKey(activity)) {
            reapplySoon(activity)
            return
        }
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? LinearLayout ?: return
        val dock = activity.findViewById<View>(R.id.bottomActionBar) ?: return

        val spec = queryCommonRange(activity)
        val savedThird = evToThird(currentEv(activity)).coerceIn(spec.minThird, spec.maxThird)
        val savedEv = savedThird / 3f
        storeEv(activity, savedEv)

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 14), dp(activity, 7), dp(activity, 14), dp(activity, 6))
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 18).toFloat()
                setColor(Color.rgb(8, 18, 31))
                setStroke(dp(activity, 1), Color.rgb(33, 95, 111))
            }
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(activity).apply {
            text = "EXPOSURE · ALL 3 CAMERAS · 1/3 EV"
            textSize = 9.5f
            setTextColor(Color.rgb(190, 219, 231))
            letterSpacing = 0.06f
        }
        val valueText = TextView(activity).apply {
            textSize = 10.5f
            setTextColor(Color.rgb(91, 246, 201))
            gravity = Gravity.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(valueText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(header)

        val seek = SeekBar(activity).apply {
            max = (spec.maxThird - spec.minThird).coerceAtLeast(1)
            progress = (savedThird - spec.minThird).coerceIn(0, max)
            splitTrack = false
            tickMark = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(dp(activity, 3), dp(activity, 3))
                setColor(Color.rgb(74, 111, 126))
            }
        }
        panel.addView(seek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 34)))

        val scale = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minText = TextView(activity).apply {
            text = formatThird(spec.minThird)
            textSize = 7f
            setTextColor(Color.rgb(75, 96, 112))
        }
        val centerText = TextView(activity).apply {
            text = "●  0 EV  ·  each dot = 1/3"
            textSize = 7f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(91, 126, 140))
        }
        val maxText = TextView(activity).apply {
            text = formatThird(spec.maxThird)
            textSize = 7f
            gravity = Gravity.END
            setTextColor(Color.rgb(75, 96, 112))
        }
        scale.addView(minText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        scale.addView(centerText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        scale.addView(maxText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(scale)

        fun thirdForProgress(progress: Int): Int =
            (spec.minThird + progress).coerceIn(spec.minThird, spec.maxThird)
        fun updateValue(third: Int) { valueText.text = formatThird(third) }
        updateValue(savedThird)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val third = thirdForProgress(progress)
                val ev = third / 3f
                updateValue(third)
                storeEv(activity, ev)
                pending.remove(activity)?.let { activity.window.decorView.removeCallbacks(it) }
                val task = Runnable { apply(activity, ev) }
                pending[activity] = task
                activity.window.decorView.postDelayed(task, 35L)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val third = thirdForProgress(seek.progress)
                seek.progress = third - spec.minThird
                apply(activity, third / 3f)
            }
        })

        val insertIndex = root.indexOfChild(dock).let { if (it >= 0) it else root.childCount }
        root.addView(
            panel,
            insertIndex,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 84)).apply {
                topMargin = dp(activity, 8)
            }
        )
        panels[activity] = panel
        reapplySoon(activity)
    }

    fun reapplySoon(activity: TriCamActivity) {
        val ev = currentEv(activity)
        listOf(100L, 450L, 900L, 1600L).forEach { delay ->
            activity.window.decorView.postDelayed({ apply(activity, ev) }, delay)
        }
    }

    fun currentEv(activity: Activity): Float =
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getFloat(KEY_EV, 0f)

    fun applyToBuilder(activity: TriCamActivity, builder: CaptureRequest.Builder, physicalIds: List<String>) {
        val ev = currentEv(activity)
        val manager = activity.getSystemService(CameraManager::class.java)
        val logicalId = runCatching { getField<String?>(activity, "logicalRearId") }.getOrNull()
        if (logicalId != null) {
            runCatching {
                builder.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    evToIndex(ev, manager.getCameraCharacteristics(logicalId))
                )
            }
        }
        physicalIds.forEach { pid ->
            runCatching {
                builder.setPhysicalCameraKey(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    evToIndex(ev, manager.getCameraCharacteristics(pid)),
                    pid
                )
            }
        }
    }

    fun apply(activity: TriCamActivity, ev: Float) {
        val snapped = evToThird(ev) / 3f
        storeEv(activity, snapped)
        try {
            val physicalIds = getField<List<String>>(activity, "physicalIds")
            val camera = getField<CameraDevice?>(activity, "cameraDevice") ?: return
            val session = getField<CameraCaptureSession?>(activity, "captureSession") ?: return
            val surfaces = getField<List<Surface>>(activity, "previewSurfaces")
            val handler = getField<Handler?>(activity, "cameraHandler") ?: return
            val afModeById = getField<MutableMap<String, Int>>(activity, "afModeById")
            val capturing = getField<Boolean>(activity, "captureInProgress")
            if (capturing || physicalIds.size != 3 || surfaces.size != 3) return

            handler.post {
                runCatching {
                    val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    surfaces.forEach { b.addTarget(it) }
                    b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    physicalIds.forEach { pid ->
                        val af = afModeById[pid] ?: CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        runCatching { b.setPhysicalCameraKey(CaptureRequest.CONTROL_AF_MODE, af, pid) }
                        runCatching { b.setPhysicalCameraKey(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON, pid) }
                        runCatching { b.setPhysicalCameraKey(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO, pid) }
                    }
                    applyToBuilder(activity, b, physicalIds)
                    session.setRepeatingRequest(
                        b.build(),
                        object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                invokeUpdate3A(activity, result)
                            }
                        },
                        handler
                    )
                }
            }
        } catch (_: Throwable) {
            // Camera may still be starting; scheduled retries will reapply this EV.
        }
    }

    private data class Spec(val minThird: Int, val maxThird: Int)

    private fun queryCommonRange(activity: TriCamActivity): Spec {
        return try {
            val manager = activity.getSystemService(CameraManager::class.java)
            val logical = manager.cameraIdList.firstOrNull { id ->
                val c = manager.getCameraCharacteristics(id)
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) &&
                    c.physicalCameraIds.size >= 3
            }
            val ids = if (logical != null) {
                val c = manager.getCameraCharacteristics(logical)
                listOf(logical) + c.physicalCameraIds.toList()
            } else emptyList()

            var minEv = -3f
            var maxEv = 3f
            ids.forEach { id ->
                val c = manager.getCameraCharacteristics(id)
                val range = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return@forEach
                val step = stepFloat(c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP))
                minEv = maxOf(minEv, range.lower * step)
                maxEv = minOf(maxEv, range.upper * step)
            }
            val minThird = ceil(minEv * 3f).toInt()
            val maxThird = floor(maxEv * 3f).toInt()
            if (minThird >= maxThird) Spec(-6, 6) else Spec(minThird, maxThird)
        } catch (_: Throwable) {
            Spec(-6, 6)
        }
    }

    private fun evToIndex(ev: Float, chars: CameraCharacteristics): Int {
        val range: Range<Int> = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val step = stepFloat(chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)).takeIf { it > 0f } ?: 1f
        return (ev / step).roundToInt().coerceIn(range.lower, range.upper)
    }

    private fun evToThird(ev: Float): Int = (ev * 3f).roundToInt()

    private fun formatThird(third: Int): String {
        if (third == 0) return "0 EV"
        val sign = if (third > 0) "+" else "−"
        val a = abs(third)
        val whole = a / 3
        val rem = a % 3
        val body = when (rem) {
            0 -> whole.toString()
            1 -> if (whole == 0) "⅓" else "$whole⅓"
            else -> if (whole == 0) "⅔" else "$whole⅔"
        }
        return "$sign$body EV"
    }

    private fun stepFloat(r: Rational?): Float =
        if (r == null || r.denominator == 0) 1f else r.numerator.toFloat() / r.denominator.toFloat()

    private fun storeEv(activity: Activity, ev: Float) {
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putFloat(KEY_EV, ev).apply()
    }

    private fun invokeUpdate3A(activity: TriCamActivity, result: TotalCaptureResult) {
        runCatching {
            val m = TriCamActivity::class.java.getDeclaredMethod("update3AState", TotalCaptureResult::class.java)
            m.isAccessible = true
            m.invoke(activity, result)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(activity: TriCamActivity, name: String): T {
        val f = TriCamActivity::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(activity) as T
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()
}
