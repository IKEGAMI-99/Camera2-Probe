package com.ikegami.camera2probe

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * Locks the four matrix cards to portrait 3:4. The camera stream itself remains the proven
 * 1080p triple stream; PreviewAspectController center-crops it without geometric stretching.
 */
object PreviewLayoutController {
    fun install(activity: Activity) {
        val main = activity.findViewById<FrameLayout>(R.id.mainFrame) ?: return
        val tele = activity.findViewById<FrameLayout>(R.id.teleFrame) ?: return
        val topRow = main.parent as? LinearLayout ?: return
        val bottomRow = tele.parent as? LinearLayout ?: return
        val matrix = topRow.parent as? LinearLayout ?: return

        matrix.post {
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

            listOf<View>(main, activity.findViewById(R.id.ultraFrame), tele, activity.findViewById(R.id.controlPanel))
                .forEach { card ->
                    val lp = card.layoutParams
                    lp.height = cardHeight
                    card.layoutParams = lp
                }
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()
}
