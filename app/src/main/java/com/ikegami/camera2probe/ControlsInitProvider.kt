package com.ikegami.camera2probe

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Small startup provider that layers UI controls onto TriCamActivity without disturbing the
 * camera engine or the existing TapFocusApplication lifecycle code.
 */
class ControlsInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(Callbacks())
        return true
    }

    private class Callbacks : Application.ActivityLifecycleCallbacks {
        private val installed = WeakHashMap<TriCamActivity, Boolean>()

        override fun onActivityResumed(activity: Activity) {
            if (activity !is TriCamActivity || installed[activity] == true) return
            activity.window.decorView.post {
                if (activity.isFinishing || installed[activity] == true) return@post
                activity.findViewById<View>(R.id.allAfButton)?.setOnClickListener {
                    it.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70L).withEndAction {
                        it.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                    }.start()
                    AllFocusController.trigger(activity)
                }
                activity.findViewById<View>(R.id.settingsButton)?.setOnClickListener {
                    showEnhancedSettings(activity)
                }
                installed[activity] = true
            }
        }

        private fun showEnhancedSettings(activity: TriCamActivity) {
            val fps = getField<Boolean>(activity, "fpsOverlayEnabled")
            val diagnostics = getField<Boolean>(activity, "diagnosticsVisible")
            val items = arrayOf("プレビューにFPSを表示", "診断ログを表示")
            val checked = booleanArrayOf(fps, diagnostics)

            AlertDialog.Builder(activity)
                .setTitle("TRI // CAM SETTINGS")
                .setMultiChoiceItems(items, checked) { _, which, enabled ->
                    when (which) {
                        0 -> {
                            setField(activity, "fpsOverlayEnabled", enabled)
                            activity.getSharedPreferences("triple_cam_ui", Activity.MODE_PRIVATE)
                                .edit().putBoolean("show_fps", enabled).apply()
                            invokeUpdateLensLabels(activity)
                        }
                        1 -> {
                            setField(activity, "diagnosticsVisible", enabled)
                            activity.getSharedPreferences("triple_cam_ui", Activity.MODE_PRIVATE)
                                .edit().putBoolean("show_diagnostics", enabled).apply()
                            activity.findViewById<View>(R.id.diagnosticPanel).visibility =
                                if (enabled) View.VISIBLE else View.GONE
                        }
                    }
                }
                .setNegativeButton("アップデート確認") { _, _ -> AppUpdater.checkForUpdate(activity) }
                .setNeutralButton("カメラ再起動") { _, _ -> invokeNoArg(activity, "restartCamera") }
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
