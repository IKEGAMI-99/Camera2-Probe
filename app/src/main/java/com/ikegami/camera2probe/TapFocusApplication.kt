package com.ikegami.camera2probe

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Installs tap-to-focus / tap-to-meter on each physical-camera preview without changing the
 * proven triple-camera capture engine. MAIN and TELE use AF + AE metering; a fixed-focus lens
 * (ULTRA on POCO F7 Ultra) receives AE metering only.
 */
class TapFocusApplication : Application(), Application.ActivityLifecycleCallbacks {

    companion object {
        private const val TAG = "TriCamTapFocus"
        private const val METERING_BOX_FRACTION = 0.16f
    }

    private val installed = WeakHashMap<TriCamActivity, Boolean>()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is TriCamActivity) return
        if (installed[activity] == true) return
        activity.window.decorView.post {
            if (!activity.isFinishing) install(activity)
        }
    }

    private fun install(activity: TriCamActivity) {
        if (installed[activity] == true) return

        val mainFrame = activity.findViewById<FrameLayout>(R.id.mainFrame)
        val ultraFrame = activity.findViewById<FrameLayout>(R.id.ultraFrame)
        val teleFrame = activity.findViewById<FrameLayout>(R.id.teleFrame)

        // Internal physical order in TriCamActivity is ULTRA(0), MAIN(1), TELE(2).
        attach(activity, mainFrame, 1)
        attach(activity, ultraFrame, 0)
        attach(activity, teleFrame, 2)

        installed[activity] = true
        Log.d(TAG, "Tap focus installed")
    }

    private fun attach(activity: TriCamActivity, frame: FrameLayout, physicalIndex: Int) {
        frame.isClickable = true
        frame.setOnTouchListener { view, event ->
            if (event.action != MotionEvent.ACTION_UP) return@setOnTouchListener true
            if (view.width <= 0 || view.height <= 0) return@setOnTouchListener true

            val nx = (event.x / view.width.toFloat()).coerceIn(0f, 1f)
            val ny = (event.y / view.height.toFloat()).coerceIn(0f, 1f)
            showFocusRing(frame, event.x, event.y, physicalIndex)
            requestTapFocus(activity, physicalIndex, nx, ny)
            true
        }
    }

    private fun requestTapFocus(activity: TriCamActivity, index: Int, nx: Float, ny: Float) {
        try {
            val physicalIds = getField<List<String>>(activity, "physicalIds")
            val pid = physicalIds.getOrNull(index) ?: return
            val camera = getField<CameraDevice?>(activity, "cameraDevice") ?: return
            val session = getField<CameraCaptureSession?>(activity, "captureSession") ?: return
            val surfaces = getField<List<Surface>>(activity, "previewSurfaces")
            if (surfaces.size != 3) return
            val handler = getField<Handler?>(activity, "cameraHandler") ?: return
            val cameraManager = getField<CameraManager>(activity, "cameraManager")
            val afRequiredById = getField<MutableMap<String, Boolean>>(activity, "afRequiredById")
            val afModeById = getField<MutableMap<String, Int>>(activity, "afModeById")
            val captureInProgress = getField<Boolean>(activity, "captureInProgress")
            if (captureInProgress) return

            val chars = cameraManager.getCameraCharacteristics(pid)
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            val maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            val afRequired = afRequiredById[pid] == true && maxAfRegions > 0
            val region = makeRegion(active, nx, ny)

            handler.post {
                try {
                    // Cancel a previous single-shot AF lock so repeated taps always start a fresh scan.
                    if (afRequired) {
                        val cancel = buildPreviewRequest(
                            activity, camera, surfaces, physicalIds, afModeById,
                            tappedPid = pid, region = region, afRequired = true,
                            trigger = CaptureRequest.CONTROL_AF_TRIGGER_CANCEL,
                            useMetering = true, cameraManager = cameraManager
                        )
                        session.capture(cancel, null, handler)
                    }

                    // One-shot trigger. For fixed focus this is AE metering only.
                    val trigger = buildPreviewRequest(
                        activity, camera, surfaces, physicalIds, afModeById,
                        tappedPid = pid, region = region, afRequired = afRequired,
                        trigger = if (afRequired) CaptureRequest.CONTROL_AF_TRIGGER_START else CaptureRequest.CONTROL_AF_TRIGGER_IDLE,
                        useMetering = true, cameraManager = cameraManager
                    )
                    session.capture(trigger, null, handler)

                    // Keep the tapped region active. AUTO keeps MAIN/TELE locked to the selected
                    // point; other AF lenses continue normal continuous-picture focusing.
                    val repeating = buildPreviewRequest(
                        activity, camera, surfaces, physicalIds, afModeById,
                        tappedPid = pid, region = region, afRequired = afRequired,
                        trigger = CaptureRequest.CONTROL_AF_TRIGGER_IDLE,
                        useMetering = true, cameraManager = cameraManager
                    )
                    session.setRepeatingRequest(
                        repeating,
                        object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                // Preserve the app's existing physical AF/AE/AWB diagnostics.
                                invokeUpdate3A(activity, result)
                            }
                        },
                        handler
                    )

                    val lens = when (index) { 0 -> "ULTRA"; 1 -> "MAIN"; else -> "TELE" }
                    activity.runOnUiThread {
                        activity.findViewById<TextView>(R.id.statusText).text =
                            if (afRequired) "$lens · TAP AF" else "$lens · AE METERING · FIXED FOCUS"
                    }
                    Log.d(TAG, "$lens tap region=$region physical=$pid AF=$afRequired AE=${maxAeRegions > 0}")
                } catch (t: Throwable) {
                    Log.e(TAG, "Tap focus request failed", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Tap focus setup failed", t)
        }
    }

    private fun buildPreviewRequest(
        activity: TriCamActivity,
        camera: CameraDevice,
        surfaces: List<Surface>,
        physicalIds: List<String>,
        afModeById: MutableMap<String, Int>,
        tappedPid: String,
        region: MeteringRectangle,
        afRequired: Boolean,
        trigger: Int,
        useMetering: Boolean,
        cameraManager: CameraManager
    ): CaptureRequest {
        val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        surfaces.forEach { b.addTarget(it) }
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)

        var physicalAfRegionApplied = false
        var physicalAeRegionApplied = false
        var physicalAfTriggerApplied = false

        physicalIds.forEach { id ->
            val defaultAf = afModeById[id] ?: CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            val mode = if (id == tappedPid && afRequired) CaptureRequest.CONTROL_AF_MODE_AUTO else defaultAf
            try { b.setPhysicalCameraKey(CaptureRequest.CONTROL_AF_MODE, mode, id) } catch (_: Throwable) {}
            try { b.setPhysicalCameraKey(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON, id) } catch (_: Throwable) {}
            try { b.setPhysicalCameraKey(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO, id) } catch (_: Throwable) {}

            if (id == tappedPid && useMetering) {
                val chars = cameraManager.getCameraCharacteristics(id)
                if ((chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                    try {
                        b.setPhysicalCameraKey(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region), id)
                        physicalAeRegionApplied = true
                    } catch (_: Throwable) {}
                }
                if (afRequired && (chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                    try {
                        b.setPhysicalCameraKey(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region), id)
                        physicalAfRegionApplied = true
                    } catch (_: Throwable) {}
                }
                if (afRequired) {
                    try {
                        b.setPhysicalCameraKey(CaptureRequest.CONTROL_AF_TRIGGER, trigger, id)
                        physicalAfTriggerApplied = true
                    } catch (_: Throwable) {}
                }
            }
        }

        // Some vendor HALs do not expose AF/AE regions as physical request keys. Fall back to
        // logical-camera regions so the tap still has useful behavior instead of silently doing nothing.
        if (useMetering && (!physicalAeRegionApplied || (afRequired && !physicalAfRegionApplied))) {
            val logicalId = getField<String?>(activity, "logicalRearId")
            if (logicalId != null) {
                val logicalChars = cameraManager.getCameraCharacteristics(logicalId)
                val logicalActive = logicalChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (logicalActive != null) {
                    val cx = region.rect.centerX().toFloat()
                    val cy = region.rect.centerY().toFloat()
                    val tappedActive = cameraManager.getCameraCharacteristics(tappedPid)
                        .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    val nx = if (tappedActive != null) (cx - tappedActive.left) / tappedActive.width().toFloat() else 0.5f
                    val ny = if (tappedActive != null) (cy - tappedActive.top) / tappedActive.height().toFloat() else 0.5f
                    val logicalRegion = makeRegion(logicalActive, nx, ny)
                    if (!physicalAeRegionApplied && (logicalChars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                        try { b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(logicalRegion)) } catch (_: Throwable) {}
                    }
                    if (afRequired && !physicalAfRegionApplied && (logicalChars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                        try { b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(logicalRegion)) } catch (_: Throwable) {}
                    }
                }
            }
        }

        if (afRequired && !physicalAfTriggerApplied) {
            try { b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO) } catch (_: Throwable) {}
            try { b.set(CaptureRequest.CONTROL_AF_TRIGGER, trigger) } catch (_: Throwable) {}
        }
        return b.build()
    }

    private fun makeRegion(active: Rect, nx: Float, ny: Float): MeteringRectangle {
        val cx = active.left + (active.width() * nx).roundToInt()
        val cy = active.top + (active.height() * ny).roundToInt()
        val half = ((minOf(active.width(), active.height()) * METERING_BOX_FRACTION) / 2f).roundToInt().coerceAtLeast(16)
        val left = (cx - half).coerceIn(active.left, active.right - 2)
        val top = (cy - half).coerceIn(active.top, active.bottom - 2)
        val right = (cx + half).coerceIn(left + 1, active.right)
        val bottom = (cy + half).coerceIn(top + 1, active.bottom)
        return MeteringRectangle(Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX)
    }

    private fun showFocusRing(frame: FrameLayout, x: Float, y: Float, index: Int) {
        val density = resources.displayMetrics.density
        val size = (62f * density).roundToInt()
        val stroke = (2.2f * density).roundToInt().coerceAtLeast(2)
        val ring = View(frame.context)
        val color = when (index) {
            0 -> Color.rgb(55, 232, 255)  // ULTRA
            1 -> Color.rgb(143, 105, 255) // MAIN
            else -> Color.rgb(76, 255, 177) // TELE
        }
        ring.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(Color.TRANSPARENT)
            setStroke(stroke, color)
        }
        ring.alpha = 0.95f
        ring.scaleX = 1.35f
        ring.scaleY = 1.35f
        val lp = FrameLayout.LayoutParams(size, size)
        lp.leftMargin = (x - size / 2f).roundToInt().coerceIn(0, (frame.width - size).coerceAtLeast(0))
        lp.topMargin = (y - size / 2f).roundToInt().coerceIn(0, (frame.height - size).coerceAtLeast(0))
        frame.addView(ring, lp)
        ring.animate().scaleX(1f).scaleY(1f).setDuration(140L).withEndAction {
            ring.animate().alpha(0f).setStartDelay(520L).setDuration(320L).withEndAction {
                frame.removeView(ring)
            }.start()
        }.start()
    }

    private fun invokeUpdate3A(activity: TriCamActivity, result: TotalCaptureResult) {
        try {
            val m = TriCamActivity::class.java.getDeclaredMethod("update3AState", TotalCaptureResult::class.java)
            m.isAccessible = true
            m.invoke(activity, result)
        } catch (_: Throwable) {
            // Diagnostics are optional; focus operation itself does not depend on this callback.
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(activity: TriCamActivity, name: String): T {
        val f = TriCamActivity::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(activity) as T
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (activity is TriCamActivity) installed.remove(activity)
    }
}
