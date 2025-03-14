package com.example.camera_test

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
class Camera2Handler(
    private val activity: Activity,
    messenger: BinaryMessenger,
    private val textureRegistry: TextureRegistry
) : MethodChannel.MethodCallHandler {

    private val TAG = "Camera2Handler"
    private val methodChannel: MethodChannel =
        MethodChannel(messenger, "com.example.camera_test/camera")

    // Camera components
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private lateinit var cameraManager: CameraManager
    private val cameraOpenCloseLock = Semaphore(1)

    // Background thread and handler
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Flutter texture entry
    private var flutterTexture: TextureRegistry.SurfaceTextureEntry? = null
    private var previewSurface: Surface? = null

    // Camera characteristics and settings
    private var currentCameraId: String? = null
    private var previewSize = Size(0, 0)
    private var captureSize = Size(0, 0)  // High resolution size for photos
    private var sensorOrientation = 0
    private var flashSupported = false
    private var flashMode = CaptureRequest.CONTROL_AE_MODE_ON
    private var torchMode = false
    private var supportedFps = Range(30, 30)

    // Zoom parameters
    private var maxZoom = 1.0f
    private var currentZoom = 1.0f
    private var cropRegion: Rect? = null

    // Camera details storage
    private val cameraDetailsMap = mutableMapOf<String, CameraDetails>()

    init {
        methodChannel.setMethodCallHandler(this)
        cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    data class CameraDetails(
        val cameraId: String,
        val lensFacing: Int,
        val focalLength: Float,
        val fieldOfView: Float,
        val isWideAngle: Boolean,
        val maxZoom: Float,
        val hasFlash: Boolean,
        val sensorOrientation: Int,
        val supportedSizes: List<Size>
    )

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getAvailableCameras" -> getAvailableCameras(result)
            "initializeCamera" -> {
                val cameraId = call.argument<String>("cameraId")
                initializeCamera(cameraId, result)
            }

            "takePicture" -> takePicture(result)
            "setZoom" -> {
                val zoom = call.argument<Double>("zoom")
                zoom?.let { setZoom(it.toFloat(), result) } ?: result.error(
                    "INVALID_ARGUMENT",
                    "Zoom value is required",
                    null
                )
            }

            "getZoomRange" -> getZoomRange(result)
            "setFlashMode" -> {
                val mode = call.argument<Int>("mode")
                mode?.let { setFlashMode(it, result) } ?: result.error(
                    "INVALID_ARGUMENT",
                    "Flash mode is required",
                    null
                )
            }

            "setFocusPoint" -> {
                val x = call.argument<Double>("x")
                val y = call.argument<Double>("y")
                if (x != null && y != null) {
                    setFocusPoint(x.toFloat(), y.toFloat(), result)
                } else {
                    result.error("INVALID_ARGUMENT", "Focus coordinates are required", null)
                }
            }

            "toggleTorch" -> {
                val enable = call.argument<Boolean>("enable")
                enable?.let { toggleTorch(it, result) } ?: result.error(
                    "INVALID_ARGUMENT",
                    "Torch state is required",
                    null
                )
            }

            "disposeCamera" -> {
                disposeCamera()
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    private fun getAvailableCameras(result: MethodChannel.Result) {
        try {
            val cameraIds = cameraManager.cameraIdList
            val cameras = mutableListOf<Map<String, Any>>()

            // Clear the previous camera details
            cameraDetailsMap.clear()

            // Track the widest angle camera and default back camera
            var defaultBackCamera: String? = null
            var widestAngleCamera: String? = null
            var widestAngleFOV = 0f

            for (cameraId in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

                // Get focal length and field of view information
                val focalLengths =
                    characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)

                // Get available output sizes
                val streamConfigurationMap =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val outputSizes =
                    streamConfigurationMap?.getOutputSizes(SurfaceTexture::class.java)?.toList()
                        ?: emptyList()

                // Get max zoom
                val maxZoomCharacteristic =
                    characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                        ?: 1.0f

                // Has flash unit
                val hasFlash =
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

                // Sensor orientation
                val sensorOrientation =
                    characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

                // Default values
                var focalLength = 0f
                var calculatedFOV = 0f
                var isWideAngle = false

                // Calculate field of view and determine if wide-angle
                if (focalLengths != null && focalLengths.isNotEmpty()) {
                    focalLength = focalLengths.minOrNull() ?: 0f

                    if (focalLength > 0) {
                        // Approximate FOV calculation
                        calculatedFOV = 1 / focalLength * 100

                        // Wide-angle detection (focal length < 5mm is typically wide-angle)
                        isWideAngle = focalLength < 5.0f

                        // Track widest angle back camera
                        if (lensFacing == CameraCharacteristics.LENS_FACING_BACK && calculatedFOV > widestAngleFOV) {
                            widestAngleFOV = calculatedFOV
                            widestAngleCamera = cameraId
                        }

                        // Track potential default back camera
                        if (lensFacing == CameraCharacteristics.LENS_FACING_BACK && !isWideAngle && defaultBackCamera == null) {
                            defaultBackCamera = cameraId
                        }
                    }
                }

                // Store camera details for later use
                cameraDetailsMap[cameraId] = CameraDetails(
                    cameraId = cameraId,
                    lensFacing = lensFacing ?: -1,
                    focalLength = focalLength,
                    fieldOfView = calculatedFOV,
                    isWideAngle = isWideAngle,
                    maxZoom = maxZoomCharacteristic,
                    hasFlash = hasFlash,
                    sensorOrientation = sensorOrientation,
                    supportedSizes = outputSizes
                )
            }

            // If we couldn't identify a default camera, use the first back camera
            if (defaultBackCamera == null) {
                for (cameraId in cameraIds) {
                    val details = cameraDetailsMap[cameraId]
                    if (details?.lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                        defaultBackCamera = cameraId
                        break
                    }
                }
            }

            // Create the camera list to return to Flutter
            for (cameraId in cameraIds) {
                val details = cameraDetailsMap[cameraId] ?: continue

                // Determine lens type based on facing
                var lensType = when (details.lensFacing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    CameraCharacteristics.LENS_FACING_BACK -> "Back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                    else -> "Unknown"
                }

                // Mark wide-angle cameras
                if (details.isWideAngle && details.lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    lensType = "Wide"
                }

                // Special case: ensure the widest camera is definitely marked as Wide
                if (cameraId == widestAngleCamera && lensType == "Back") {
                    lensType = "Wide"
                }

                // Check if this is the default back camera
                val isDefault = cameraId == defaultBackCamera

                // Log camera details
                Log.d(
                    TAG,
                    "Camera: $cameraId, Type: $lensType, Focal Length: ${details.focalLength}mm, " +
                            "FOV: ${details.fieldOfView}, Wide: ${details.isWideAngle}, Default: $isDefault"
                )

                // Add to list
                val cameraMap = mapOf(
                    "id" to cameraId,
                    "name" to lensType,
                    "isDefault" to isDefault,
                    "isWideAngle" to (details.isWideAngle || cameraId == widestAngleCamera),
                    "focalLength" to details.focalLength,
                    "fieldOfView" to details.fieldOfView
                )
                cameras.add(cameraMap)
            }

            result.success(cameras)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cameras: ${e.message}", e)
            result.error("CAMERA_ERROR", "Failed to get available cameras", e.message)
        }
    }

    private fun initializeCamera(cameraId: String?, result: MethodChannel.Result) {
        if (cameraId == null) {
            result.error("CAMERA_ERROR", "Camera ID cannot be null", null)
            return
        }

        // Clean up any existing camera
        disposeCamera()

        startBackgroundThread()

        // Get camera details
        val cameraDetails = cameraDetailsMap[cameraId]
        if (cameraDetails == null) {
            result.error("CAMERA_ERROR", "Camera details not found for ID: $cameraId", null)
            return
        }

        // Store the current camera ID
        currentCameraId = cameraId

        // Get Camera Characteristics for more detailed settings
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

        // Set up sensor orientation
        sensorOrientation = cameraDetails.sensorOrientation

        // Set up maximum zoom
        maxZoom = cameraDetails.maxZoom

        // Check if flash is supported
        flashSupported = cameraDetails.hasFlash

        // Setup optimal resolutions for preview and capture
        setupOptimalResolutions(characteristics)

        // Create a new texture entry for Flutter
        flutterTexture = textureRegistry.createSurfaceTexture()
        val surfaceTexture = flutterTexture?.surfaceTexture()

        // Set buffer size for preview
        surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)

        // Create surface for preview
        previewSurface = Surface(surfaceTexture)

        // Create high resolution image reader for taking photos
        imageReader = ImageReader.newInstance(
            captureSize.width, captureSize.height, ImageFormat.JPEG, 2
        ).apply {
            // Log the selected capture size
            Log.d(TAG, "Image capture size: ${captureSize.width}x${captureSize.height}")
        }

        try {
            // Open camera
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    // Camera opened successfully
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    createCameraPreviewSession()

                    // Return camera info to Flutter
                    activity.runOnUiThread {
                        val responseMap = HashMap<String, Any>()
                        responseMap["textureId"] = flutterTexture!!.id()
                        responseMap["width"] = previewSize.width
                        responseMap["height"] = previewSize.height
                        responseMap["aspectRatio"] =
                            previewSize.width.toDouble() / previewSize.height
                        responseMap["captureWidth"] = captureSize.width
                        responseMap["captureHeight"] = captureSize.height
                        responseMap["minZoom"] = 1.0
                        responseMap["maxZoom"] = maxZoom.toDouble()
                        responseMap["hasTorch"] = flashSupported
                        responseMap["fieldOfView"] = cameraDetails.fieldOfView
                        responseMap["focalLength"] = cameraDetails.focalLength
                        responseMap["isWideAngle"] = cameraDetails.isWideAngle

                        result.success(responseMap)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    activity.runOnUiThread {
                        result.error(
                            "CAMERA_ERROR",
                            "Failed to open camera: error code $error",
                            null
                        )
                    }
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception", e)
            result.error("CAMERA_ERROR", "Camera access exception", e.message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize camera", e)
            result.error("CAMERA_ERROR", "Failed to initialize camera", e.message)
        }
    }

    private fun setupOptimalResolutions(characteristics: CameraCharacteristics) {
        val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return

        // Get available output sizes for preview (SurfaceTexture) and capture (JPEG)
        val previewSizes = configMap.getOutputSizes(SurfaceTexture::class.java).toList()
        val captureSizes = configMap.getOutputSizes(ImageFormat.JPEG).toList()

        // Choose optimal sizes
        previewSize = chooseBestPreviewSize(previewSizes)
        captureSize = chooseBestCaptureSize(captureSizes)

        // Log selected sizes
        Log.d(TAG, "Selected preview size: ${previewSize.width}x${previewSize.height}")
        Log.d(TAG, "Selected capture size: ${captureSize.width}x${captureSize.height}")
    }

    private fun chooseBestPreviewSize(sizes: List<Size>): Size {
        if (sizes.isEmpty()) return Size(640, 480)

        // Target around 720p for smooth preview
        val targetHeight = 720

        // Sort by how close sizes are to the target height
        val sortedSizes = sizes.sortedBy { Math.abs(it.height - targetHeight) }

        // Choose a good middle ground (not too small, not too large for preview)
        return sortedSizes.firstOrNull { it.height >= 720 && it.height <= 1080 }
            ?: sortedSizes.first()
    }

    private fun chooseBestCaptureSize(sizes: List<Size>): Size {
        if (sizes.isEmpty()) return Size(1920, 1080)

        // Find the largest available resolution
        return sizes.sortedByDescending { it.width.toLong() * it.height }.first()
    }

    private fun createCameraPreviewSession() {
        try {
            val cameraDevice = cameraDevice ?: return

            // Create preview request builder
            val previewRequestBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(previewSurface!!)

            // Set up initial zoom
            updateZoom(previewRequestBuilder)

            // Set up initial flash mode
            updateFlashMode(previewRequestBuilder)

            // Set best preview quality
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            previewRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)

            // Create capture session
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Use the new API for Android 9.0 (API 28) and above
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(
                        OutputConfiguration(previewSurface!!),
                        OutputConfiguration(imageReader!!.surface)
                    ),
                    activity.mainExecutor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cameraDevice == null) return
                            setupPreviewSession(session, previewRequestBuilder)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Failed to configure camera capture session")
                        }
                    }
                )
                cameraDevice.createCaptureSession(sessionConfig)
            } else {
                // Use the old API for older devices
                cameraDevice.createCaptureSession(
                    listOf(previewSurface, imageReader?.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cameraDevice == null) return
                            setupPreviewSession(session, previewRequestBuilder)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Failed to configure camera capture session")
                        }
                    },
                    null
                )
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create preview session", e)
        }
    }

    private fun setupPreviewSession(session: CameraCaptureSession, previewRequestBuilder: CaptureRequest.Builder) {
        captureSession = session

        try {
            // Auto-focus
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            // Start preview
            val previewRequest = previewRequestBuilder.build()
            captureSession?.setRepeatingRequest(
                previewRequest,
                null,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to start camera preview", e)
        }
    }

    private fun updateZoom(requestBuilder: CaptureRequest.Builder) {
        val characteristics = cameraManager.getCameraCharacteristics(currentCameraId!!)
        val rect =
            characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        // Calculate the crop region based on zoom level
        val zoomFactor = currentZoom
        val centerX = rect.width() / 2
        val centerY = rect.height() / 2
        val deltaX = (rect.width() / (2 * zoomFactor)).toInt()
        val deltaY = (rect.height() / (2 * zoomFactor)).toInt()

        cropRegion = Rect(
            centerX - deltaX,
            centerY - deltaY,
            centerX + deltaX,
            centerY + deltaY
        )

        requestBuilder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
    }

    private fun updatePreview() {
        if (cameraDevice == null || captureSession == null) return

        try {
            val previewRequestBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(previewSurface!!)

            // Apply zoom
            updateZoom(previewRequestBuilder)

            // Apply flash mode
            updateFlashMode(previewRequestBuilder)

            // Apply torch mode
            if (flashSupported) {
                previewRequestBuilder.set(
                    CaptureRequest.FLASH_MODE,
                    if (torchMode) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                )
            }

            // Set video stabilization for a smoother preview
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )

            // Continuous auto-focus
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            captureSession?.setRepeatingRequest(
                previewRequestBuilder.build(),
                null,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to update preview", e)
        }
    }

    private fun updateFlashMode(requestBuilder: CaptureRequest.Builder) {
        if (!flashSupported) return

        when (flashMode) {
            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH -> {
                requestBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                )
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> {
                requestBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                )
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            else -> {
                requestBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON
                )
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
        }
    }

    private fun setZoom(zoom: Float, result: MethodChannel.Result) {
        try {
            currentZoom = zoom.coerceIn(1.0f, maxZoom)
            updatePreview()
            result.success(currentZoom)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set zoom", e)
            result.error("ZOOM_ERROR", "Failed to set zoom", e.message)
        }
    }

    private fun getZoomRange(result: MethodChannel.Result) {
        result.success(
            mapOf(
                "min" to 1.0,
                "max" to maxZoom.toDouble(),
                "current" to currentZoom.toDouble()
            )
        )
    }

    private fun setFlashMode(mode: Int, result: MethodChannel.Result) {
        if (!flashSupported) {
            result.error("FLASH_ERROR", "Flash not supported on this device", null)
            return
        }

        try {
            flashMode = when (mode) {
                0 -> CaptureRequest.CONTROL_AE_MODE_ON
                1 -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                2 -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                else -> CaptureRequest.CONTROL_AE_MODE_ON
            }

            updatePreview()
            result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set flash mode", e)
            result.error("FLASH_ERROR", "Failed to set flash mode", e.message)
        }
    }

    private fun setFocusPoint(x: Float, y: Float, result: MethodChannel.Result) {
        if (cameraDevice == null || captureSession == null) {
            result.error("CAMERA_ERROR", "Camera not initialized", null)
            return
        }

        try {
            // Cancel any ongoing auto-focus
            val previewRequestBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(previewSurface!!)

            // Convert normalized coordinates to camera sensor coordinates
            val characteristics = cameraManager.getCameraCharacteristics(currentCameraId!!)
            val sensorRect =
                characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

            // Apply existing zoom if any
            if (cropRegion != null) {
                previewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
            }

            // Calculate focus area
            val focusAreaSize = 200
            val focusAreaLeft = clamp(
                (x * sensorRect.width() - focusAreaSize / 2).toInt(),
                0,
                sensorRect.width() - focusAreaSize
            )
            val focusAreaTop = clamp(
                (y * sensorRect.height() - focusAreaSize / 2).toInt(),
                0,
                sensorRect.height() - focusAreaSize
            )

            val focusAreaRect = Rect(
                focusAreaLeft,
                focusAreaTop,
                focusAreaLeft + focusAreaSize,
                focusAreaTop + focusAreaSize
            )

            val meteringRectangle =
                MeteringRectangle(focusAreaRect, MeteringRectangle.METERING_WEIGHT_MAX)

            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRectangle))
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRectangle))

            // Start auto-focus
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_AUTO
            )
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_START
            )

            // Apply flash mode
            updateFlashMode(previewRequestBuilder)

            // Capture with auto-focus
            captureSession?.capture(
                previewRequestBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        super.onCaptureCompleted(session, request, result)

                        // Reset auto-focus trigger after focusing is done
                        try {
                            previewRequestBuilder.set(
                                CaptureRequest.CONTROL_AF_TRIGGER,
                                CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                            )
                            previewRequestBuilder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            captureSession?.setRepeatingRequest(
                                previewRequestBuilder.build(),
                                null,
                                backgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Failed to reset auto-focus", e)
                        }
                    }
                },
                backgroundHandler
            )

            result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set focus point", e)
            result.error("FOCUS_ERROR", "Failed to set focus point", e.message)
        }
    }

    private fun toggleTorch(enable: Boolean, result: MethodChannel.Result) {
        if (!flashSupported) {
            result.error("TORCH_ERROR", "Torch not supported on this device", null)
            return
        }

        try {
            torchMode = enable
            updatePreview()
            result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle torch", e)
            result.error("TORCH_ERROR", "Failed to toggle torch", e.message)
        }
    }

    private fun takePicture(result: MethodChannel.Result) {
        if (cameraDevice == null) {
            result.error("CAMERA_ERROR", "Camera not initialized", null)
            return
        }

        try {
            // Create timestamped output file
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "IMG_$timeStamp.jpg"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraApp")
                }
            }

            val outputUri = activity.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )

            if (outputUri == null) {
                result.error("CAPTURE_ERROR", "Failed to create output file", null)
                return
            }

            // Set up image capture callback
            val imageReader = imageReader ?: return
            imageReader.setOnImageAvailableListener({ reader ->
                backgroundHandler?.post {
                    try {
                        val image = reader.acquireLatestImage()
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)

                        activity.contentResolver.openOutputStream(outputUri)?.use { output ->
                            output.write(bytes)
                        }

                        image.close()

                        activity.runOnUiThread {
                            result.success(outputUri.toString())
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save image", e)
                        activity.runOnUiThread {
                            result.error("CAPTURE_ERROR", "Failed to save image", e.message)
                        }
                    }
                }
            }, backgroundHandler)

            // Create capture request with high quality settings
            val captureBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(imageReader.surface)

                    // Set orientation
                    set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation())

                    // Set highest JPEG quality (100%)
                    set(CaptureRequest.JPEG_QUALITY, 90.toByte())

                    // Apply zoom
                    if (cropRegion != null) {
                        set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
                    }

                    // Set auto-focus
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )

                    // Enable image stabilization if available
                    set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )

                    // Set noise reduction to high quality
                    set(
                        CaptureRequest.NOISE_REDUCTION_MODE,
                        CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                    )

                    // Set edge enhancement to high quality
                    set(
                        CaptureRequest.EDGE_MODE,
                        CaptureRequest.EDGE_MODE_HIGH_QUALITY
                    )

                    // Set tone mapping to high quality
                    set(
                        CaptureRequest.TONEMAP_MODE,
                        CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
                    )

                    // Set color correction to high quality
                    set(
                        CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                        CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
                    )

                    // Apply flash settings
                    when (flashMode) {
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH -> {
                            set(
                                CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                            )
                        }

                        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> {
                            set(
                                CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                            )
                        }

                        else -> {
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }
                    }
                }

            // Capture the image with pre-capture sequence for better exposure
            captureStillPictureWithPrecapture(captureBuilder, result)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to take picture", e)
            result.error("CAPTURE_ERROR", "Failed to take picture", e.message)
        } catch (e: Exception) {
            Log.e(TAG, "Error taking picture", e)
            result.error("CAPTURE_ERROR", "Error taking picture", e.message)
        }
    }

    private fun captureStillPictureWithPrecapture(
        captureBuilder: CaptureRequest.Builder,
        result: MethodChannel.Result
    ) {
        try {
            // Run pre-capture sequence to ensure proper exposure before capturing
            val precaptureBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            precaptureBuilder.addTarget(previewSurface!!)

            // Trigger auto-exposure precapture
            precaptureBuilder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )

            captureSession?.capture(
                precaptureBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        // Now capture the actual image
                        captureSession?.stopRepeating()
                        captureSession?.capture(
                            captureBuilder.build(),
                            object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(
                                    session: CameraCaptureSession,
                                    request: CaptureRequest,
                                    result: TotalCaptureResult
                                ) {
                                    try {
                                        // Restart preview after capture
                                        updatePreview()
                                    } catch (e: CameraAccessException) {
                                        Log.e(TAG, "Failed to restart preview", e)
                                    }
                                }

                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: CaptureFailure
                        ) {
                            // Store the result reference in a final variable
                            val finalResult = result
                            activity.runOnUiThread {
                                //finalResult.error("CAPTURE_ERROR", "Image capture failed", null)
                            }
                            try {
                                updatePreview()
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to restart preview", e)
                            }
                        }
                            },
                            backgroundHandler
                        )
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed in pre-capture sequence", e)
            // Fall back to direct capture if pre-capture fails
            captureSession?.capture(
                captureBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        activity.runOnUiThread {
                            result.error("CAPTURE_ERROR", "Image capture failed", null)
                        }
                    }
                },
                backgroundHandler
            )
        }
    }

    private fun getJpegOrientation(): Int {
        val deviceOrientation = activity.windowManager.defaultDisplay.rotation
        val characteristics = cameraManager.getCameraCharacteristics(currentCameraId!!)

        // Calculate JPEG orientation based on sensor orientation and device rotation
        // Get sensor orientation
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        // Get the device orientation in degrees
        val deviceOrientationDegrees = when (deviceOrientation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        // Calculate rotation required based on lens facing and sensor orientation
        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
        val jpegOrientation = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            val result = (sensorOrientation + deviceOrientationDegrees) % 360
            (360 - result) % 360  // Front camera mirroring
        } else {
            (sensorOrientation - deviceOrientationDegrees + 360) % 360
        }

        return jpegOrientation
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread?.looper!!)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Failed to stop background thread", e)
        }
    }

    private fun disposeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            flutterTexture?.release()
            flutterTexture = null
            stopBackgroundThread()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing camera resources", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    fun dispose() {
        disposeCamera()
        methodChannel.setMethodCallHandler(null)
    }

    private fun clamp(value: Int, min: Int, max: Int): Int {
        return Math.max(min, Math.min(max, value))
    }
}