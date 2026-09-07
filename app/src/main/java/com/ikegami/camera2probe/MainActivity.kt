package com.ikegami.camera2probe

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

class MainActivity : Activity() {

    companion object {
        private const val TAG = "Camera2Probe"
        private const val CAMERA_PERMISSION_REQUEST = 10
        private const val PREVIEW_WIDTH = 1920
        private const val PREVIEW_HEIGHT = 1080
        private const val FALLBACK_MAX_PIXELS = 12_500_000L
        private const val CAPTURE_TIMEOUT_MS = 15_000L
    }

    private lateinit var cameraManager: CameraManager
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var captureInfoText: TextView
    private lateinit var textureViews: List<TextureView>
    private lateinit var lensLabels: List<TextView>

    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurfaces: List<Surface> = emptyList()
    private var imageReaders: List<ImageReader> = emptyList()

    private var logicalRearId: String? = null
    private var physicalIds: List<String> = emptyList()
    private val focalById = mutableMapOf<String, Float>()
    private val maxJpegById = mutableMapOf<String, Size>()
    private val fallbackJpegById = mutableMapOf<String, Size>()

    private var openingCamera = false
    private var pendingPreviewOpen = false
    private var previewRunning = false
    private var capturing = false
    private var usingFallbackCapture = false

    private val frameCounters = LongArray(3)
    private val lastFrameCounters = LongArray(3)
    private var lastFpsTimeMs = 0L
    private var fpsRunning = false

    private val captureReceived = BooleanArray(3)
    private val captureTimestampsNs = LongArray(3)
    private val captureByteCounts = LongArray(3)
    private var captureBatch = ""

    private val lensNames = listOf("ULTRA", "MAIN", "TELE")

    private val fpsRunnable = object : Runnable {
        override fun run() {
            if (!fpsRunning) return
            val now = SystemClock.elapsedRealtime()
            val elapsed = (now - lastFpsTimeMs).coerceAtLeast(1L) / 1000.0
            val fps = DoubleArray(3)
            for (i in 0..2) {
                val count = frameCounters[i]
                fps[i] = (count - lastFrameCounters[i]) / elapsed
                lastFrameCounters[i] = count
            }
            lastFpsTimeMs = now
            updateLensLabels(fps)
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private val captureTimeoutRunnable = Runnable {
        if (!capturing) return@Runnable
        appendLog("CAPTURE TIMEOUT: received ${captureReceived.count { it }}/3 JPEGs")
        setStatus("Capture timeout; restoring 1080p preview")
        capturing = false
        cameraHandler?.post { resumePreviewAfterCapture() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            statusText = findViewById(R.id.statusText)
            logText = findViewById(R.id.logText)
            captureInfoText = findViewById(R.id.captureInfoText)
            textureViews = listOf(
                findViewById(R.id.preview1),
                findViewById(R.id.preview2),
                findViewById(R.id.preview3)
            )
            lensLabels = listOf(
                findViewById(R.id.label1),
                findViewById(R.id.label2),
                findViewById(R.id.label3)
            )

            cameraManager = getSystemService(CameraManager::class.java)
            startCameraThread()
            installTextureListeners()

            findViewById<Button>(R.id.scanButton).setOnClickListener { requestPermissionOrScan() }
            findViewById<Button>(R.id.startButton).setOnClickListener { start1080Preview() }
            findViewById<Button>(R.id.captureButton).setOnClickListener { startTripleCapture() }
            findViewById<Button>(R.id.stopButton).setOnClickListener { closeCamera("Stopped by user") }

            statusText.text = "Ready. SCAN first, then START 1080P."
            logText.text = "Camera2 Probe v0.3.0\n"
            appendLog("Preview fixed at 1920x1080 × 3")
            appendLog("Capture mode: MAX JPEG per physical camera with ~12MP fallback")
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal startup error", t)
            setContentView(android.R.layout.simple_list_item_1)
            findViewById<TextView>(android.R.id.text1)?.text =
                "Camera2 Probe startup error\n${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun installTextureListeners() {
        textureViews.forEachIndexed { index, view ->
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    if (pendingPreviewOpen && textureViews.all { it.isAvailable }) {
                        pendingPreviewOpen = false
                        openPreviewCamera()
                    }
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    if (fpsRunning && index in 0..2) frameCounters[index]++
                }
            }
        }
    }

    private fun requestPermissionOrScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanCameras()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            scanCameras()
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            setStatus("Camera permission denied")
        }
    }

    private fun startCameraThread() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("Camera2ProbeThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun scanCameras() {
        closeCamera(null)
        logicalRearId = null
        physicalIds = emptyList()
        focalById.clear()
        maxJpegById.clear()
        fallbackJpegById.clear()
        logText.text = "Camera2 Probe v0.3.0\n"
        appendLog("=== CAMERA2 CAPABILITY SCAN ===")

        val ids = try { cameraManager.cameraIdList } catch (t: Throwable) {
            appendLog("cameraIdList ERROR: ${t.message}")
            return
        }

        for (id in ids) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val logical = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                val pids = chars.physicalCameraIds.toList()
                appendLog("Camera $id facing=${facingName(facing)} logical=$logical physical=$pids")

                if (logicalRearId == null && facing == CameraCharacteristics.LENS_FACING_BACK && logical && pids.size >= 3) {
                    pids.forEach { pid ->
                        val pchars = cameraManager.getCameraCharacteristics(pid)
                        val focal = pchars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
                            ?: Float.MAX_VALUE
                        focalById[pid] = focal
                    }
                    physicalIds = pids.sortedBy { focalById[it] ?: Float.MAX_VALUE }.take(3)
                    logicalRearId = id
                }
            } catch (t: Throwable) {
                appendLog("Camera $id ERROR: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        if (logicalRearId == null || physicalIds.size != 3) {
            setStatus("3 physical rear cameras not found")
            return
        }

        appendLog("Logical rear: $logicalRearId")
        appendLog("Physical order: $physicalIds")

        physicalIds.forEachIndexed { index, pid ->
            try {
                val chars = cameraManager.getCameraCharacteristics(pid)
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
                val max = jpegSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                val fallback = jpegSizes
                    .filter { it.width.toLong() * it.height.toLong() <= FALLBACK_MAX_PIXELS }
                    .maxByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: max

                if (max != null) maxJpegById[pid] = max
                if (fallback != null) fallbackJpegById[pid] = fallback

                appendLog(
                    "${lensNames[index]} ID $pid focal=${formatFocal(pid)}mm " +
                        "JPEG_MAX=${sizeText(max)} fallback=${sizeText(fallback)}"
                )
            } catch (t: Throwable) {
                appendLog("JPEG size scan failed for physical $pid: ${t.message}")
            }
        }

        updateLensLabels(null)
        captureInfoText.text = buildCaptureInfo(maxJpegById)
        setStatus("Ready: 1080p × 3 preview; MAX JPEG capture available")
    }

    private fun start1080Preview() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            return
        }
        if (logicalRearId == null || physicalIds.size != 3) {
            setStatus("Run SCAN first")
            return
        }
        closeCamera(null)
        pendingPreviewOpen = true
        if (textureViews.all { it.isAvailable }) {
            pendingPreviewOpen = false
            openPreviewCamera()
        } else {
            setStatus("Waiting for preview surfaces...")
        }
    }

    private fun openPreviewCamera() {
        if (openingCamera || cameraDevice != null) return
        val logicalId = logicalRearId ?: return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        previewSurfaces = try {
            textureViews.map { view ->
                val texture = view.surfaceTexture ?: error("Missing SurfaceTexture")
                texture.setDefaultBufferSize(PREVIEW_WIDTH, PREVIEW_HEIGHT)
                Surface(texture)
            }
        } catch (t: Throwable) {
            appendLog("Preview surface error: ${t.message}")
            return
        }

        openingCamera = true
        setStatus("Opening 3-CAM 1080p preview...")
        cameraManager.openCamera(logicalId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                openingCamera = false
                cameraDevice = camera
                configurePreviewSession(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (cameraDevice === camera) cameraDevice = null
                openingCamera = false
                setStatus("Camera disconnected")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (cameraDevice === camera) cameraDevice = null
                openingCamera = false
                setStatus("Camera error $error")
            }
        }, cameraHandler)
    }

    private fun configurePreviewSession(camera: CameraDevice) {
        if (previewSurfaces.size != 3 || physicalIds.size != 3) return
        try {
            val outputs = previewSurfaces.zip(physicalIds).map { (surface, pid) ->
                OutputConfiguration(surface).apply { setPhysicalCameraId(pid) }
            }
            val executor = Executor { command -> cameraHandler?.post(command) }
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cameraDevice == null) {
                                session.close()
                                return
                            }
                            captureSession = session
                            try {
                                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                previewSurfaces.forEach { builder.addTarget(it) }
                                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                session.setRepeatingRequest(builder.build(), null, cameraHandler)
                                previewRunning = true
                                capturing = false
                                startFpsMeter()
                                setStatus("SUCCESS: 3-CAM 1920×1080 preview running")
                                appendLog("TRIPLE PREVIEW: SUCCESS @ 1920x1080 × 3")
                            } catch (t: Throwable) {
                                appendLog("Preview request failed: ${t.message}")
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            session.close()
                            setStatus("1080p triple preview rejected by HAL")
                        }
                    }
                )
            )
        } catch (t: Throwable) {
            appendLog("Preview session error: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun startTripleCapture() {
        val camera = cameraDevice
        if (camera == null || !previewRunning) {
            setStatus("Start 1080p preview first")
            return
        }
        if (capturing) return
        if (maxJpegById.size < 3) {
            setStatus("JPEG sizes unavailable; run SCAN again")
            return
        }

        capturing = true
        usingFallbackCapture = false
        previewRunning = false
        stopFpsMeter()
        captureReceived.fill(false)
        captureTimestampsNs.fill(0L)
        captureByteCounts.fill(0L)
        captureBatch = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

        appendLog("")
        appendLog("=== TRIPLE JPEG CAPTURE ===")
        appendLog("Trying MAX JPEG sizes first")
        setStatus("Capturing 3 cameras at MAX JPEG resolution...")

        try { captureSession?.stopRepeating() } catch (_: Throwable) {}
        try { captureSession?.abortCaptures() } catch (_: Throwable) {}
        try { captureSession?.close() } catch (_: Throwable) {}
        captureSession = null

        cameraHandler?.postDelayed({ configureCaptureSession(camera, false) }, 100L)
    }

    private fun configureCaptureSession(camera: CameraDevice, fallback: Boolean) {
        usingFallbackCapture = fallback
        closeImageReaders()

        val sizeMap = if (fallback) fallbackJpegById else maxJpegById
        val sizes = physicalIds.mapNotNull { sizeMap[it] }
        if (sizes.size != 3) {
            failCapture("Missing JPEG output sizes")
            return
        }

        appendLog("Capture profile: ${if (fallback) "~12MP FALLBACK" else "MAX"}")
        physicalIds.forEachIndexed { i, pid ->
            appendLog("  ${lensNames[i]} ID $pid -> ${sizeText(sizes[i])}")
        }
        runOnUiThread { captureInfoText.text = buildCaptureInfo(sizeMap) }

        imageReaders = sizes.mapIndexed { index, size ->
            ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader -> onJpegAvailable(index, size, reader) }, cameraHandler)
            }
        }

        try {
            val outputs = imageReaders.zip(physicalIds).map { (reader, pid) ->
                OutputConfiguration(reader.surface).apply { setPhysicalCameraId(pid) }
            }
            val executor = Executor { command -> cameraHandler?.post(command) }
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (!capturing || cameraDevice == null) {
                                session.close()
                                return
                            }
                            captureSession = session
                            issueStillCapture(camera, session)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            session.close()
                            captureSession = null
                            appendLog("JPEG session CONFIGURE FAILED (${if (fallback) "fallback" else "MAX"})")
                            if (!fallback && capturing) {
                                appendLog("Retrying automatically with ~12MP per camera...")
                                cameraHandler?.postDelayed({ configureCaptureSession(camera, true) }, 150L)
                            } else {
                                failCapture("Triple JPEG session rejected by HAL")
                            }
                        }
                    }
                )
            )
        } catch (t: Throwable) {
            appendLog("Capture session exception: ${t.javaClass.simpleName}: ${t.message}")
            if (!fallback && capturing) {
                cameraHandler?.postDelayed({ configureCaptureSession(camera, true) }, 150L)
            } else {
                failCapture("Capture session creation failed")
            }
        }
    }

    private fun issueStillCapture(camera: CameraDevice, session: CameraCaptureSession) {
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            imageReaders.forEach { builder.addTarget(it.surface) }
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())

            session.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        timestamp: Long,
                        frameNumber: Long
                    ) {
                        appendLog("Still capture started: frame=$frameNumber requestTs=$timestamp")
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        appendLog("Still capture FAILED reason=${failure.reason}")
                        failCapture("Still capture request failed")
                    }
                },
                cameraHandler
            )
            mainHandler.removeCallbacks(captureTimeoutRunnable)
            mainHandler.postDelayed(captureTimeoutRunnable, CAPTURE_TIMEOUT_MS)
        } catch (t: Throwable) {
            appendLog("Still capture exception: ${t.javaClass.simpleName}: ${t.message}")
            failCapture("Still capture failed")
        }
    }

    private fun onJpegAvailable(index: Int, size: Size, reader: ImageReader) {
        val image = try { reader.acquireNextImage() } catch (t: Throwable) {
            appendLog("${lensNames[index]} acquire image failed: ${t.message}")
            return
        } ?: return

        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val timestamp = image.timestamp
            val path = saveJpeg(bytes, index, size)

            captureReceived[index] = true
            captureTimestampsNs[index] = timestamp
            captureByteCounts[index] = bytes.size.toLong()
            appendLog(
                "${lensNames[index]} SAVED ${size.width}x${size.height} " +
                    "${bytes.size / 1024} KiB ts=$timestamp -> $path"
            )
        } catch (t: Throwable) {
            appendLog("${lensNames[index]} save error: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            image.close()
        }

        if (captureReceived.all { it }) finishTripleCapture()
    }

    private fun finishTripleCapture() {
        if (!capturing) return
        capturing = false
        mainHandler.removeCallbacks(captureTimeoutRunnable)

        val valid = captureTimestampsNs.filter { it > 0L }
        val deltaNs = if (valid.size == 3) (valid.maxOrNull()!! - valid.minOrNull()!!) else -1L
        appendLog("TRIPLE JPEG CAPTURE: SUCCESS (${if (usingFallbackCapture) "fallback" else "MAX"})")
        if (deltaNs >= 0L) {
            appendLog("Sensor timestamp spread: ${"%.1f".format(Locale.US, deltaNs / 1000.0)} µs")
            setStatus("3 photos saved; timestamp spread ${"%.1f".format(Locale.US, deltaNs / 1000.0)} µs")
        } else {
            appendLog("Sensor timestamp spread: unavailable")
            setStatus("3 photos saved successfully")
        }

        cameraHandler?.postDelayed({ resumePreviewAfterCapture() }, 300L)
    }

    private fun failCapture(message: String) {
        if (!capturing) return
        capturing = false
        mainHandler.removeCallbacks(captureTimeoutRunnable)
        appendLog("CAPTURE FAILED: $message")
        setStatus("$message; restoring preview")
        cameraHandler?.postDelayed({ resumePreviewAfterCapture() }, 250L)
    }

    private fun resumePreviewAfterCapture() {
        try { captureSession?.close() } catch (_: Throwable) {}
        captureSession = null
        closeImageReaders()
        val camera = cameraDevice ?: return
        if (previewSurfaces.size == 3 && textureViews.all { it.isAvailable }) {
            configurePreviewSession(camera)
        }
    }

    private fun saveJpeg(bytes: ByteArray, index: Int, size: Size): String {
        val name = "C2P_${captureBatch}_${lensNames[index]}_${size.width}x${size.height}.jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camera2Probe")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("MediaStore output stream failed")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            "Pictures/Camera2Probe/$name"
        } else {
            val base = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
            val dir = File(base, "Camera2Probe").apply { mkdirs() }
            val file = File(dir, name)
            FileOutputStream(file).use { it.write(bytes) }
            file.absolutePath
        }
    }

    private fun jpegOrientation(): Int {
        val logicalId = logicalRearId ?: return 0
        val sensor = cameraManager.getCameraCharacteristics(logicalId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val device = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensor - device + 360) % 360
    }

    private fun startFpsMeter() {
        fpsRunning = true
        for (i in 0..2) {
            frameCounters[i] = 0L
            lastFrameCounters[i] = 0L
        }
        lastFpsTimeMs = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(fpsRunnable)
        mainHandler.postDelayed(fpsRunnable, 1000L)
    }

    private fun stopFpsMeter() {
        fpsRunning = false
        mainHandler.removeCallbacks(fpsRunnable)
    }

    private fun updateLensLabels(fps: DoubleArray?) {
        for (i in 0..2) {
            val pid = physicalIds.getOrNull(i)
            val idText = pid ?: "--"
            val focal = if (pid != null) formatFocal(pid) else "--"
            val fpsText = if (fps != null) "${"%.1f".format(Locale.US, fps[i])} fps" else "-- fps"
            lensLabels[i].text = "${lensNames[i]}\nID $idText · ${focal}mm\n$fpsText"
        }
    }

    private fun buildCaptureInfo(map: Map<String, Size>): String {
        if (physicalIds.size != 3) return "Capture: max JPEG per physical camera"
        return physicalIds.mapIndexed { i, id -> "${lensNames[i]} ${sizeText(map[id])}" }
            .joinToString("  |  ")
    }

    private fun formatFocal(id: String): String {
        val focal = focalById[id] ?: return "?"
        return "%.2f".format(Locale.US, focal)
    }

    private fun sizeText(size: Size?): String = size?.let { "${it.width}x${it.height}" } ?: "?"

    private fun facingName(value: Int?): String = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun closeImageReaders() {
        imageReaders.forEach { try { it.close() } catch (_: Throwable) {} }
        imageReaders = emptyList()
    }

    private fun closeCamera(reason: String?) {
        stopFpsMeter()
        mainHandler.removeCallbacks(captureTimeoutRunnable)
        pendingPreviewOpen = false
        previewRunning = false
        capturing = false

        try { captureSession?.stopRepeating() } catch (_: Throwable) {}
        try { captureSession?.close() } catch (_: Throwable) {}
        captureSession = null
        closeImageReaders()

        try { cameraDevice?.close() } catch (_: Throwable) {}
        cameraDevice = null
        openingCamera = false

        previewSurfaces.forEach { try { it.release() } catch (_: Throwable) {} }
        previewSurfaces = emptyList()

        if (reason != null) setStatus(reason)
    }

    private fun appendLog(text: String) {
        Log.d(TAG, text)
        runOnUiThread {
            if (::logText.isInitialized) {
                logText.append(text)
                logText.append("\n")
            }
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread { if (::statusText.isInitialized) statusText.text = text }
    }

    override fun onPause() {
        closeCamera(null)
        super.onPause()
    }

    override fun onDestroy() {
        closeCamera(null)
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        super.onDestroy()
    }
}
