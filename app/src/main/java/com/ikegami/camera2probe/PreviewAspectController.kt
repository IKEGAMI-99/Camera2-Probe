package com.ikegami.camera2probe

import android.app.Activity
import android.content.Context
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.TextureView
import android.view.View
import java.util.WeakHashMap

/**
 * Preserves the camera image geometry while filling the portrait preview cards. Physical camera
 * sensor orientation is inspected so devices that expose the stream effectively as 9:16 are not
 * accidentally treated as 16:9 (or vice versa).
 */
object PreviewAspectController {
    private const val LANDSCAPE_ASPECT = 1920f / 1080f
    private const val PORTRAIT_ASPECT = 1080f / 1920f
    private val installed = WeakHashMap<TextureView, View.OnLayoutChangeListener>()
    private val aspectByView = WeakHashMap<TextureView, Float>()
    private val lensAspects = floatArrayOf(PORTRAIT_ASPECT, PORTRAIT_ASPECT, PORTRAIT_ASPECT)

    fun install(activity: Activity) {
        val viewsByLens = listOf(
            activity.findViewById<TextureView>(R.id.previewUltra), // lens index 0
            activity.findViewById<TextureView>(R.id.previewMain),  // lens index 1
            activity.findViewById<TextureView>(R.id.previewTele)   // lens index 2
        )
        val detected = detectLensAspects(activity)
        for (i in 0..2) lensAspects[i] = detected.getOrElse(i) { PORTRAIT_ASPECT }

        viewsByLens.forEachIndexed { index, view ->
            aspectByView[view] = lensAspects[index]
            if (installed.containsKey(view)) {
                applyCenterCrop(view)
                return@forEachIndexed
            }
            val listener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                applyCenterCrop(v as TextureView)
            }
            installed[view] = listener
            view.addOnLayoutChangeListener(listener)
            view.post { applyCenterCrop(view) }
        }
    }

    fun applyCenterCrop(view: TextureView) {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0f || h <= 0f) return

        val sourceAspect = aspectByView[view] ?: PORTRAIT_ASPECT
        val viewAspect = w / h
        val matrix = Matrix()
        val cx = w / 2f
        val cy = h / 2f

        if (viewAspect < sourceAspect) {
            matrix.setScale(sourceAspect / viewAspect, 1f, cx, cy)
        } else {
            matrix.setScale(1f, viewAspect / sourceAspect, cx, cy)
        }
        view.setTransform(matrix)
    }

    /** Map a visible tap back through the center crop to normalized sensor coordinates. */
    fun mapTap(width: Int, height: Int, x: Float, y: Float, lensIndex: Int): Pair<Float, Float> {
        if (width <= 0 || height <= 0) return 0.5f to 0.5f
        val vx = (x / width.toFloat()).coerceIn(0f, 1f)
        val vy = (y / height.toFloat()).coerceIn(0f, 1f)
        val viewAspect = width.toFloat() / height.toFloat()
        val sourceAspect = lensAspects.getOrElse(lensIndex) { PORTRAIT_ASPECT }

        return if (viewAspect < sourceAspect) {
            val visibleWidthFraction = (viewAspect / sourceAspect).coerceIn(0f, 1f)
            val nx = (0.5f + (vx - 0.5f) * visibleWidthFraction).coerceIn(0f, 1f)
            nx to vy
        } else {
            val visibleHeightFraction = (sourceAspect / viewAspect).coerceIn(0f, 1f)
            val ny = (0.5f + (vy - 0.5f) * visibleHeightFraction).coerceIn(0f, 1f)
            vx to ny
        }
    }

    // Compatibility overload for existing tap-focus call sites. All three POCO rear sensors expose
    // the same mounting orientation in practice; MAIN is used as the safe shared reference.
    fun mapTap(width: Int, height: Int, x: Float, y: Float): Pair<Float, Float> =
        mapTap(width, height, x, y, 1)

    private fun detectLensAspects(activity: Activity): List<Float> {
        return try {
            val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val logicalId = manager.cameraIdList.firstOrNull { id ->
                val c = manager.getCameraCharacteristics(id)
                val back = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                back && caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) &&
                    c.physicalCameraIds.size >= 3
            } ?: return List(3) { PORTRAIT_ASPECT }

            val physical = manager.getCameraCharacteristics(logicalId).physicalCameraIds
                .sortedBy { pid ->
                    manager.getCameraCharacteristics(pid)
                        .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?.minOrNull() ?: Float.MAX_VALUE
                }
                .take(3)

            physical.map { pid ->
                val orientation = manager.getCameraCharacteristics(pid)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                if (orientation % 180 == 90) PORTRAIT_ASPECT else LANDSCAPE_ASPECT
            }
        } catch (_: Throwable) {
            List(3) { PORTRAIT_ASPECT }
        }
    }
}
