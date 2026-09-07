package com.ikegami.camera2probe

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast

/** Opens the user's real gallery/photos application, preferring TRI // CAM's latest image. */
object GalleryLauncher {
    private const val APP_GALLERY_CATEGORY = "android.intent.category.APP_GALLERY"

    fun open(activity: Activity) {
        // First preference: launch the device's actual gallery/photos app as an app, not a picker.
        try {
            val galleryIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, APP_GALLERY_CATEGORY)
            if (galleryIntent.resolveActivity(activity.packageManager) != null) {
                activity.startActivity(galleryIntent)
                return
            }
        } catch (_: Throwable) {}

        // Some OEM galleries do not advertise APP_GALLERY. Open our newest image instead; this
        // normally lands inside Xiaomi Gallery / Google Photos and lets the user swipe the folder.
        val latest = findLatestTriCamImage(activity)
        if (latest != null) {
            try {
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(latest, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(view)
                return
            } catch (_: Throwable) {}
        }

        // Last gallery-like fallback. This intentionally avoids ACTION_PICK so the button does not
        // suddenly turn into a file-selection workflow.
        try {
            val viewAll = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            }
            activity.startActivity(viewAll)
        } catch (_: Throwable) {
            Toast.makeText(activity, "ギャラリーアプリを開けませんでした", Toast.LENGTH_SHORT).show()
        }
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
