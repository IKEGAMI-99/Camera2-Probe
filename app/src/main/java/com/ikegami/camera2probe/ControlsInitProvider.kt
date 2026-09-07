package com.ikegami.camera2probe

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.WeakHashMap

/** Startup provider for UI controls and non-camera helpers. */
class ControlsInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        MergedCaptureObserver.start(app)
        app.registerActivityLifecycleCallbacks(Callbacks())
        return true
    }

    private class Callbacks : Application.ActivityLifecycleCallbacks {
        private val installed = WeakHashMap<TriCamActivity, Boolean>()

        override fun onActivityResumed(activity: Activity) {
            if (activity !is TriCamActivity) return
            activity.window.decorView.post {
                if (activity.isFinishing) return@post

                PreviewLayoutController.install(activity)
                PreviewAspectController.install(activity)
                ExposureController.install(activity)
                AppUpdater.resumePendingInstall(activity)

                if (installed[activity] == true) return@post

                activity.findViewById<View>(R.id.allAfButton)?.setOnClickListener {
                    it.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70L).withEndAction {
                        it.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                    }.start()
                    AllFocusController.trigger(activity)
                }

                activity.findViewById<View>(R.id.settingsButton)?.setOnClickListener {
                    showEnhancedSettings(activity)
                }

                activity.findViewById<View>(R.id.galleryButton)?.setOnClickListener {
                    GalleryLauncher.open(activity)
                }

                ShutterEffects.install(activity)
                installed[activity] = true
            }
        }

        @Suppress("DEPRECATION")
        private fun showEnhancedSettings(activity: TriCamActivity) {
            val density = activity.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()

            val fps = getField<Boolean>(activity, "fpsOverlayEnabled")
            val diagnostics = getField<Boolean>(activity, "diagnosticsVisible")
            val saveIndividual = MergedCaptureObserver.saveIndividualEnabled(activity)
            val saveMerged = MergedCaptureObserver.saveMergedEnabled(activity)

            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(6), dp(22), dp(4))
            }

            fun section(title: String, subtitle: String) {
                panel.addView(TextView(activity).apply {
                    text = title
                    textSize = 12f
                    setTextColor(Color.rgb(190, 211, 228))
                    setPadding(0, dp(12), 0, dp(2))
                })
                panel.addView(TextView(activity).apply {
                    text = subtitle
                    textSize = 9f
                    setTextColor(Color.rgb(100, 123, 143))
                    setPadding(0, 0, 0, dp(5))
                })
            }

            section("CAPTURE OUTPUT", "撮影後に残すファイルを選択")

            val individualSwitch = Switch(activity).apply {
                text = "個別3枚を保存"
                textSize = 14f
                isChecked = saveIndividual
                setPadding(0, dp(4), 0, dp(4))
            }
            val mergedSwitch = Switch(activity).apply {
                text = "横並びマージを保存"
                textSize = 14f
                isChecked = saveMerged
                setPadding(0, dp(4), 0, dp(4))
            }
            panel.addView(individualSwitch)
            panel.addView(mergedSwitch)

            individualSwitch.setOnCheckedChangeListener { button, enabled ->
                if (!enabled && !mergedSwitch.isChecked) {
                    Toast.makeText(activity, "保存方式を少なくとも1つ有効にしてください", Toast.LENGTH_SHORT).show()
                    button.isChecked = true
                    return@setOnCheckedChangeListener
                }
                MergedCaptureObserver.setSaveIndividual(activity, enabled)
            }
            mergedSwitch.setOnCheckedChangeListener { button, enabled ->
                if (!enabled && !individualSwitch.isChecked) {
                    Toast.makeText(activity, "保存方式を少なくとも1つ有効にしてください", Toast.LENGTH_SHORT).show()
                    button.isChecked = true
                    return@setOnCheckedChangeListener
                }
                MergedCaptureObserver.setSaveMerged(activity, enabled)
            }

            section("VIEW", "プレビューと診断表示")

            val fpsSwitch = Switch(activity).apply {
                text = "プレビューにFPSを表示"
                textSize = 14f
                isChecked = fps
                setPadding(0, dp(4), 0, dp(4))
            }
            val diagnosticsSwitch = Switch(activity).apply {
                text = "診断ログを表示"
                textSize = 14f
                isChecked = diagnostics
                setPadding(0, dp(4), 0, dp(4))
            }
            panel.addView(fpsSwitch)
            panel.addView(diagnosticsSwitch)

            fpsSwitch.setOnCheckedChangeListener { _, enabled ->
                setField(activity, "fpsOverlayEnabled", enabled)
                activity.getSharedPreferences("triple_cam_ui", Activity.MODE_PRIVATE)
                    .edit().putBoolean("show_fps", enabled).apply()
                invokeUpdateLensLabels(activity)
            }
            diagnosticsSwitch.setOnCheckedChangeListener { _, enabled ->
                setField(activity, "diagnosticsVisible", enabled)
                activity.getSharedPreferences("triple_cam_ui", Activity.MODE_PRIVATE)
                    .edit().putBoolean("show_diagnostics", enabled).apply()
                activity.findViewById<View>(R.id.diagnosticPanel).visibility =
                    if (enabled) View.VISIBLE else View.GONE
            }

            AlertDialog.Builder(activity)
                .setTitle("TRI // CAM SETTINGS")
                .setView(panel)
                .setNegativeButton("アップデート確認") { _, _ -> AppUpdater.checkForUpdate(activity) }
                .setNeutralButton("カメラ再起動") { _, _ ->
                    invokeNoArg(activity, "restartCamera")
                    ExposureController.reapplySoon(activity)
                }
                .setPositiveButton("閉じる", null)
                .show()
        }

        private fun invokeUpdateLensLabels(activity: TriCamActivity) {
            try {
                val method = TriCamActivity::class.java.getDeclaredMethod("updateLensLabels", DoubleArray::class.java)
                method.isAccessible = true
                method.invoke(activity, null)
            } catch (_: Throwable) {}
        }

        private fun invokeNoArg(activity: TriCamActivity, name: String) {
            try {
                val method = TriCamActivity::class.java.getDeclaredMethod(name)
                method.isAccessible = true
                method.invoke(activity)
            } catch (_: Throwable) {}
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> getField(activity: TriCamActivity, name: String): T {
            val field = TriCamActivity::class.java.getDeclaredField(name)
            field.isAccessible = true
            return field.get(activity) as T
        }

        private fun setField(activity: TriCamActivity, name: String, value: Any) {
            val field = TriCamActivity::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(activity, value)
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (activity is TriCamActivity) installed.remove(activity)
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
