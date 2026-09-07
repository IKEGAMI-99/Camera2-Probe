package com.ikegami.camera2probe

import android.app.Activity
import android.graphics.Matrix
import android.view.TextureView
import android.view.View
import java.util.WeakHashMap

/**
 * Keeps Camera2's 1920x1080 preview at its native 16:9 geometry while filling the portrait cards.
 * The previous layout let TextureView stretch independently on X/Y, which made people and objects
 * look unnaturally tall/narrow. We correct only the display transform; the camera stream stays 1080p.
 */
object PreviewAspectController {
    private const val SOURCE_ASPECT = 1920f / 1080f
    private val installed = WeakHashMap<TextureView, View.OnLayoutChangeListener>()

    fun install(activity: Activity) {
        listOf(
            activity.findViewById<TextureView>(R.id.previewMain),
            activity.findViewById<TextureView>(R.id.previewUltra),
            activity.findViewById<TextureView>(R.id.previewTele)
        ).forEach { view ->
            if (installed.containsKey(view)) {
                applyCenterCrop(view)
                return@forEach
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

        val viewAspect = w / h
        val matrix = Matrix()
        val cx = w / 2f
        val cy = h / 2f

        // TextureView's default behavior stretches the buffer to the view. Compensate for that
        // non-uniform scaling, then crop symmetrically around the center.
        if (viewAspect < SOURCE_ASPECT) {
            matrix.setScale(SOURCE_ASPECT / viewAspect, 1f, cx, cy)
        } else {
            matrix.setScale(1f, viewAspect / SOURCE_ASPECT, cx, cy)
        }
        view.setTransform(matrix)
    }

    /**
     * Convert a tap on a center-cropped preview back into normalized sensor coordinates.
     * This keeps tap-AF aligned with what the user can actually see after aspect correction.
     */
    fun mapTap(width: Int, height: Int, x: Float, y: Float): Pair<Float, Float> {
        if (width <= 0 || height <= 0) return 0.5f to 0.5f
        val vx = (x / width.toFloat()).coerceIn(0f, 1f)
        val vy = (y / height.toFloat()).coerceIn(0f, 1f)
        val viewAspect = width.toFloat() / height.toFloat()

        return if (viewAspect < SOURCE_ASPECT) {
            val visibleWidthFraction = (viewAspect / SOURCE_ASPECT).coerceIn(0f, 1f)
            val nx = (0.5f + (vx - 0.5f) * visibleWidthFraction).coerceIn(0f, 1f)
            nx to vy
        } else {
            val visibleHeightFraction = (SOURCE_ASPECT / viewAspect).coerceIn(0f, 1f)
            val ny = (0.5f + (vy - 0.5f) * visibleHeightFraction).coerceIn(0f, 1f)
            vx to ny
        }
    }
}
