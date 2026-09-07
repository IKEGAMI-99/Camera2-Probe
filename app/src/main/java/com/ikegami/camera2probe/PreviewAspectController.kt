package com.ikegami.camera2probe

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Matrix
import android.view.TextureView
import android.view.View
import java.util.WeakHashMap

/** Preserves geometry and center-crops the proven 16:9 Camera2 stream into 4:3 preview cards. */
object PreviewAspectController {
    private const val LANDSCAPE_ASPECT = 1920f / 1080f
    private const val PORTRAIT_ASPECT = 1080f / 1920f
    private val installed = WeakHashMap<TextureView, View.OnLayoutChangeListener>()
    private val aspectByView = WeakHashMap<TextureView, Float>()
    private val lensAspects = floatArrayOf(PORTRAIT_ASPECT, PORTRAIT_ASPECT, PORTRAIT_ASPECT)

    fun install(activity: Activity) {
        val viewsByLens = listOf(
            activity.findViewById<TextureView>(R.id.previewUltra),
            activity.findViewById<TextureView>(R.id.previewMain),
            activity.findViewById<TextureView>(R.id.previewTele)
        )
        val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val effectiveAspect = if (landscape) LANDSCAPE_ASPECT else PORTRAIT_ASPECT
        for (i in 0..2) lensAspects[i] = effectiveAspect

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

    fun mapTap(width: Int, height: Int, x: Float, y: Float, lensIndex: Int): Pair<Float, Float> {
        if (width <= 0 || height <= 0) return 0.5f to 0.5f
        val vx = (x / width.toFloat()).coerceIn(0f, 1f)
        val vy = (y / height.toFloat()).coerceIn(0f, 1f)
        val viewAspect = width.toFloat() / height.toFloat()
        val sourceAspect = lensAspects.getOrElse(lensIndex) { PORTRAIT_ASPECT }
        return if (viewAspect < sourceAspect) {
            val visibleWidthFraction = (viewAspect / sourceAspect).coerceIn(0f, 1f)
            (0.5f + (vx - 0.5f) * visibleWidthFraction).coerceIn(0f, 1f) to vy
        } else {
            val visibleHeightFraction = (sourceAspect / viewAspect).coerceIn(0f, 1f)
            vx to (0.5f + (vy - 0.5f) * visibleHeightFraction).coerceIn(0f, 1f)
        }
    }

    fun mapTap(width: Int, height: Int, x: Float, y: Float): Pair<Float, Float> =
        mapTap(width, height, x, y, 1)
}
