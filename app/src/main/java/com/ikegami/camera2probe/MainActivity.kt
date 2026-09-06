package com.ikegami.camera2probe

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
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
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Camera2Probe"
        private const val CAMERA_PERMISSION_REQUEST = 10
        private const val TEST_WIDTH = 640
        private const val TEST_HEIGHT = 480
    }

    private lateinit var cameraManager: CameraManager
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var previewLabels: TextView
    private lateinit var textureViews: List<TextureView>

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var activeSurfaces: List<Surface> = emptyList()

    private var logicalRearId: String? = null
    private var selectedPhysicalIds: List<String> = emptyList()
    private var openingCamera = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraManager = getSystemService(CameraManager::class.java)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        previewLabels = findViewById(R.id.previewLabels)
        textureViews = listOf(
            findViewById(R.id.preview1),
            findViewById(R.id.preview2),
            findViewById(R.id.preview3)
        )

        findViewById<Button>(R.id.scanButton).setOnClickListener { scanCameras() }
        findViewById<Button>(R.id.tripleButton).setOnClickListener { prepareTripleTest() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { closeCamera("Stopped by user") }

        startCameraThread()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanCameras()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            appendLog("Camera permission: GRANTED")
            scanCameras()
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
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
        logText.text = ""
        appendLog("=== CAMERA2 CAPABILITY SCAN ===")
        appendLog("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLog("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        try {
            val ids = cameraManager.cameraIdList
            appendLog("Public camera IDs: ${ids.joinToString(prefix = "[", postfix = "]")}")

            for (id in ids) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = facingName(chars.get(CameraCharacteristics.LENS_FACING))
                val hw = hardwareLevelName(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val logical = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                val physicalIds = chars.physicalCameraIds.toList()
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

                if (physicalIds.isNotEmpty()) {
                    for (physicalId in physicalIds) {
                        appendPhysicalInfo(physicalId)
                    }
                }

                if (
                    logicalRearId == null &&
                    facing == "BACK" &&
                    logical &&
                    physicalIds.size >= 3
                ) {
                    logicalRearId = id
                    selectedPhysicalIds = sortPhysicalIdsByFocalLength(physicalIds).take(3)
                }
            }

            appendConcurrentInfo()
            appendLog("")
            appendLog("=== TRIPLE PHYSICAL TEST CANDIDATE ===")

            if (logicalRearId != null && selectedPhysicalIds.size == 3) {
                appendLog("Logical rear ID: $logicalRearId")
                appendLog("Selected physical IDs: $selectedPhysicalIds")
                previewLabels.text = selectedPhysicalIds.joinToString("  |  ") { "ID $it" }
                setStatus("3 physical cameras exposed. Ready for 640x480 × 3 test.")
            } else {
                appendLog("No public logical rear camera exposing >= 3 physical IDs.")
                previewLabels.text = "No 3-physical-camera logical device found"
                setStatus("3-camera public Camera2 path not found")
            }
        } catch (e: Exception) {
            appendLog("SCAN ERROR: ${e.javaClass.simpleName}: ${e.message}")
            setStatus("Scan failed")
            Log.e(TAG, "Camera scan failed", e)
        }
    }

    private fun appendPhysicalInfo(id: String) {
        try {
            val chars = cameraManager.getCameraCharacteristics(id)
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.joinToString(prefix = "[", postfix = "]") { "${it}mm" }
                ?: "unknown"
            val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            appendLog("    physical[$id] focal=$focal sensor=${sensor ?: "unknown"}")
        } catch (e: Exception) {
            appendLog("    physical[$id] characteristics unavailable: ${e.javaClass.simpleName}")
        }
    }

    private fun sortPhysicalIdsByFocalLength(ids: List<String>): List<String> {
        return ids.sortedBy { id ->
            try {
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.minOrNull() ?: Float.MAX_VALUE
            } catch (_: Exception) {
                Float.MAX_VALUE
            }
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
            } catch (e: Exception) {
                appendLog("  ERROR: ${e.javaClass.simpleName}: ${e.message}")
            }
        } else {
            appendLog("  unavailable below API 30")
        }
    }

    private fun prepareTripleTest() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            return
        }

        val logicalId = logicalRearId
        if (logicalId == null || selectedPhysicalIds.size < 3) {
            appendLog("TRIPLE TEST: no suitable logical camera. Run SCAN first.")
            setStatus("Triple test unavailable")
            return
        }

        closeCamera(null)
        appendLog("")
        appendLog("=== START TRIPLE PREVIEW TEST ===")
        appendLog("logical=$logicalId")
        appendLog("physical=${selectedPhysicalIds.take(3)}")
        appendLog("requested=${TEST_WIDTH}x${TEST_HEIGHT} each")
        setStatus("Waiting for 3 preview surfaces...")
        waitForAllTextureViews()
    }

    private fun waitForAllTextureViews() {
        if (textureViews.all { it.isAvailable }) {
            openTripleCamera()
            return
        }

        textureViews.forEach { view ->
            if (!view.isAvailable) {
                view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        if (textureViews.all { it.isAvailable }) {
                            textureViews.forEach { it.surfaceTextureListener = null }
                            openTripleCamera()
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        }
    }

    private fun openTripleCamera() {
        if (openingCamera || cameraDevice != null) return
        val logicalId = logicalRearId ?: return
        val physicalIds = selectedPhysicalIds.take(3)
        if (physicalIds.size != 3) return

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val surfaces = try {
            textureViews.map { textureView ->
                val surfaceTexture = textureView.surfaceTexture
                    ?: throw IllegalStateException("TextureView has no SurfaceTexture")
                surfaceTexture.setDefaultBufferSize(TEST_WIDTH, TEST_HEIGHT)
                Surface(surfaceTexture)
            }
        } catch (e: Exception) {
            appendLog("SURFACE ERROR: ${e.message}")
            setStatus("Surface setup failed")
            return
        }

        activeSurfaces = surfaces
        openingCamera = true
        setStatus("Opening logical camera $logicalId...")

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
                        setStatus("Camera disconnected")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        appendLog("CameraDevice ERROR: ${cameraErrorName(error)} ($error)")
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                        openingCamera = false
                        setStatus("Camera open failed: ${cameraErrorName(error)}")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            openingCamera = false
            activeSurfaces.forEach { it.release() }
            activeSurfaces = emptyList()
            appendLog("OPEN ERROR: ${e.javaClass.simpleName}: ${e.message}")
            setStatus("Open failed")
            Log.e(TAG, "openCamera failed", e)
        }
    }

    private fun configureTripleSession(
        camera: CameraDevice,
        physicalIds: List<String>,
        surfaces: List<Surface>
    ) {
        try {
            val outputs = surfaces.zip(physicalIds).map { (surface, physicalId) ->
                OutputConfiguration(surface).apply {
                    setPhysicalCameraId(physicalId)
                }
            }

            outputs.forEachIndexed { index, output ->
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
                        appendLog("TRIPLE SESSION: CONFIGURE FAILED")
                        setStatus("3-camera session rejected by HAL")
                        session.close()
                    }
                }
            )

            appendLog("Submitting SessionConfiguration with 3 physical outputs...")
            camera.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            appendLog("SESSION ERROR: ${e.javaClass.simpleName}: ${e.message}")
            setStatus("3-camera session creation failed")
            Log.e(TAG, "Triple session configuration failed", e)
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
                                appendLog("TRIPLE SESSION: SUCCESS")
                                appendLog("First capture frame: $frameNumber")
                                setStatus("SUCCESS: 3 physical camera preview session is running")
                            }
                        }
                    }
                },
                cameraHandler
            )
            appendLog("Repeating request submitted to all 3 surfaces")
        } catch (e: Exception) {
            appendLog("REQUEST ERROR: ${e.javaClass.simpleName}: ${e.message}")
            setStatus("Repeating request failed")
            Log.e(TAG, "Repeating request failed", e)
        }
    }

    private fun closeCamera(reason: String?) {
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
        }
        captureSession?.close()
        captureSession = null

        cameraDevice?.close()
        cameraDevice = null
        openingCamera = false

        activeSurfaces.forEach { surface ->
            try {
                surface.release()
            } catch (_: Exception) {
            }
        }
        activeSurfaces = emptyList()

        if (reason != null) {
            appendLog(reason)
            setStatus(reason)
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    private fun appendLog(text: String) {
        runOnUiThread {
            logText.append(text)
            logText.append("\n")
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
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        super.onDestroy()
    }
}
