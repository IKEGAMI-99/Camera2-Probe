package com.ikegami.camera2probe

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
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
import android.view.View
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * TRI // CAM camera engine.
 *
 * The important difference from the old probe is that preview and still JPEG outputs live in the
 * same CameraCaptureSession whenever the HAL accepts the six-output configuration. That preserves
 * the 3A state (AF/AE/AWB) instead of throwing it away immediately before every still capture.
 */
class TriCamActivity : Activity() {

    companion object {
        private const val TAG = "TriCamEngine"
        private const val CAMERA_PERMISSION_REQUEST = 10
        private const val PREVIEW_WIDTH = 1920
        private const val PREVIEW_HEIGHT = 1080
        private const val FALLBACK_MAX_PIXELS = 12_500_000L
        private const val THREE_A_TIMEOUT_MS = 1400L
        private const val JPEG_TIMEOUT_MS = 15_000L
        private const val PREFS_NAME = "triple_cam_ui"
        private const val PREF_FPS = "show_fps"
        private const val PREF_DIAGNOSTICS = "show_diagnostics"
    }

    private lateinit var cameraManager: CameraManager
    private lateinit var statusText: TextView
    private lateinit var linkBadge: TextView
    private lateinit var logText: TextView
    private lateinit var diagnosticPanel: View
    private lateinit var captureInfoText: TextView
    private lateinit var captureButton: View
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
    private val afRequiredById = mutableMapOf<String, Boolean>()
    private val afModeById = mutableMapOf<String, Int>()

    private var openingCamera = false
    private var previewRunning = false
    private var captureInProgress = false
    private var pendingAutoStart = false
    private var activityResumed = false

    /** True when the active session owns preview x3 and JPEG x3 together. */
    private var integratedCaptureSession = false
    private var integratedUsesFallbackJpeg = false

    private var fpsOverlayEnabled = true
    private var diagnosticsVisible = false

    private val frameCounters = LongArray(3)
    private val lastFrameCounters = LongArray(3)
    private var lastFpsTimeMs = 0L
    private var fpsRunning = false

    private val afStates = IntArray(3) { -1 }
    private val aeStates = IntArray(3) { -1 }
    private val awbStates = IntArray(3) { -1 }
    private val exposureNs = LongArray(3)
    private val isoValues = IntArray(3)
    private val focusDistances = FloatArray(3)
    private var waitingFor3A = false
    private var last3ALogSignature = ""

    private val captureReceived = BooleanArray(3)
    private val captureTimestampsNs = LongArray(3)
    private var captureBatch = ""
    private var legacyCaptureFallback = false

    // Camera/physical order: ULTRA -> MAIN -> TELE. Screen order is independent of this list.
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

    private val threeATimeoutRunnable = Runnable {
        if (!waitingFor3A || !captureInProgress) return@Runnable
        appendLog("3A wait timeout; capturing with latest stable state")
        append3ADiagnostics("3A TIMEOUT")
        waitingFor3A = false
        cameraHandler?.post { captureStillNow() }
    }

    private val jpegTimeoutRunnable = Runnable {
        if (!captureInProgress) return@Runnable
        appendLog("JPEG TIMEOUT: received ${captureReceived.count { it }}/3")
        captureInProgress = false
        waitingFor3A = false
        setStatus("CAPTURE TIMEOUT")
        setLinkBadge("RECOVER")
        if (!integratedCaptureSession) cameraHandler?.post { restorePreviewAfterLegacyCapture() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            statusText = findViewById(R.id.statusText)
            linkBadge = findViewById(R.id.linkBadge)
            logText = findViewById(R.id.logText)
            diagnosticPanel = findViewById(R.id.diagnosticPanel)
            captureInfoText = findViewById(R.id.captureInfoText)
            captureButton = findViewById(R.id.captureButton)

            val previewUltra: TextureView = findViewById(R.id.previewUltra)
            val previewMain: TextureView = findViewById(R.id.previewMain)
            val previewTele: TextureView = findViewById(R.id.previewTele)
            textureViews = listOf(previewUltra, previewMain, previewTele)

            val labelUltra: TextView = findViewById(R.id.labelUltra)
            val labelMain: TextView = findViewById(R.id.labelMain)
            val labelTele: TextView = findViewById(R.id.labelTele)
            lensLabels = listOf(labelUltra, labelMain, labelTele)

            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            fpsOverlayEnabled = prefs.getBoolean(PREF_FPS, true)
            diagnosticsVisible = prefs.getBoolean(PREF_DIAGNOSTICS, false)
            applyDiagnosticsVisibility()

            cameraManager = getSystemService(CameraManager::class.java)
            startCameraThread()
            installTextureListeners()
            installUiActions()

            logText.text = "TRI // CAM v0.5.0\n"
            appendLog("Camera engine: persistent 3A + integrated still capture")
            appendLog("Preview: 1920x1080 × 3")
            setStatus("INITIALIZING TRIPLE OPTICS")
            setLinkBadge("BOOT")

            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal startup error", t)
            setContentView(android.R.layout.simple_list_item_1)
            findViewById<TextView>(android.R.id.text1)?.text =
                "TRI // CAM startup error\n${t.javaClass.simpleName}: ${t.message}"
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            mainHandler.postDelayed({ ensureCameraStarted() }, 350L)
        }
    }

    override fun onPause() {
        activityResumed = false
        mainHandler.removeCallbacks(threeATimeoutRunnable)
        mainHandler.removeCallbacks(jpegTimeoutRunnable)
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            mainHandler.postDelayed({ ensureCameraStarted() }, 250L)
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            setStatus("CAMERA PERMISSION DENIED")
            setLinkBadge("OFFLINE")
        }
    }

    private fun installUiActions() {
        captureButton.setOnClickListener {
            it.animate().scaleX(0.90f).scaleY(0.90f).setDuration(70L).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
            }.start()
            beginTripleCapture()
        }
        findViewById<View>(R.id.settingsButton).setOnClickListener { showSettings() }
        findViewById<View>(R.id.galleryButton).setOnClickListener { openGallery() }
    }

    private fun installTextureListeners() {
        textureViews.forEachIndexed { index, view ->
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    if (pendingAutoStart && activityResumed && textureViews.all { it.isAvailable }) {
                        pendingAutoStart = false
                        startTripleCamera()
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

    private fun startCameraThread() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("TriCamCameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun ensureCameraStarted() {
        if (!activityResumed || captureInProgress || openingCamera || previewRunning) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        if (logicalRearId == null || physicalIds.size != 3) {
            scanCameras()
        }
        if (logicalRearId == null || physicalIds.size != 3) return

        if (!textureViews.all { it.isAvailable }) {
            pendingAutoStart = true
            setStatus("WAITING FOR PREVIEW SURFACES")
            mainHandler.postDelayed({ ensureCameraStarted() }, 150L)
            return
        }

        pendingAutoStart = false
        startTripleCamera()
    }

    private fun scanCameras() {
        logicalRearId = null
        physicalIds = emptyList()
        focalById.clear()
        maxJpegById.clear()
        fallbackJpegById.clear()
        afRequiredById.clear()
        afModeById.clear()

        setStatus("SCANNING PHYSICAL CAMERAS")
        setLinkBadge("SCAN")
        appendLog("=== CAMERA2 CAPABILITY SCAN ===")

        val ids = try { cameraManager.cameraIdList } catch (t: Throwable) {
            appendLog("cameraIdList ERROR: ${t.message}")
            setStatus("CAMERA ENUMERATION FAILED")
            setLinkBadge("ERROR")
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
            setStatus("TRIPLE CAMERA PATH NOT FOUND")
            setLinkBadge("ERROR")
            return
        }

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

                val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                val afRequired = minFocus > 0f && afModes.any { it != CaptureRequest.CONTROL_AF_MODE_OFF }
                val afMode = when {
                    afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
                    else -> CaptureRequest.CONTROL_AF_MODE_OFF
                }
                afRequiredById[pid] = afRequired
                afModeById[pid] = afMode

                appendLog(
                    "${lensNames[index]} ID $pid focal=${formatFocal(pid)}mm " +
                        "JPEG=${sizeText(max)} AF=${afModeName(afMode)} fixed=${!afRequired}"
                )
            } catch (t: Throwable) {
                appendLog("Physical $pid scan failed: ${t.message}")
            }
        }

        captureInfoText.text = compactCaptureInfo(maxJpegById)
        updateLensLabels(null)
        setStatus("3 OPTICS READY · BUILDING 3A SESSION")
        setLinkBadge("3× READY")
    }

    private fun startTripleCamera() {
        if (openingCamera || cameraDevice != null || !activityResumed) return
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
            mainHandler.postDelayed({ ensureCameraStarted() }, 250L)
            return
        }

        openingCamera = true
        setStatus("OPENING LOGICAL TRIPLE CAMERA")
        setLinkBadge("LINKING")
        cameraManager.openCamera(logicalId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                openingCamera = false
                cameraDevice = camera
                configureIntegratedSession(camera, useFallbackJpeg = false)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (cameraDevice === camera) cameraDevice = null
                openingCamera = false
                previewRunning = false
                setStatus("CAMERA DISCONNECTED")
                setLinkBadge("OFFLINE")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (cameraDevice === camera) cameraDevice = null
                openingCamera = false
                previewRunning = false
                setStatus("CAMERA ERROR $error")
                setLinkBadge("ERROR")
            }
        }, cameraHandler)
    }

    /** Attempt six outputs: 3 x TextureView + 3 x JPEG ImageReader. */
    private fun configureIntegratedSession(camera: CameraDevice, useFallbackJpeg: Boolean) {
        closeSessionOnly()
        closeImageReaders()

        val sizeMap = if (useFallbackJpeg) fallbackJpegById else maxJpegById
        val sizes = physicalIds.mapNotNull { sizeMap[it] }
        if (sizes.size != 3) {
            configurePreviewOnlySession(camera)
            return
        }

        imageReaders = sizes.mapIndexed { index, size ->
            ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader -> onJpegAvailable(index, size, reader) }, cameraHandler)
            }
        }

        val outputs = mutableListOf<OutputConfiguration>()
        previewSurfaces.zip(physicalIds).forEach { (surface, pid) ->
            outputs += OutputConfiguration(surface).apply { setPhysicalCameraId(pid) }
        }
        imageReaders.zip(physicalIds).forEach { (reader, pid) ->
            outputs += OutputConfiguration(reader.surface).apply { setPhysicalCameraId(pid) }
        }

        val executor = Executor { command -> cameraHandler?.post(command) }
        try {
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cameraDevice == null || !activityResumed) {
                                session.close()
                                return
                            }
                            captureSession = session
                            integratedCaptureSession = true
                            integratedUsesFallbackJpeg = useFallbackJpeg
                            captureInfoText.post {
                                captureInfoText.text = (if (useFallbackJpeg) "12MP 3A-LIVE · " else "MAX 3A-LIVE · ") + compactCaptureInfo(sizeMap)
                            }
                            appendLog(
                                "Integrated session SUCCESS: 3 preview + 3 JPEG (${if (useFallbackJpeg) "fallback" else "MAX"})"
                            )
                            startRepeatingPreview(session)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            session.close()
                            appendLog("Integrated 6-output session rejected (${if (useFallbackJpeg) "fallback" else "MAX"})")
                            closeImageReaders()
                            if (!useFallbackJpeg) {
                                cameraHandler?.postDelayed({ configureIntegratedSession(camera, true) }, 120L)
                            } else {
                                cameraHandler?.postDelayed({ configurePreviewOnlySession(camera) }, 120L)
                            }
                        }
                    }
                )
            )
        } catch (t: Throwable) {
            appendLog("Integrated session exception: ${t.javaClass.simpleName}: ${t.message}")
            closeImageReaders()
            if (!useFallbackJpeg) {
                cameraHandler?.postDelayed({ configureIntegratedSession(camera, true) }, 120L)
            } else {
                cameraHandler?.postDelayed({ configurePreviewOnlySession(camera) }, 120L)
            }
        }
    }

    private fun configurePreviewOnlySession(camera: CameraDevice) {
        closeSessionOnly()
        closeImageReaders()
        integratedCaptureSession = false
        integratedUsesFallbackJpeg = false

        val outputs = previewSurfaces.zip(physicalIds).map { (surface, pid) ->
            OutputConfiguration(surface).apply { setPhysicalCameraId(pid) }
        }
        val executor = Executor { command -> cameraHandler?.post(command) }
        try {
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cameraDevice == null || !activityResumed) {
                                session.close()
                                return
                            }
                            captureSession = session
                            appendLog("Preview-only fallback session SUCCESS")
                            captureInfoText.post { captureInfoText.text = "LEGACY STILL · MAX JPEG × 3" }
                            startRepeatingPreview(session)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            session.close()
                            setStatus("TRIPLE PREVIEW SESSION FAILED")
                            setLinkBadge("ERROR")
                        }
                    }
                )
            )
        } catch (t: Throwable) {
            appendLog("Preview-only session error: ${t.message}")
            setStatus("TRIPLE PREVIEW SESSION FAILED")
            setLinkBadge("ERROR")
        }
    }

    private fun startRepeatingPreview(session: CameraCaptureSession) {
        val camera = cameraDevice ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurfaces.forEach { builder.addTarget(it) }
            applyAuto3A(builder, lockAeAwb = false)

            session.setRepeatingRequest(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        update3AState(result)
                    }
                },
                cameraHandler
            )
            previewRunning = true
            captureInProgress = false
            waitingFor3A = false
            startFpsMeter()
            setStatus("LIVE · CONTINUOUS AF / AE / AWB")
            setLinkBadge(if (integratedCaptureSession) "3A LIVE" else "3× LIVE")
            appendLog("Repeating preview: CONTINUOUS_AF + AE_ON + AWB_AUTO")
        } catch (t: Throwable) {
            appendLog("Repeating preview failed: ${t.message}")
            setStatus("3A PREVIEW REQUEST FAILED")
            setLinkBadge("ERROR")
        }
    }

    /**
     * Apply 3A globally and, where the HAL exposes physical request keys, per physical camera too.
     * setPhysicalCameraKey can legitimately reject a key, so every physical assignment is guarded.
     */
    private fun applyAuto3A(builder: CaptureRequest.Builder, lockAeAwb: Boolean) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        try { builder.set(CaptureRequest.CONTROL_AE_LOCK, lockAeAwb) } catch (_: Throwable) {}
        try { builder.set(CaptureRequest.CONTROL_AWB_LOCK, lockAeAwb) } catch (_: Throwable) {}

        physicalIds.forEach { pid ->
            val afMode = afModeById[pid] ?: CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            try { builder.setPhysicalCameraKey(CaptureRequest.CONTROL_AF_MODE, afMode, pid) } catch (_: Throwable) {}
            try { builder.setPhysicalCameraKey(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON, pid) } catch (_: Throwable) {}
            try { builder.setPhysicalCameraKey(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO, pid) } catch (_: Throwable) {}
            try { builder.setPhysicalCameraKey(CaptureRequest.CONTROL_AE_LOCK, lockAeAwb, pid) } catch (_: Throwable) {}
            try { builder.setPhysicalCameraKey(CaptureRequest.CONTROL_AWB_LOCK, lockAeAwb, pid) } catch (_: Throwable) {}
        }
    }

    private fun beginTripleCapture() {
        if (!previewRunning || captureInProgress || captureSession == null || cameraDevice == null) {
            setStatus("PREVIEW / 3A NOT READY")
            return
        }

        captureInProgress = true
        waitingFor3A = true
        legacyCaptureFallback = !integratedCaptureSession
        captureReceived.fill(false)
        captureTimestampsNs.fill(0L)
        captureBatch = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

        setStatus("LOCKING FOCUS · EXPOSURE · WHITE BALANCE")
        setLinkBadge("3A LOCK")
        appendLog("=== CAPTURE: waiting for physical 3A convergence ===")

        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        try {
            val trigger = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurfaces.forEach { trigger.addTarget(it) }
            applyAuto3A(trigger, lockAeAwb = false)
            try { trigger.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START) } catch (_: Throwable) {}
            try {
                trigger.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
                )
            } catch (_: Throwable) {}

            session.capture(
                trigger.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        update3AState(result)
                    }
                },
                cameraHandler
            )
            mainHandler.removeCallbacks(threeATimeoutRunnable)
            mainHandler.postDelayed(threeATimeoutRunnable, THREE_A_TIMEOUT_MS)
        } catch (t: Throwable) {
            appendLog("3A trigger failed: ${t.message}; capturing immediately")
            waitingFor3A = false
            cameraHandler?.post { captureStillNow() }
        }
    }

    private fun update3AState(result: TotalCaptureResult) {
        val physical = try { result.physicalCameraResults } catch (_: Throwable) { emptyMap() }

        for (i in 0..2) {
            val pid = physicalIds.getOrNull(i) ?: continue
            val r: CaptureResult = physical[pid] ?: result
            afStates[i] = r.get(CaptureResult.CONTROL_AF_STATE) ?: -1
            aeStates[i] = r.get(CaptureResult.CONTROL_AE_STATE) ?: -1
            awbStates[i] = r.get(CaptureResult.CONTROL_AWB_STATE) ?: -1
            exposureNs[i] = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            isoValues[i] = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            focusDistances[i] = r.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
        }

        val signature = (0..2).joinToString("|") { "${afStates[it]},${aeStates[it]},${awbStates[it]}" }
        if (signature != last3ALogSignature) {
            last3ALogSignature = signature
            appendLog(
                "3A " + (0..2).joinToString(" · ") { i ->
                    "${lensNames[i]} AF=${afStateName(afStates[i])} AE=${aeStateName(aeStates[i])} AWB=${awbStateName(awbStates[i])}"
                }
            )
        }

        if (waitingFor3A && captureInProgress && threeAReady()) {
            waitingFor3A = false
            mainHandler.removeCallbacks(threeATimeoutRunnable)
            append3ADiagnostics("3A READY")
            setStatus("3A LOCKED · CAPTURING")
            setLinkBadge("CAPTURE")
            cameraHandler?.post { captureStillNow() }
        }
    }

    private fun threeAReady(): Boolean {
        if (physicalIds.size != 3) return false
        for (i in 0..2) {
            val pid = physicalIds[i]
            val afRequired = afRequiredById[pid] == true
            val afReady = !afRequired || afStates[i] in setOf(
                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
            )
            val aeReady = aeStates[i] in setOf(
                CaptureResult.CONTROL_AE_STATE_CONVERGED,
                CaptureResult.CONTROL_AE_STATE_LOCKED,
                CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
            )
            val awbReady = awbStates[i] in setOf(
                CaptureResult.CONTROL_AWB_STATE_CONVERGED,
                CaptureResult.CONTROL_AWB_STATE_LOCKED
            )
            if (!afReady || !aeReady || !awbReady) return false
        }
        return true
    }

    private fun captureStillNow() {
        if (!captureInProgress) return
        if (integratedCaptureSession && imageReaders.size == 3) {
            captureStillInIntegratedSession()
        } else {
            switchToLegacyJpegSession(useFallback = false)
        }
    }

    private fun captureStillInIntegratedSession() {
        val camera = cameraDevice ?: return failCapture("Camera missing")
        val session = captureSession ?: return failCapture("Session missing")
        try {
            val still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            imageReaders.forEach { still.addTarget(it.surface) }
            applyAuto3A(still, lockAeAwb = true)
            try { still.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE) } catch (_: Throwable) {}
            try {
                still.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                )
            } catch (_: Throwable) {}
            still.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
            try { still.set(CaptureRequest.JPEG_QUALITY, 95.toByte()) } catch (_: Throwable) {}

            session.capture(
                still.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        appendPhysicalCaptureResult(result)
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        failCapture("Still request failed reason=${failure.reason}")
                    }
                },
                cameraHandler
            )
            mainHandler.removeCallbacks(jpegTimeoutRunnable)
            mainHandler.postDelayed(jpegTimeoutRunnable, JPEG_TIMEOUT_MS)
        } catch (t: Throwable) {
            failCapture("Integrated still exception: ${t.message}")
        }
    }

    /** Fallback only for HALs that reject preview x3 + JPEG x3 in one session. */
    private fun switchToLegacyJpegSession(useFallback: Boolean) {
        val camera = cameraDevice ?: return failCapture("Camera missing")
        closeSessionOnly()
        closeImageReaders()

        val sizeMap = if (useFallback) fallbackJpegById else maxJpegById
        val sizes = physicalIds.mapNotNull { sizeMap[it] }
        if (sizes.size != 3) return failCapture("JPEG sizes unavailable")

        imageReaders = sizes.mapIndexed { index, size ->
            ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader -> onJpegAvailable(index, size, reader) }, cameraHandler)
            }
        }
        val outputs = imageReaders.zip(physicalIds).map { (reader, pid) ->
            OutputConfiguration(reader.surface).apply { setPhysicalCameraId(pid) }
        }
        val executor = Executor { command -> cameraHandler?.post(command) }

        try {
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            legacyCaptureFallback = true
                            issueLegacyStill(camera, session)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            session.close()
                            if (!useFallback) {
                                cameraHandler?.postDelayed({ switchToLegacyJpegSession(true) }, 120L)
                            } else {
                                failCapture("JPEG fallback session rejected")
                            }
                        }
                    }
                )
            )
        } catch (t: Throwable) {
            if (!useFallback) {
                cameraHandler?.postDelayed({ switchToLegacyJpegSession(true) }, 120L)
            } else {
                failCapture("JPEG fallback exception: ${t.message}")
            }
        }
    }

    private fun issueLegacyStill(camera: CameraDevice, session: CameraCaptureSession) {
        try {
            val still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            imageReaders.forEach { still.addTarget(it.surface) }
            applyAuto3A(still, lockAeAwb = true)
            still.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
            try { still.set(CaptureRequest.JPEG_QUALITY, 95.toByte()) } catch (_: Throwable) {}
            session.capture(still.build(), null, cameraHandler)
            mainHandler.removeCallbacks(jpegTimeoutRunnable)
            mainHandler.postDelayed(jpegTimeoutRunnable, JPEG_TIMEOUT_MS)
        } catch (t: Throwable) {
            failCapture("Legacy still failed: ${t.message}")
        }
    }

    private fun onJpegAvailable(index: Int, size: Size, reader: ImageReader) {
        val image = try { reader.acquireNextImage() } catch (t: Throwable) {
            appendLog("${lensNames[index]} acquire failed: ${t.message}")
            return
        } ?: return

        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            captureReceived[index] = true
            captureTimestampsNs[index] = image.timestamp
            val path = saveJpeg(bytes, index, size)
            appendLog(
                "${lensNames[index]} SAVED ${size.width}x${size.height} ${bytes.size / 1024}KiB " +
                    "ts=${image.timestamp} -> $path"
            )
        } catch (t: Throwable) {
            appendLog("${lensNames[index]} save failed: ${t.message}")
        } finally {
            image.close()
        }

        if (captureReceived.all { it }) finishCapture()
    }

    private fun finishCapture() {
        if (!captureInProgress) return
        captureInProgress = false
        waitingFor3A = false
        mainHandler.removeCallbacks(threeATimeoutRunnable)
        mainHandler.removeCallbacks(jpegTimeoutRunnable)

        val valid = captureTimestampsNs.filter { it > 0L }
        val spreadNs = if (valid.size == 3) valid.maxOrNull()!! - valid.minOrNull()!! else -1L
        val spreadUs = if (spreadNs >= 0) "%.1f".format(Locale.US, spreadNs / 1000.0) else "?"
        appendLog("TRIPLE JPEG SUCCESS · timestamp spread=$spreadUs µs")
        setStatus("3 PHOTOS SAVED · Δ $spreadUs µs")
        setLinkBadge("SAVED")

        if (legacyCaptureFallback) {
            cameraHandler?.postDelayed({ restorePreviewAfterLegacyCapture() }, 180L)
        } else {
            // Same session remains alive. AE/AWB lock only existed on the one-shot still request;
            // the repeating preview continues with unlocked continuous 3A.
            setLinkBadge("3A LIVE")
            mainHandler.postDelayed({ setStatus("LIVE · CONTINUOUS AF / AE / AWB") }, 650L)
        }
    }

    private fun restorePreviewAfterLegacyCapture() {
        if (!activityResumed) return
        closeSessionOnly()
        closeImageReaders()
        val camera = cameraDevice ?: return
        integratedCaptureSession = false
        legacyCaptureFallback = false
        cameraHandler?.postDelayed({ configureIntegratedSession(camera, false) }, 120L)
    }

    private fun failCapture(message: String) {
        appendLog("CAPTURE FAILED: $message")
        captureInProgress = false
        waitingFor3A = false
        mainHandler.removeCallbacks(threeATimeoutRunnable)
        mainHandler.removeCallbacks(jpegTimeoutRunnable)
        setStatus("CAPTURE FAILED")
        setLinkBadge("ERROR")
        if (legacyCaptureFallback) cameraHandler?.postDelayed({ restorePreviewAfterLegacyCapture() }, 180L)
    }

    private fun appendPhysicalCaptureResult(result: TotalCaptureResult) {
        val physical = try { result.physicalCameraResults } catch (_: Throwable) { emptyMap() }
        appendLog("=== PHYSICAL STILL RESULT ===")
        physicalIds.forEachIndexed { i, pid ->
            val r: CaptureResult = physical[pid] ?: result
            val ts = r.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
            val exp = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val fd = r.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
            appendLog(
                "${lensNames[i]} ts=$ts exp=${"%.2f".format(Locale.US, exp / 1_000_000.0)}ms " +
                    "ISO=$iso focus=${"%.3f".format(Locale.US, fd)}D " +
                    "AF=${afStateName(r.get(CaptureResult.CONTROL_AF_STATE) ?: -1)} " +
                    "AE=${aeStateName(r.get(CaptureResult.CONTROL_AE_STATE) ?: -1)}"
            )
        }
    }

    private fun append3ADiagnostics(prefix: String) {
        appendLog(
            prefix + " · " + (0..2).joinToString(" · ") { i ->
                "${lensNames[i]} AF=${afStateName(afStates[i])} AE=${aeStateName(aeStates[i])} " +
                    "AWB=${awbStateName(awbStates[i])} exp=${"%.2f".format(Locale.US, exposureNs[i] / 1_000_000.0)}ms " +
                    "ISO=${isoValues[i]} focus=${"%.3f".format(Locale.US, focusDistances[i])}D"
            }
        )
    }

    private fun saveJpeg(bytes: ByteArray, index: Int, size: Size): String {
        val name = "TRICAM_${captureBatch}_${lensNames[index]}_${size.width}x${size.height}.jpg"
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

    private fun openGallery() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
            } catch (_: Throwable) {
                setStatus("NO GALLERY APP AVAILABLE")
            }
        }
    }

    private fun showSettings() {
        val items = arrayOf("プレビューにFPSを表示", "診断ログを表示")
        val checked = booleanArrayOf(fpsOverlayEnabled, diagnosticsVisible)
        AlertDialog.Builder(this)
            .setTitle("TRI // CAM SETTINGS")
            .setMultiChoiceItems(items, checked) { _, which, enabled ->
                when (which) {
                    0 -> {
                        fpsOverlayEnabled = enabled
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_FPS, enabled).apply()
                        updateLensLabels(null)
                    }
                    1 -> {
                        diagnosticsVisible = enabled
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_DIAGNOSTICS, enabled).apply()
                        applyDiagnosticsVisibility()
                    }
                }
            }
            .setNeutralButton("カメラ再起動") { _, _ -> restartCamera() }
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun restartCamera() {
        closeCamera(null)
        logicalRearId = null
        physicalIds = emptyList()
        mainHandler.postDelayed({ ensureCameraStarted() }, 300L)
    }

    private fun applyDiagnosticsVisibility() {
        if (::diagnosticPanel.isInitialized) {
            diagnosticPanel.visibility = if (diagnosticsVisible) View.VISIBLE else View.GONE
        }
    }

    private fun startFpsMeter() {
        fpsRunning = true
        for (i in 0..2) {
            frameCounters[i] = 0
            lastFrameCounters[i] = 0
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
            val focal = if (pid != null) formatFocal(pid) else "--"
            val af = if (pid != null && afRequiredById[pid] == false) "FIXED" else afShortName(afStates[i])
            val fpsText = if (fpsOverlayEnabled) {
                if (fps != null) "${"%.1f".format(Locale.US, fps[i])} fps" else "-- fps"
            } else ""
            val second = listOf("${focal}mm", af, fpsText).filter { it.isNotBlank() }.joinToString(" · ")
            lensLabels[i].text = "${lensNames[i]}\n$second"
        }
    }

    private fun compactCaptureInfo(map: Map<String, Size>): String {
        if (physicalIds.size != 3) return "MAX JPEG × 3"
        return physicalIds.mapIndexed { i, id -> "${lensNames[i]} ${sizeText(map[id])}" }.joinToString("  •  ")
    }

    private fun jpegOrientation(): Int {
        val logicalId = logicalRearId ?: return 0
        val sensor = cameraManager.getCameraCharacteristics(logicalId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        @Suppress("DEPRECATION")
        val device = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensor - device + 360) % 360
    }

    private fun closeSessionOnly() {
        try { captureSession?.stopRepeating() } catch (_: Throwable) {}
        try { captureSession?.close() } catch (_: Throwable) {}
        captureSession = null
        previewRunning = false
        stopFpsMeter()
    }

    private fun closeImageReaders() {
        imageReaders.forEach { try { it.close() } catch (_: Throwable) {} }
        imageReaders = emptyList()
    }

    private fun closeCamera(reason: String?) {
        pendingAutoStart = false
        previewRunning = false
        captureInProgress = false
        waitingFor3A = false
        integratedCaptureSession = false
        legacyCaptureFallback = false
        mainHandler.removeCallbacks(threeATimeoutRunnable)
        mainHandler.removeCallbacks(jpegTimeoutRunnable)
        closeSessionOnly()
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

    private fun setLinkBadge(text: String) {
        runOnUiThread { if (::linkBadge.isInitialized) linkBadge.text = text }
    }

    private fun formatFocal(id: String): String = "%.2f".format(Locale.US, focalById[id] ?: 0f)
    private fun sizeText(size: Size?): String = size?.let { "${it.width}x${it.height}" } ?: "?"

    private fun facingName(value: Int?): String = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun afModeName(value: Int): String = when (value) {
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONT_PICTURE"
        CaptureRequest.CONTROL_AF_MODE_AUTO -> "AUTO"
        CaptureRequest.CONTROL_AF_MODE_OFF -> "OFF"
        else -> value.toString()
    }

    private fun afStateName(value: Int): String = when (value) {
        CaptureResult.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "PASSIVE_SCAN"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED"
        CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "ACTIVE_SCAN"
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUSED_LOCKED"
        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT_FOCUSED"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "PASSIVE_UNFOCUSED"
        else -> "?"
    }

    private fun afShortName(value: Int): String = when (value) {
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "AF✓"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN,
        CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "AF…"
        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "AF×"
        else -> "AF"
    }

    private fun aeStateName(value: Int): String = when (value) {
        CaptureResult.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
        CaptureResult.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
        CaptureResult.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
        CaptureResult.CONTROL_AE_STATE_LOCKED -> "LOCKED"
        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
        CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
        else -> "?"
    }

    private fun awbStateName(value: Int): String = when (value) {
        CaptureResult.CONTROL_AWB_STATE_INACTIVE -> "INACTIVE"
        CaptureResult.CONTROL_AWB_STATE_SEARCHING -> "SEARCHING"
        CaptureResult.CONTROL_AWB_STATE_CONVERGED -> "CONVERGED"
        CaptureResult.CONTROL_AWB_STATE_LOCKED -> "LOCKED"
        else -> "?"
    }
}
