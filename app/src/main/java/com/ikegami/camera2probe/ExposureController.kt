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
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Shared AE exposure compensation for all three physical cameras. */
object ExposureController {
    private const val PREFS = "tri_cam_exposure"
    private const val KEY_EV = "ev"
    private const val UI_STEP_EV = 0.1f
    private val panels = WeakHashMap<TriCamActivity, View>()
    private val pending = WeakHashMap<TriCamActivity, Runnable>()

    fun install(activity: TriCamActivity) {
        if (panels.containsKey(activity)) {
            reapplySoon(activity)
            return
        }
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? LinearLayout ?: return

        val spec = queryCommonRange(activity)
        val saved = currentEv(activity).coerceIn(spec.minEv, spec.maxEv)
        storeEv(activity, saved)

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8))
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
            text = "EXPOSURE · ALL 3 CAMERAS"
            textSize = 10f
            setTextColor(Color.rgb(190, 219, 231))
            letterSpacing = 0.08f
        }
        val valueText = TextView(activity).apply {
            textSize = 11f
            setTextColor(Color.rgb(91, 246, 201))
            gravity = Gravity.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(valueText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(header)

        val seek = SeekBar(activity).apply {
            max = (((spec.maxEv - spec.minEv) / UI_STEP_EV).roundToInt()).coerceAtLeast(1)
            progress = (((saved - spec.minEv) / UI_STEP_EV).roundToInt()).coerceIn(0, max)
        }
        panel.addView(seek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 38)))

        fun evForProgress(progress: Int): Float =
            (spec.minEv + progress * UI_STEP_EV).coerceIn(spec.minEv, spec.maxEv)

        fun updateValue(ev: Float) {
            valueText.text = String.format(Locale.US, "%+.1f EV", ev)
        }
        updateValue(saved)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val ev = evForProgress(progress)
                updateValue(ev)
                storeEv(activity, ev)
                val old = pending.remove(activity)
                if (old != null) activity.window.decorView.removeCallbacks(old)
                val task = Runnable { apply(activity, ev) }
                pending[activity] = task
                activity.window.decorView.postDelayed(task, 45L)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                apply(activity, evForProgress(seek.progress))
            }
        })

        val diagnostic = activity.findViewById<View>(R.id.diagnosticPanel)
        val insertIndex = root.indexOfChild(diagnostic).let { if (it >= 0) it else root.childCount }
        root.addView(
            panel,
            insertIndex,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 72)).apply {
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
                val chars = manager.getCameraCharacteristics(logicalId)
                val idx = evToIndex(ev, chars)
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx)
            }
        }

        physicalIds.forEach { pid ->
            runCatching {
                val chars = manager.getCameraCharacteristics(pid)
                val idx = evToIndex(ev, chars)
                builder.setPhysicalCameraKey(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx, pid)
            }
        }
    }

    fun apply(activity: TriCamActivity, ev: Float) {
        storeEv(activity, ev)
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
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
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

    private data class Spec(val minEv: Float, val maxEv: Float)

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
                minEv = max(minEv, range.lower * step)
                maxEv = min(maxEv, range.upper * step)
            }
            if (minEv >= maxEv) Spec(-2f, 2f) else Spec(minEv, maxEv)
        } catch (_: Throwable) {
            Spec(-2f, 2f)
        }
    }

    private fun evToIndex(ev: Float, chars: CameraCharacteristics): Int {
        val range: Range<Int> = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val step = stepFloat(chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)).takeIf { it > 0f } ?: 1f
        return (ev / step).roundToInt().coerceIn(range.lower, range.upper)
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
