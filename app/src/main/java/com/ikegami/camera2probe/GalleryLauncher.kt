package com.ikegami.camera2probe

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast

/** Opens the device-native gallery, deliberately avoiding Google Photos. */
object GalleryLauncher {
    private val nativeGalleryPackages = listOf(
        "com.miui.gallery",          // Xiaomi / POCO / HyperOS
        "com.android.gallery3d",     // AOSP / several OEMs
        "com.sec.android.gallery3d", // Samsung
        "com.coloros.gallery3d"      // OPPO / ColorOS variants
    )

    fun open(activity: Activity) {
        val latest = findLatestTriCamImage(activity)

        for (pkg in nativeGalleryPackages) {
            if (!isInstalled(activity, pkg)) continue

            // Prefer opening the latest TRI // CAM image inside the native gallery so the user lands
            // directly in the relevant media collection and can swipe through nearby captures.
            if (latest != null) {
                try {
                    val view = Intent(Intent.ACTION_VIEW).apply {
                        setPackage(pkg)
                        setDataAndType(latest, "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(view)
                    return
                } catch (_: Throwable) {}
            }

            try {
                val launch = activity.packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    activity.startActivity(launch)
                    return
                }
            } catch (_: Throwable) {}
        }

        Toast.makeText(activity, "端末のネイティブギャラリーを開けませんでした", Toast.LENGTH_SHORT).show()
    }

    private fun isInstalled(activity: Activity, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        activity.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }

    private fun findLatestTriCamImage(activity: Activity): Uri? {
        return try {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection: String?
            val args: Array<String>?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
                args = arrayOf("%Pictures/Camera2Probe%")
            } else {
                selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
                args = arrayOf("TRICAM_%")
            }
            activity.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
            }
        } catch (_: Throwable) {
            null
        }
    }
}
