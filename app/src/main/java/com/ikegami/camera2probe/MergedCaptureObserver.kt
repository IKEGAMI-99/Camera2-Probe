package com.ikegami.camera2probe

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Watches TRI // CAM's three JPEGs and optionally creates one additional contact sheet in the
 * requested order: ULTRA -> MAIN -> TELE. Output policy is user-selectable:
 *  - individual ON + merged ON  = keep all four files
 *  - individual ON + merged OFF = keep three originals
 *  - individual OFF + merged ON = make the merged file, then remove the three originals
 */
object MergedCaptureObserver {
    private const val TAG = "TriCamMerge"
    private const val MERGED_HEIGHT = 2048
    private const val JPEG_QUALITY = 95
    private const val PREFS = "tricam_capture_output"
    private const val PREF_INDIVIDUAL = "save_individual_3"
    private const val PREF_MERGED = "save_merged_strip"

    private val namePattern = Regex(
        "^TRICAM_(\\d{8}_\\d{6}_\\d{3})_(ULTRA|MAIN|TELE)_\\d+x\\d+\\.jpg$",
        RegexOption.IGNORE_CASE
    )

    @Volatile private var started = false
    private lateinit var appContext: Context
    private lateinit var worker: Handler
    private val batches = ConcurrentHashMap<String, MutableMap<String, Uri>>()
    private val merging = ConcurrentHashMap.newKeySet<String>()
    private val finished = ConcurrentHashMap.newKeySet<String>()

    fun saveIndividualEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_INDIVIDUAL, true)

    fun saveMergedEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_MERGED, true)

    fun setSaveIndividual(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_INDIVIDUAL, enabled).apply()
    }

    fun setSaveMerged(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_MERGED, enabled).apply()
    }

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            appContext = context.applicationContext
            val thread = HandlerThread("TriCamMergeThread").also { it.start() }
            worker = Handler(thread.looper)
            appContext.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                object : ContentObserver(worker) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        super.onChange(selfChange, uri)
                        if (uri != null) inspect(uri)
                    }
                }
            )
            started = true
            Log.d(TAG, "Merged capture observer started")
        }
    }

    private fun inspect(uri: Uri) {
        // If merged output is disabled the Camera2 engine's three originals are already the final
        // output, so there is intentionally nothing for this observer to do.
        if (!saveMergedEnabled(appContext)) return

        try {
            val columns = mutableListOf(MediaStore.Images.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                columns += MediaStore.Images.Media.RELATIVE_PATH
                columns += MediaStore.Images.Media.IS_PENDING
            }
            appContext.contentResolver.query(uri, columns.toTypedArray(), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return
                val name = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val pendingIndex = c.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                    if (pendingIndex >= 0 && c.getInt(pendingIndex) != 0) return
                    val pathIndex = c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                    if (pathIndex >= 0) {
                        val relative = c.getString(pathIndex).orEmpty()
                        if (!relative.contains("Camera2Probe")) return
                    }
                }

                val match = namePattern.matchEntire(name) ?: return
                val batch = match.groupValues[1]
                val lens = match.groupValues[2].uppercase()
                if (finished.contains(batch)) return

                val ready = synchronized(batches) {
                    val map = batches.getOrPut(batch) { mutableMapOf() }
                    map[lens] = uri
                    listOf("ULTRA", "MAIN", "TELE").all { map.containsKey(it) }
                }
                if (ready && merging.add(batch)) {
                    worker.post { merge(batch) }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaStore inspect failed: ${t.message}")
        }
    }

    private fun merge(batch: String) {
        val uris = synchronized(batches) { batches[batch]?.toMap() } ?: return
        val order = listOf("ULTRA", "MAIN", "TELE")
        val bitmaps = mutableListOf<Bitmap>()
        try {
            order.forEach { lens ->
                val uri = uris[lens] ?: error("Missing $lens")
                bitmaps += decodeForMerge(uri)
            }
            val height = bitmaps.minOf { it.height }.coerceAtMost(MERGED_HEIGHT)
            val normalized = bitmaps.map { bitmap ->
                if (bitmap.height == height) bitmap
                else Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * (height.toFloat() / bitmap.height)).roundToInt().coerceAtLeast(1),
                    height,
                    true
                ).also { if (it !== bitmap) bitmap.recycle() }
            }
            bitmaps.clear()
            bitmaps.addAll(normalized)

            val width = bitmaps.sumOf { it.width }
            val merged = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(merged)
            var x = 0f
            bitmaps.forEach { bitmap ->
                canvas.drawBitmap(bitmap, x, 0f, null)
                x += bitmap.width
            }

            val path = saveMerged(batch, merged)
            merged.recycle()

            val keepIndividuals = saveIndividualEnabled(appContext)
            if (!keepIndividuals) {
                var deleted = 0
                order.forEach { lens ->
                    val source = uris[lens] ?: return@forEach
                    try {
                        if (appContext.contentResolver.delete(source, null, null) > 0) deleted++
                    } catch (t: Throwable) {
                        Log.w(TAG, "Could not remove source $lens: ${t.message}")
                    }
                }
                Log.d(TAG, "Merged-only mode: removed $deleted/3 source JPEGs")
            }

            finished += batch
            batches.remove(batch)
            Log.d(TAG, "Merged ULTRA-MAIN-TELE saved: $path")
            Handler(Looper.getMainLooper()).post {
                val message = if (keepIndividuals) {
                    "個別3枚 + 横並びマージを保存しました"
                } else {
                    "横並びマージを保存しました"
                }
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            // Never delete the individual originals when merging fails. Losing all copies because a
            // derived image failed would be a rather impressive failure mode, even for camera code.
            Log.e(TAG, "Merge failed for $batch; keeping originals", t)
        } finally {
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            merging.remove(batch)
        }
    }

    private fun decodeForMerge(uri: Uri): Bitmap {
        val orientation = appContext.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val decoded = appContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: error("Could not decode $uri")

        val oriented = orientBitmap(decoded, orientation)
        if (oriented !== decoded) decoded.recycle()

        val targetHeight = oriented.height.coerceAtMost(MERGED_HEIGHT)
        if (oriented.height == targetHeight) return oriented
        val targetWidth = (oriented.width * (targetHeight.toFloat() / oriented.height))
            .roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(oriented, targetWidth, targetHeight, true)
        if (scaled !== oriented) oriented.recycle()
        return scaled
    }

    private fun orientBitmap(source: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
            return source
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun saveMerged(batch: String, bitmap: Bitmap): String {
        val name = "TRICAM_${batch}_MERGED_ULTRA_MAIN_TELE_${bitmap.width}x${bitmap.height}.jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camera2Probe")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = appContext.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Merged MediaStore insert failed")
            appContext.contentResolver.openOutputStream(uri)?.use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    error("Merged JPEG compression failed")
                }
            } ?: error("Merged output stream failed")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            appContext.contentResolver.update(uri, values, null, null)
            "Pictures/Camera2Probe/$name"
        } else {
            val base = appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: appContext.filesDir
            val dir = File(base, "Camera2Probe").apply { mkdirs() }
            val file = File(dir, name)
            FileOutputStream(file).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    error("Merged JPEG compression failed")
                }
            }
            file.absolutePath
        }
    }
}
