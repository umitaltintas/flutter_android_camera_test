package com.example.camera_test

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity: FlutterActivity() {
    private var camera2Handler: Camera2Handler? = null
    
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        // Initialize Camera2 handler
        camera2Handler = Camera2Handler(
            this,
            flutterEngine.dartExecutor.binaryMessenger,
            flutterEngine.renderer
        )
    }
    
    override fun onDestroy() {
        camera2Handler?.dispose()
        super.onDestroy()
    }
}