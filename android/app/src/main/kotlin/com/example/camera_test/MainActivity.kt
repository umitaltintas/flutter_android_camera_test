package com.example.camera_test

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity: FlutterActivity() {
    private var cameraXHandler: CameraXHandler? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Initialize CameraX handler
        cameraXHandler = CameraXHandler(
            this,
            flutterEngine.dartExecutor.binaryMessenger,
            flutterEngine.renderer
        )
    }

    override fun onDestroy() {
        cameraXHandler?.dispose()
        super.onDestroy()
    }
}
