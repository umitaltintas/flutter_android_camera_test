package com.example.camera_test

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.OnImageSavedCallback
import androidx.camera.core.ImageCapture.OutputFileOptions
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CameraXHandler(
    private val activity: Activity,
    messenger: BinaryMessenger,
    private val textureRegistry: TextureRegistry
) : MethodChannel.MethodCallHandler {

    private val TAG = "CameraXHandler"
    private val methodChannel: MethodChannel =
        MethodChannel(messenger, "com.example.camera_test/camera")

    // Camera components
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private var cameraSelector: CameraSelector? = null
    private var mainExecutor: Executor = ContextCompat.getMainExecutor(activity)
    private var backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Flutter texture entry
    private var flutterTexture: TextureRegistry.SurfaceTextureEntry? = null
    private var previewSurface: Surface? = null

    // Camera settings
    private var currentCameraId: String? = null
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var torchEnabled = false
    private var previewSize = Size(0, 0)
    private var captureSize = Size(0, 0)

    // Camera details storage
    private val cameraDetailsMap = mutableMapOf<String, CameraDetails>()

    init {
        methodChannel.setMethodCallHandler(this)
    }

    data class CameraDetails(
        val cameraId: String,
        val lensFacing: Int,
        val focalLength: Float,
        val fieldOfView: Float,
        val isWideAngle: Boolean,
        val minZoom: Double,
        val maxZoom: Double,
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
        // Run in background to avoid blocking UI thread
        backgroundExecutor.execute {
            try {
                // Initialize camera provider if not already initialized
                if (cameraProvider == null) {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
                    cameraProvider = cameraProviderFuture.get()
                }

                val provider = cameraProvider!!
                val cameras = mutableListOf<Map<String, Any>>()
                cameraDetailsMap.clear()

                // Track the widest angle camera and default back camera
                var defaultBackCamera: String? = null
                var widestAngleCamera: String? = null
                var widestAngleFOV = 0f

                // Get all camera info
                val cameraList = provider.availableCameraInfos
                for (cameraInfo in cameraList) {
                    try {
                        // Get camera ID (requires parsing from selector string)
                        val cameraId = getCameraIdFromCameraInfo(cameraInfo)
                        
                        // Get lens facing
                        val lensFacing = when {
                            CameraSelector.DEFAULT_BACK_CAMERA.filter(listOf(cameraInfo)).isNotEmpty() -> 
                                CameraCharacteristics.LENS_FACING_BACK
                            CameraSelector.DEFAULT_FRONT_CAMERA.filter(listOf(cameraInfo)).isNotEmpty() -> 
                                CameraCharacteristics.LENS_FACING_FRONT
                            else -> -1
                        }
                        
                        // Get supported resolutions
                        val supportedResolutions = getSupportedResolutions(cameraInfo)
                        
                        // Get zoom range
                        val zoomState = cameraInfo.zoomState.value
                        val minZoom = zoomState?.minZoomRatio?.toDouble() ?: 1.0
                        val maxZoom = zoomState?.maxZoomRatio?.toDouble() ?: 1.0
                        
                        // Calculate field of view and determine if wide angle
                        // Wide angle detection based on zoom ratio < 1.0
                        val isWideAngle = minZoom < 0.9
                        val fieldOfView = if (isWideAngle) 110.0f else 70.0f
                        
                        // Approximate focal length (lower for wide angle)
                        val focalLength = if (isWideAngle) 2.5f else 4.5f
                        
                        // Flash support
                        val hasFlash = cameraInfo.hasFlashUnit()
                        
                        // Sensor orientation
                        val sensorRotation = cameraInfo.sensorRotationDegrees
                        
                        // Store camera details
                        cameraDetailsMap[cameraId] = CameraDetails(
                            cameraId = cameraId,
                            lensFacing = lensFacing,
                            focalLength = focalLength,
                            fieldOfView = fieldOfView,
                            isWideAngle = isWideAngle,
                            minZoom = minZoom,
                            maxZoom = maxZoom,
                            hasFlash = hasFlash,
                            sensorOrientation = sensorRotation,
                            supportedSizes = supportedResolutions
                        )
                        
                        // Track the widest angle camera
                        if (lensFacing == CameraCharacteristics.LENS_FACING_BACK && isWideAngle) {
                            if (fieldOfView > widestAngleFOV) {
                                widestAngleFOV = fieldOfView
                                widestAngleCamera = cameraId
                            }
                        }
                        
                        // Track default back camera
                        if (lensFacing == CameraCharacteristics.LENS_FACING_BACK && !isWideAngle && defaultBackCamera == null) {
                            defaultBackCamera = cameraId
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error examining camera: ${e.message}")
                        continue
                    }
                }
                
                // If we couldn't identify a default camera, use the first back camera
                if (defaultBackCamera == null) {
                    for ((cameraId, details) in cameraDetailsMap) {
                        if (details.lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                            defaultBackCamera = cameraId
                            break
                        }
                    }
                }
                
                // Create the camera list to return to Flutter
                for ((cameraId, details) in cameraDetailsMap) {
                    var lensType = when (details.lensFacing) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                        CameraCharacteristics.LENS_FACING_BACK -> "Back"
                        else -> "External"
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
                                "FOV: ${details.fieldOfView}, Wide: ${details.isWideAngle}, " +
                                "Zoom Range: ${details.minZoom}x-${details.maxZoom}x, Default: $isDefault"
                    )
                    
                    val cameraMap = mapOf(
                        "id" to cameraId,
                        "name" to lensType,
                        "isDefault" to isDefault,
                        "isWideAngle" to details.isWideAngle,
                        "focalLength" to details.focalLength,
                        "fieldOfView" to details.fieldOfView,
                        "minZoom" to details.minZoom,
                        "maxZoom" to details.maxZoom,
                        "hasFlash" to details.hasFlash
                    )
                    cameras.add(cameraMap)
                }
                
                activity.runOnUiThread {
                    result.success(cameras)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get cameras: ${e.message}", e)
                activity.runOnUiThread {
                    result.error("CAMERA_ERROR", "Failed to get available cameras", e.message)
                }
            }
        }
    }

    private fun getCameraIdFromCameraInfo(cameraInfo: CameraInfo): String {
        // This is a workaround since CameraX doesn't expose direct camera IDs
        // We use a hash code instead as a unique identifier
        return cameraInfo.toString().hashCode().toString()
    }

    private fun getSupportedResolutions(cameraInfo: CameraInfo): List<Size> {
        val resolutions = mutableListOf<Size>()
        try {
            // Query available resolutions for ImageCapture
            val qualities = arrayOf(
                Quality.UHD, Quality.FHD, Quality.HD, Quality.SD
            )
            
            for (quality in qualities) {
                val resolution = QualitySelector.getResolution(cameraInfo, quality)
                if (resolution != null) {
                    resolutions.add(Size(resolution.width, resolution.height))
                }
            }
            
            // If we don't have any resolutions, add some defaults
            if (resolutions.isEmpty()) {
                resolutions.add(Size(3840, 2160)) // 4K
                resolutions.add(Size(1920, 1080)) // 1080p
                resolutions.add(Size(1280, 720))  // 720p
                resolutions.add(Size(640, 480))   // 480p
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting supported resolutions: ${e.message}")
            // Add fallback resolutions
            resolutions.add(Size(1920, 1080))
            resolutions.add(Size(1280, 720))
        }
        return resolutions
    }

    private fun initializeCamera(cameraId: String?, result: MethodChannel.Result) {
        if (cameraId == null) {
            result.error("CAMERA_ERROR", "Camera ID cannot be null", null)
            return
        }

        // Clean up existing camera
        disposeCamera()

        // Get camera details
        val cameraDetails = cameraDetailsMap[cameraId]
        if (cameraDetails == null) {
            result.error("CAMERA_ERROR", "Camera details not found for ID: $cameraId", null)
            return
        }

        // Store current camera ID
        currentCameraId = cameraId

        // Create a new texture entry for Flutter
        flutterTexture = textureRegistry.createSurfaceTexture()
        val surfaceTexture = flutterTexture?.surfaceTexture()

        // Set optimal sizes
        previewSize = chooseBestPreviewSize(cameraDetails.supportedSizes)
        captureSize = chooseBestCaptureSize(cameraDetails.supportedSizes)

        // Set buffer size
        surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)

        // Create surface for preview
        previewSurface = Surface(surfaceTexture)

        // Determine camera selector based on lens facing
        cameraSelector = when (cameraDetails.lensFacing) {
            CameraCharacteristics.LENS_FACING_BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            CameraCharacteristics.LENS_FACING_FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Initialize CameraX in the background
        backgroundExecutor.execute {
            try {
                // Get camera provider instance
                if (cameraProvider == null) {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
                    cameraProvider = cameraProviderFuture.get()
                }

                val provider = cameraProvider!!

                // Unbind all uses cases before rebinding
                provider.unbindAll()

                // Configure preview
                preview = Preview.Builder()
                    .setTargetResolution(Size(previewSize.width, previewSize.height))
                    .build()
                    .also {
                        it.setSurfaceProvider { request ->
                            request.provideSurface(previewSurface!!, backgroundExecutor) {
                                // Surface cleanup not needed here as we manage it in disposeCamera
                            }
                        }
                    }

                // Configure image capture
                imageCapture = ImageCapture.Builder()
                    .setTargetResolution(Size(captureSize.width, captureSize.height))
                    .setFlashMode(flashMode)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()

                // Bind use cases
                camera = provider.bindToLifecycle(
                    activity as LifecycleOwner,
                    cameraSelector!!,
                    preview,
                    imageCapture
                )

                // Set initial torch mode
                camera?.cameraControl?.enableTorch(torchEnabled)

                // Return camera info to Flutter on main thread
                activity.runOnUiThread {
                    val zoomState = camera?.cameraInfo?.zoomState?.value
                    val responseMap = HashMap<String, Any>()
                    responseMap["textureId"] = flutterTexture!!.id()
                    responseMap["width"] = previewSize.width
                    responseMap["height"] = previewSize.height
                    responseMap["aspectRatio"] = previewSize.width.toDouble() / previewSize.height
                    responseMap["captureWidth"] = captureSize.width
                    responseMap["captureHeight"] = captureSize.height
                    responseMap["minZoom"] = zoomState?.minZoomRatio?.toDouble() ?: cameraDetails.minZoom
                    responseMap["maxZoom"] = zoomState?.maxZoomRatio?.toDouble() ?: cameraDetails.maxZoom
                    responseMap["hasTorch"] = cameraDetails.hasFlash
                    responseMap["fieldOfView"] = cameraDetails.fieldOfView
                    responseMap["focalLength"] = cameraDetails.focalLength
                    responseMap["isWideAngle"] = cameraDetails.isWideAngle
                    result.success(responseMap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize camera: ${e.message}", e)
                activity.runOnUiThread {
                    result.error("CAMERA_ERROR", "Failed to initialize camera", e.message)
                }
            }
        }
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

        // Find a large resolution for photo capture
        val sortedSizes = sizes.sortedByDescending { it.width.toLong() * it.height }
        
        // Target a reasonably high resolution (e.g., 8MP or higher) but not excessive
        return sortedSizes.firstOrNull { it.width * it.height <= 16_000_000 && it.width * it.height >= 8_000_000 }
            ?: sortedSizes.first()
    }

    private fun takePicture(result: MethodChannel.Result) {
        val imageCapture = this.imageCapture
        if (imageCapture == null) {
            result.error("CAMERA_ERROR", "Camera not initialized", null)
            return
        }

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

        // Create output options for saving the photo
        val outputOptions = OutputFileOptions.Builder(
            activity.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        // Take the picture
        imageCapture.takePicture(
            outputOptions,
            mainExecutor,
            object : OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri
                    if (savedUri != null) {
                        result.success(savedUri.toString())
                    } else {
                        result.error("CAPTURE_ERROR", "Failed to save image - URI is null", null)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    result.error(
                        "CAPTURE_ERROR",
                        "Failed to take picture: ${exception.message}",
                        exception.toString()
                    )
                }
            }
        )
    }

    private fun setZoom(zoom: Float, result: MethodChannel.Result) {
        val camera = this.camera
        if (camera == null) {
            result.error("CAMERA_ERROR", "Camera not initialized", null)
            return
        }

        try {
            val zoomState = camera.cameraInfo.zoomState.value
            val minZoom = zoomState?.minZoomRatio ?: 1.0f
            val maxZoom = zoomState?.maxZoomRatio ?: 1.0f
            
            // Ensure zoom is within valid range
            val clampedZoom = zoom.coerceIn(minZoom, maxZoom)
            
            // Set zoom asynchronously
            camera.cameraControl.setZoomRatio(clampedZoom)
            result.success(clampedZoom.toDouble())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set zoom: ${e.message}", e)
            result.error("ZOOM_ERROR", "Failed to set zoom", e.message)
        }
    }

    private fun getZoomRange(result: MethodChannel.Result) {
        val camera = this.camera
        if (camera == null) {
            result.error("CAMERA_ERROR", "Camera not initialized", null)
            return
        }

        val zoomState = camera.cameraInfo.zoomState.value
        val minZoom = zoomState?.minZoomRatio?.toDouble() ?: 1.0
        val maxZoom = zoomState?.maxZoomRatio?.toDouble() ?: 1.0
        val currentZoom = zoomState?.zoomRatio?.toDouble() ?: 1.0

        result.success(
            mapOf(
                "min" to minZoom,
                "max" to maxZoom,
                "current" to currentZoom
            )
        )
    }

    private fun setFlashMode(mode: Int, result: MethodChannel.Result) {
        val imageCapture = this.imageCapture
        if (imageCapture == null) {
            result.error("CAMERA_ERROR", "Camera not initialized", null)
            return
        }

        try {
            flashMode = when (mode) {
                0 -> ImageCapture.FLASH_MODE_OFF
                1 -> ImageCapture.FLASH_MODE_ON
                2 -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }

            imageCapture.flashMode = flashMode
            result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set flash mode: ${e.message}", e)
            result.error("FLASH_ERROR", "Failed to set flash mode", e.message)
        }
    }

    private fun setFocusPoint(x: Float, y: Float, result: MethodChannel.Result) {
        val camera = this.camera
        if (camera == null) {
            result.error("CAMERA_ERROR", "Camera not initialized", null)
            return
        }

        try {
            // Convert normalized coordinates (0-1) to MeteringPoint
            val meteringPointFactory = camera.cameraInfo.meteringPointFactory
            val meteringPoint = meteringPointFactory.createPoint(x, y)

            // Build focus and metering actions
            val focusMeteringAction = FocusMeteringAction.Builder(meteringPoint)
                .addPoint(meteringPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            // Start focus and metering
            camera.cameraControl.startFocusAndMetering(focusMeteringAction)
                .addListener({
                    result.success(true)
                }, mainExecutor)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set focus point: ${e.message}", e)
            result.error("FOCUS_ERROR", "Failed to set focus point", e.message)
        }
    }

    private fun toggleTorch(enable: Boolean, result: MethodChannel.Result) {
        val camera = this.camera
        if (camera == null) {
            result.error("CAMERA_ERROR", "Camera not initialized", null)
            return
        }

        if (!camera.cameraInfo.hasFlashUnit()) {
            result.error("TORCH_ERROR", "Torch not supported on this device", null)
            return
        }

        try {
            torchEnabled = enable
            camera.cameraControl.enableTorch(enable)
                .addListener({
                    result.success(true)
                }, mainExecutor)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle torch: ${e.message}", e)
            result.error("TORCH_ERROR", "Failed to toggle torch", e.message)
        }
    }

    private fun disposeCamera() {
        try {
            // Unbind all camera use cases
            cameraProvider?.unbindAll()
            
            // Release the surface
            previewSurface?.release()
            previewSurface = null
            
            // Release the texture entry
            flutterTexture?.release()
            flutterTexture = null
            
            // Reset camera references
            camera = null
            imageCapture = null
            preview = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing camera resources: ${e.message}", e)
        }
    }

    fun dispose() {
        disposeCamera()
        methodChannel.setMethodCallHandler(null)
        backgroundExecutor.shutdown()
    }
}
