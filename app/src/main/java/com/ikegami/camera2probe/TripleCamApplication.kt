package com.ikegami.camera2probe

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

class TripleCamApplication : Application(), Application.ActivityLifecycleCallbacks {

    companion object {
        private const val TAG = "TripleCamBoot"
        private const val INITIAL_DELAY_MS = 500L
        private const val RETRY_DELAY_MS = 350L
        private const val MAX_RETRIES = 12
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var resumedActivity: Activity? = null
    private var bootGeneration = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        resumedActivity = activity
        val generation = ++bootGeneration
        mainHandler.postDelayed({ ensurePreview(activity, generation, 0) }, INITIAL_DELAY_MS)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity === activity) {
            resumedActivity = null
            bootGeneration++
        }
    }

    private fun ensurePreview(activity: MainActivity, generation: Int, attempt: Int) {
        if (generation != bootGeneration || resumedActivity !== activity || activity.isFinishing) return
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        try {
            val cls = MainActivity::class.java
            val previewRunning = cls.getDeclaredField("previewRunning").apply { isAccessible = true }
                .getBoolean(activity)
            val openingCamera = cls.getDeclaredField("openingCamera").apply { isAccessible = true }
                .getBoolean(activity)
            val capturing = cls.getDeclaredField("capturing").apply { isAccessible = true }
                .getBoolean(activity)

            if (previewRunning) {
                Log.d(TAG, "Preview already running")
                return
            }

            if (!openingCamera && !capturing) {
                Log.d(TAG, "Auto-starting triple preview, attempt=${attempt + 1}")
                cls.getDeclaredMethod("start1080Preview").apply { isAccessible = true }.invoke(activity)
            }

            if (attempt + 1 < MAX_RETRIES) {
                mainHandler.postDelayed(
                    { ensurePreview(activity, generation, attempt + 1) },
                    RETRY_DELAY_MS
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Auto preview guard failed", t)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
