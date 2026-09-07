package com.ikegami.camera2probe

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.util.Log
import android.view.Surface
import android.widget.TextView

object AllFocusController {
    private const val TAG = "TriCamAllAF"

    fun trigger(activity: TriCamActivity) {
        try {
            val physicalIds = getField<List<String>>(activity, "physicalIds")
            val camera = getField<CameraDevice?>(activity, "cameraDevice") ?: return
            val session = getField<CameraCaptureSession?>(activity, "captureSession") ?: return
            val surfaces = getField<List<Surface>>(activity, "previewSurfaces")
            val handler = getField<Handler?>(activity, "cameraHandler") ?: return
            val afRequiredById = getField<MutableMap<String, Boolean>>(activity, "afRequiredById")
            val captureInProgress = getField<Boolean>(activity, "captureInProgress")
            if (physicalIds.size != 3 || surfaces.size != 3 || captureInProgress) return

            handler.post {
                try {
                    val cancel = buildRequest(
                        camera, surfaces, physicalIds, afRequiredById,
                        CaptureRequest.CONTROL_AF_TRIGGER_CANCEL
                    )
                    session.capture(cancel, null, handler)

                    val start = buildRequest(
                        camera, surfaces, physicalIds, afRequiredById,
                        CaptureRequest.CONTROL_AF_TRIGGER_START
                    )
                    session.capture(start, null, handler)

                    val hold = buildRequest(
                        camera, surfaces, physicalIds, afRequiredById,
                        CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                    )
                    session.setRepeatingRequest(
                        hold,
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

                    activity.runOnUiThread {
                        activity.findViewById<TextView>(R.id.statusText).text = "ALL AF · MAIN + TELE SCANNING"
                        activity.findViewById<TextView>(R.id.linkBadge).text = "ALL AF"
                    }
                    Log.d(TAG, "Triggered AF on every focus-capable physical camera; fixed-focus lenses remain AF OFF")
                } catch (t: Throwable) {
                    Log.e(TAG, "ALL AF trigger failed", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ALL AF setup failed", t)
        }
    }

    private fun buildRequest(
        camera: CameraDevice,
        surfaces: List<Surface>,
        physicalIds: List<String>,
        afRequiredById: MutableMap<String, Boolean>,
        trigger: Int
    ): CaptureRequest {
        val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        surfaces.forEach { b.addTarget(it) }
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

        val anyAf = physicalIds.any { afRequiredById[it] == true }
        b.set(
            CaptureRequest.CONTROL_AF_MODE,
            if (anyAf) CaptureRequest.CONTROL_AF_MODE_AUTO else CaptureRequest.CONTROL_AF_MODE_OFF
        )
        b.set(
            CaptureRequest.CONTROL_AF_TRIGGER,
            if (anyAf) trigger else CaptureRequest.CONTROL_AF_TRIGGER_IDLE
        )

        physicalIds.forEach { pid ->
            val hasAf = afRequiredById[pid] == true
            try {
                b.setPhysicalCameraKey(
                    CaptureRequest.CONTROL_AF_MODE,
                    if (hasAf) CaptureRequest.CONTROL_AF_MODE_AUTO else CaptureRequest.CONTROL_AF_MODE_OFF,
                    pid
                )
            } catch (_: Throwable) {}
            try { b.setPhysicalCameraKey(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON, pid) } catch (_: Throwable) {}
            try { b.setPhysicalCameraKey(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO, pid) } catch (_: Throwable) {}
            if (hasAf) {
                try { b.setPhysicalCameraKey(CaptureRequest.CONTROL_AF_TRIGGER, trigger, pid) } catch (_: Throwable) {}
            }
        }
        return b.build()
    }

    private fun invokeUpdate3A(activity: TriCamActivity, result: TotalCaptureResult) {
        try {
            val m = TriCamActivity::class.java.getDeclaredMethod("update3AState", TotalCaptureResult::class.java)
            m.isAccessible = true
            m.invoke(activity, result)
        } catch (_: Throwable) {}
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(activity: TriCamActivity, name: String): T {
        val f = TriCamActivity::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(activity) as T
    }
}
