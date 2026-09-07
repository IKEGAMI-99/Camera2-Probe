package com.ikegami.camera2probe

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs

class MainActivity : Activity() {

    companion object {
        private const val TAG = "Camera2Probe"
        private const val CAMERA_PERMISSION_REQUEST = 10
        private const val APP_VERSION = "0.2.0"
    }

    private lateinit var cameraManager: CameraManager
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var resolutionText: TextView
    private lateinit var maxButton: Button
    private lateinit var textureViews: List<TextureView>
    private lateinit var cameraLabels: List<TextView>

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var activeSurfaces: List<Surface> = emptyList()
    private var openingCamera = false
    private var waitingForSurfaces = false

    private var logicalRearId: String? = null
    private var selectedPhysicalIds: List<String> = emptyList()
    private var selectedFocals: List<Float?> = emptyList()
    private var commonOutputSizes: List<Size> = emptyList()
    private var maxCommonSize: Size? = null

    private var selectedSize = Size(640, 480)
    private var selectedModeName = "VGA"

    private val frameCounts = LongArray(3)
    private val lastFrameCounts = LongArray(3)
    private val fpsValues = FloatArray(3)
    private var fpsMonitoring = false
    private var lastFpsSampleMs = 0L

    private val fpsRunnable = object : Runnable {
        override fun run() {
            if (!fpsMonitoring) return

            val now = SystemClock.elapsedRealtime()
            val elapsedMs = (now - lastFpsSampleMs).coerceAtLeast(1L)
            for (i in 0..2) {
                val current = frameCounts[i]
                val delta = current - lastFrameCounts[i]
                fpsValues[i] = delta * 1000f / elapsedMs.toFloat()
                lastFrameCounts[i] = current
            }
            lastFpsSampleMs = now
            updateCameraLabels()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_main)

            statusText = findViewById(R.id.statusText)
            logText = findViewById(R.id.logText)
            resolutionText = findViewById(R.id.resolutionText)
            maxButton = findViewById(R.id.maxButton)

            textureViews = listOf(
                findViewById(R.id.preview1),
                findViewById(R.id.preview2),
                findViewById(R.id.preview3)
            )
            cameraLabels = listOf(
                findViewById(R.id.label1),
                findViewById(R.id.label2),
                findViewById(R.id.label3)
            )

            cameraManager = getSystemService(CameraManager::class.java)
            startCameraThread()
            installTextureListeners()

            findViewById<Button>(R.id.scanButton).setOnClickListener { requestPermissionOrScan() }
            findViewById<Button>(R.id.tripleButton).setOnClickListener { prepareTripleTest() }
            findViewById<Button>(R.id.stopButton).setOnClickListener { closeCamera("Stopped by user") }

            findViewById<Button>(R.id.vgaButton).setOnClickListener {
                selectPreset(640, 480, "VGA")
            }
            findViewById<Button>(R.id.p720Button).setOnClickListener {
                selectPreset(1280, 720, "720P")
            }
            findViewById<Button>(R.id.p1080Button).setOnClickListener {
                selectPreset(1920, 1080, "1080P")
            }
            maxButton.setOnClickListener { selectMaximumCommonResolution() }

            statusText.text = "Ready. Tap SCAN to inspect Camera2."
            logText.text = "Camera2 Probe v$APP_VERSION\n"
            updateResolutionText()
            updateCameraLabels()
            appendLog("App started successfully")
            appendLog("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLog("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLog("FPS meter counts actual TextureView buffer updates per camera.")
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal startup error", t)
            try {
                setContentView(android.R.layout.simple_list_item_1)
                findViewById<TextView>(android.R.id.text1)?.text =
                    "Camera2 Probe startup error\n${t.javaClass.simpleName}: ${t.message}"
            } catch (_: Throwable) {
            }
        }
    }

    private fun installTextureListeners() {
        textureViews.forEachIndexed { index, view ->
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    if (waitingForSurfaces && textureViews.all { it.isAvailable }) {
                        waitingForSurfaces = false
                        openTripleCamera()
                    }
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    if (cameraDevice != null || openingCamera) {
                        closeCamera("Preview surface destroyed")
                    }
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    frameCounts[index]++
                }
            }
        }
    }

    private fun requestPermissionOrScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanCameras()
        } else {
            appendLog("Requesting CAMERA permission...")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST) return

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            appendLog("Camera permission: GRANTED")
            scanCameras()
        } else {
            setStatus("Camera permission denied")
            appendLog("Camera permission: DENIED")
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
        selectedPhysicalIds = emptyList()
        selectedFocals = emptyList()
        commonOutputSizes = emptyList()
        maxCommonSize = null
        maxButton.text = "MAX"
        logText.text = "Camera2 Probe v$APP_VERSION\n"
        appendLog("=== CAMERA2 CAPABILITY SCAN ===")

        val ids = try {
            cameraManager.cameraIdList
        } catch (t: Throwable) {
            appendLog("cameraIdList ERROR: ${t.javaClass.simpleName}: ${t.message}")
            setStatus("Camera ID enumeration failed")
            return
        }

        appendLog("Public camera IDs: ${ids.joinToString(prefix = "[", postfix = "]")}")

        for (id in ids) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facingValue = chars.get(CameraCharacteristics.LENS_FACING)
                val facing = facingName(facingValue)
                val hw = hardwareLevelName(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val logical = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                val physicalIds = try {
                    chars.physicalCameraIds.toList()
                } catch (t: Throwable) {
                    appendLog("Camera $id physicalCameraIds ERROR: ${t.javaClass.simpleName}")
                    emptyList()
                }
                val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.joinToString(prefix = "[", postfix = "]") { "${it}mm" }
                    ?: "unknown"

                appendLog("")
                appendLog("Camera $id")
                appendLog("  facing=$facing")
                appendLog("  hardware=$hw")
                appendLog("  focalLengths=$focal")
                appendLog("  logicalMultiCamera=$logical")
                appendLog("  physicalIds=${physicalIds.ifEmpty { listOf("<none>") }}")

                for (physicalId in physicalIds) {
                    appendPhysicalInfo(physicalId)
                }

                if (
                    logicalRearId == null &&
                    facingValue == CameraCharacteristics.LENS_FACING_BACK &&
                    logical &&
                    physicalIds.size >= 3
                ) {
                    logicalRearId = id
                    selectedPhysicalIds = sortPhysicalIdsByFocalLength(physicalIds).take(3)
                }
            } catch (t: Throwable) {
                appendLog("")
                appendLog("Camera $id ERROR: ${t.javaClass.simpleName}: ${t.message}")
                Log.e(TAG, "Characteristics failed for camera $id", t)
            }
        }

        appendConcurrentInfo()
        appendLog("")
        appendLog("=== TRIPLE PHYSICAL TEST CANDIDATE ===")

        if (logicalRearId != null && selectedPhysicalIds.size == 3) {
            selectedFocals = selectedPhysicalIds.map { getPrimaryFocalLength(it) }
            appendLog("Logical rear ID: $logicalRearId")
            appendLog("Selected physical IDs: $selectedPhysicalIds")
            appendLog("Lens order: ULTRA / MAIN / TELE")
            calculateCommonOutputSizes()
            updateCameraLabels()

            val resolved = resolvePresetSize(640, 480)
            selectedSize = resolved
            selectedModeName = "VGA"
            updateResolutionText()
            setStatus("3 physical cameras found. Choose resolution and START 3-CAM.")
        } else {
            updateCameraLabels()
            appendLog("No public logical rear camera exposing >= 3 physical IDs.")
            setStatus("Scan complete. 3-camera logical path not found.")
        }
    }

    private fun appendPhysicalInfo(id: String) {
        try {
            val chars = cameraManager.getCameraCharacteristics(id)
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.joinToString(prefix = "[", postfix = "]") { "${it}mm" }
                ?: "unknown"
            val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val previewSizes = getSurfaceTextureSizes(id)
            val largest = previewSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            appendLog(
                "    physical[$id] focal=$focal sensor=${sensor ?: "unknown"}" +
                    if (largest != null) " previewMax=${formatSize(largest)}" else ""
            )
        } catch (t: Throwable) {
            appendLog("    physical[$id] characteristics unavailable: ${t.javaClass.simpleName}")
        }
    }

    private fun sortPhysicalIdsByFocalLength(ids: List<String>): List<String> {
        return ids.sortedBy { id -> getPrimaryFocalLength(id) ?: Float.MAX_VALUE }
    }

    private fun getPrimaryFocalLength(id: String): Float? {
        return try {
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    private fun getSurfaceTextureSizes(id: String): List<Size> {
        return try {
            val chars = cameraManager.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun calculateCommonOutputSizes() {
        var common: Set<Pair<Int, Int>>? = null

        selectedPhysicalIds.forEach { id ->
            val sizeSet = getSurfaceTextureSizes(id)
                .map { it.width to it.height }
                .toSet()
            common = if (common == null) sizeSet else common!!.intersect(sizeSet)
        }

        commonOutputSizes = (common ?: emptySet())
            .map { Size(it.first, it.second) }
            .sortedWith(
                compareByDescending<Size> { it.width.toLong() * it.height.toLong() }
                    .thenByDescending { it.width }
            )

        maxCommonSize = commonOutputSizes.firstOrNull()

        appendLog("")
        appendLog("Common SurfaceTexture output sizes: ${commonOutputSizes.size}")
        if (commonOutputSizes.isNotEmpty()) {
            appendLog(
                "  top=${commonOutputSizes.take(12).joinToString { formatSize(it) }}"
            )
        }

        val max = maxCommonSize
        if (max != null) {
            maxButton.text = "MAX\n${max.width}×${max.height}"
            appendLog("MAX common output = ${formatSize(max)}")
            if (max.width.toLong() * max.height.toLong() > 3840L * 2160L) {
                appendLog("WARNING: MAX is above 4K. HAL may reject it or allocate large buffers.")
            }
        } else {
            appendLog("Could not calculate a common physical-camera SurfaceTexture size.")
        }
    }

    private fun appendConcurrentInfo() {
        appendLog("")
        appendLog("Concurrent camera sets:")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val sets = cameraManager.concurrentCameraIds
                if (sets.isEmpty()) {
                    appendLog("  <none>")
                } else {
                    sets.forEach { appendLog("  ${it.joinToString(prefix = "[", postfix = "]")}") }
                }
            } catch (t: Throwable) {
                appendLog("  ERROR: ${t.javaClass.simpleName}: ${t.message}")
            }
        } else {
            appendLog("  unavailable below API 30")
        }
    }

    private fun selectPreset(width: Int, height: Int, name: String) {
        val resolved = resolvePresetSize(width, height)
        selectedSize = resolved
        selectedModeName = name
        updateResolutionText()

        if (resolved.width == width && resolved.height == height) {
            appendLog("Selected $name: ${formatSize(resolved)}")
        } else {
            appendLog("$name exact size not common; using nearest common ${formatSize(resolved)}")
        }

        if (cameraDevice != null) {
            setStatus("Resolution changed to ${formatSize(resolved)}. Tap START 3-CAM to restart.")
        }
    }

    private fun selectMaximumCommonResolution() {
        val max = maxCommonSize
        if (max == null) {
            appendLog("MAX unavailable. Run SCAN first.")
            setStatus("Run SCAN before selecting MAX")
            return
        }

        selectedSize = max
        selectedModeName = "MAX"
        updateResolutionText()
        appendLog("Selected MAX common resolution: ${formatSize(max)}")
        if (max.width.toLong() * max.height.toLong() > 3840L * 2160L) {
            appendLog("MAX warning: this is above 4K and may be rejected by the HAL.")
        }
        if (cameraDevice != null) {
            setStatus("MAX selected. Tap START 3-CAM to restart.")
        }
    }

    private fun resolvePresetSize(targetWidth: Int, targetHeight: Int): Size {
        if (commonOutputSizes.isEmpty()) return Size(targetWidth, targetHeight)

        commonOutputSizes.firstOrNull {
            it.width == targetWidth && it.height == targetHeight
        }?.let { return it }

        val targetAspect = targetWidth.toDouble() / targetHeight.toDouble()
        val targetArea = targetWidth.toLong() * targetHeight.toLong()
        val sameAspect = commonOutputSizes.filter {
            abs(it.width.toDouble() / it.height.toDouble() - targetAspect) < 0.03
        }
        val pool = sameAspect.ifEmpty { commonOutputSizes }

        return pool.minByOrNull {
            abs(it.width.toLong() * it.height.toLong() - targetArea)
        } ?: Size(targetWidth, targetHeight)
    }

    private fun prepareTripleTest() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            appendLog("Triple test needs CAMERA permission")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            return
        }

        val logicalId = logicalRearId
        if (logicalId == null || selectedPhysicalIds.size < 3) {
            appendLog("TRIPLE TEST: no suitable logical camera. Run SCAN first.")
            setStatus("Triple test unavailable")
            return
        }

        closeCamera(null)
        resetFpsCounters()
        appendLog("")
        appendLog("=== START TRIPLE PREVIEW BENCHMARK ===")
        appendLog("logical=$logicalId")
        appendLog("physical=${selectedPhysicalIds.take(3)}")
        appendLog("mode=$selectedModeName")
        appendLog("requested=${formatSize(selectedSize)} each")
        setStatus("Waiting for 3 preview surfaces...")

        if (textureViews.all { it.isAvailable }) {
            openTripleCamera()
        } else {
            waitingForSurfaces = true
        }
    }

    private fun openTripleCamera() {
        if (openingCamera || cameraDevice != null) return
        val logicalId = logicalRearId ?: return
        val physicalIds = selectedPhysicalIds.take(3)
        if (physicalIds.size != 3) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        val surfaces = try {
            textureViews.map { textureView ->
                val surfaceTexture = textureView.surfaceTexture
                    ?: throw IllegalStateException("TextureView has no SurfaceTexture")
                surfaceTexture.setDefaultBufferSize(selectedSize.width, selectedSize.height)
                Surface(surfaceTexture)
            }
        } catch (t: Throwable) {
            appendLog("SURFACE ERROR: ${t.javaClass.simpleName}: ${t.message}")
            setStatus("Surface setup failed")
            return
        }

        activeSurfaces = surfaces
        openingCamera = true
        setStatus("Opening logical camera $logicalId @ ${formatSize(selectedSize)}...")

        try {
            cameraManager.openCamera(
                logicalId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        openingCamera = false
                        cameraDevice = camera
                        appendLog("CameraDevice OPENED: ${camera.id}")
                        configureTripleSession(camera, physicalIds, surfaces)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        appendLog("CameraDevice DISCONNECTED")
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                        openingCamera = false
                        stopFpsMonitor()
                        setStatus("Camera disconnected")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        appendLog("CameraDevice ERROR: ${cameraErrorName(error)} ($error)")
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                        openingCamera = false
                        stopFpsMonitor()
                        setStatus("Camera open failed: ${cameraErrorName(error)}")
                    }
                },
                cameraHandler
            )
        } catch (t: Throwable) {
            openingCamera = false
            activeSurfaces.forEach { try { it.release() } catch (_: Throwable) {} }
            activeSurfaces = emptyList()
            appendLog("OPEN ERROR: ${t.javaClass.simpleName}: ${t.message}")
            setStatus("Open failed")
            Log.e(TAG, "openCamera failed", t)
        }
    }

    private fun configureTripleSession(
        camera: CameraDevice,
        physicalIds: List<String>,
        surfaces: List<Surface>
    ) {
        try {
            val outputs = surfaces.zip(physicalIds).map { (surface, physicalId) ->
                OutputConfiguration(surface).apply { setPhysicalCameraId(physicalId) }
            }

            outputs.forEachIndexed { index, _ ->
                appendLog("output[$index] -> physical=${physicalIds[index]}")
            }

            val executor = Executor { command -> cameraHandler?.post(command) }
            val sessionConfig = SessionConfiguration(
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
                        startRepeatingTripleRequest(camera, session, surfaces)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        appendLog("TRIPLE SESSION: CONFIGURE FAILED @ ${formatSize(selectedSize)}")
                        stopFpsMonitor()
                        setStatus("HAL rejected 3-camera ${formatSize(selectedSize)} session")
                        session.close()
                    }
                }
            )

            appendLog("Submitting 3 physical outputs @ ${formatSize(selectedSize)}...")
            camera.createCaptureSession(sessionConfig)
        } catch (t: Throwable) {
            appendLog("SESSION ERROR: ${t.javaClass.simpleName}: ${t.message}")
            stopFpsMonitor()
            setStatus("3-camera session creation failed")
            Log.e(TAG, "Triple session configuration failed", t)
        }
    }

    private fun startRepeatingTripleRequest(
        camera: CameraDevice,
        session: CameraCaptureSession,
        surfaces: List<Surface>
    ) {
        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            surfaces.forEach { requestBuilder.addTarget(it) }
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

            session.setRepeatingRequest(
                requestBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    private var firstFrameLogged = false

                    override fun onCaptureStarted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        timestamp: Long,
                        frameNumber: Long
                    ) {
                        if (!firstFrameLogged) {
                            firstFrameLogged = true
                            runOnUiThread {
                                appendLog("TRIPLE SESSION: SUCCESS @ ${formatSize(selectedSize)}")
                                appendLog("First capture frame: $frameNumber")
                                appendLog("Per-camera FPS meter started")
                                setStatus(
                                    "SUCCESS: 3-CAM ${formatSize(selectedSize)} running; measuring FPS"
                                )
                            }
                        }
                    }
                },
                cameraHandler
            )

            appendLog("Repeating request submitted to all 3 surfaces")
            runOnUiThread { startFpsMonitor() }
        } catch (t: Throwable) {
            appendLog("REQUEST ERROR: ${t.javaClass.simpleName}: ${t.message}")
            stopFpsMonitor()
            setStatus("Repeating request failed")
            Log.e(TAG, "Repeating request failed", t)
        }
    }

    private fun resetFpsCounters() {
        for (i in 0..2) {
            frameCounts[i] = 0L
            lastFrameCounts[i] = 0L
            fpsValues[i] = 0f
        }
        updateCameraLabels()
    }

    private fun startFpsMonitor() {
        resetFpsCounters()
        fpsMonitoring = true
        lastFpsSampleMs = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(fpsRunnable)
        mainHandler.postDelayed(fpsRunnable, 1000L)
    }

    private fun stopFpsMonitor() {
        fpsMonitoring = false
        mainHandler.removeCallbacks(fpsRunnable)
        for (i in 0..2) fpsValues[i] = 0f
        if (::cameraLabels.isInitialized) {
            runOnUiThread { updateCameraLabels() }
        }
    }

    private fun updateCameraLabels() {
        if (!::cameraLabels.isInitialized) return
        val names = listOf("ULTRA", "MAIN", "TELE")

        cameraLabels.forEachIndexed { index, label ->
            val id = selectedPhysicalIds.getOrNull(index)
            val focal = selectedFocals.getOrNull(index)
            val fpsText = if (fpsMonitoring) {
                String.format(Locale.US, "%.1f fps", fpsValues[index])
            } else {
                "-- fps"
            }

            label.text = if (id != null) {
                val focalText = focal?.let { String.format(Locale.US, "%.2fmm", it) } ?: "?mm"
                "${names[index]}\nID $id • $focalText\n$fpsText"
            } else {
                "${names[index]}\nID --\n$fpsText"
            }
        }
    }

    private fun updateResolutionText() {
        if (!::resolutionText.isInitialized) return
        resolutionText.text = "Selected: $selectedModeName ${formatSize(selectedSize)} × 3"
    }

    private fun closeCamera(reason: String?) {
        waitingForSurfaces = false
        stopFpsMonitor()

        try { captureSession?.stopRepeating() } catch (_: Throwable) {}
        try { captureSession?.close() } catch (_: Throwable) {}
        captureSession = null

        try { cameraDevice?.close() } catch (_: Throwable) {}
        cameraDevice = null
        openingCamera = false

        activeSurfaces.forEach { surface ->
            try { surface.release() } catch (_: Throwable) {}
        }
        activeSurfaces = emptyList()

        if (reason != null) {
            appendLog(reason)
            setStatus(reason)
        }
    }

    private fun formatSize(size: Size): String = "${size.width}x${size.height}"

    private fun setStatus(text: String) {
        if (!::statusText.isInitialized) return
        runOnUiThread { statusText.text = text }
    }

    private fun appendLog(text: String) {
        if (::logText.isInitialized) {
            runOnUiThread {
                logText.append(text)
                logText.append("\n")
            }
        }
        Log.d(TAG, text)
    }

    private fun facingName(value: Int?): String = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun hardwareLevelName(value: Int?): String = when (value) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun cameraErrorName(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
        else -> "UNKNOWN"
    }

    override fun onPause() {
        closeCamera(null)
        super.onPause()
    }

    override fun onDestroy() {
        closeCamera(null)
        mainHandler.removeCallbacksAndMessages(null)
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        super.onDestroy()
    }
}
