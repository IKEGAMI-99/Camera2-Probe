package com.ikegami.camera2probe

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.roundToInt

/** Keeps preview cards at the camera-native 4:3 geometry in either device orientation. */
object PreviewLayoutController {
    fun install(activity: Activity) {
        val main = activity.findViewById<FrameLayout>(R.id.mainFrame) ?: return
        val ultra = activity.findViewById<FrameLayout>(R.id.ultraFrame) ?: return
        val tele = activity.findViewById<FrameLayout>(R.id.teleFrame) ?: return
        val control = activity.findViewById<FrameLayout>(R.id.controlPanel) ?: return
        val matrix = activity.findViewById<LinearLayout>(R.id.cameraMatrix) ?: return
        val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        matrix.post {
            if (landscape) {
                val usableWidth = matrix.width.coerceAtLeast(1)
                val gap = dp(activity, 24)
                val cardWidth = ((usableWidth - gap) / 4f).roundToInt().coerceAtLeast(1)
                val cardHeight = (cardWidth * 3f / 4f).roundToInt()
                listOf<View>(main, ultra, tele, control).forEach { card ->
                    val lp = card.layoutParams
                    lp.height = cardHeight
                    card.layoutParams = lp
                }
                val lp = matrix.layoutParams
                lp.height = cardHeight
                if (lp is LinearLayout.LayoutParams) lp.weight = 0f
                matrix.layoutParams = lp
            } else {
                val topRow = activity.findViewById<LinearLayout>(R.id.topRow) ?: return@post
                val bottomRow = activity.findViewById<LinearLayout>(R.id.bottomRow) ?: return@post
                val width = main.width.takeIf { it > 0 }
                    ?: ((matrix.width - dp(activity, 10)) / 2).coerceAtLeast(1)
                val cardHeight = (width * 4f / 3f).roundToInt()

                fun lockRow(row: LinearLayout) {
                    val lp = row.layoutParams
                    lp.height = cardHeight
                    if (lp is LinearLayout.LayoutParams) lp.weight = 0f
                    row.layoutParams = lp
                }
                lockRow(topRow)
                lockRow(bottomRow)

                val matrixLp = matrix.layoutParams
                matrixLp.height = cardHeight * 2 + dp(activity, 10)
                if (matrixLp is LinearLayout.LayoutParams) matrixLp.weight = 0f
                matrix.layoutParams = matrixLp

                listOf<View>(main, ultra, tele, control).forEach { card ->
                    val lp = card.layoutParams
                    lp.height = cardHeight
                    card.layoutParams = lp
                }
            }
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()
}
