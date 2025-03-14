import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  // Ensure Flutter bindings are initialized
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(),
      home: const CameraScreen(),
    );
  }
}

class CameraScreen extends StatefulWidget {
  const CameraScreen({Key? key}) : super(key: key);

  @override
  _CameraScreenState createState() => _CameraScreenState();
}

class _CameraScreenState extends State<CameraScreen>
    with WidgetsBindingObserver {
  static const MethodChannel _channel = MethodChannel(
    'com.example.camera_test/camera',
  );

  int _textureId = -1;
  bool _isInitialized = false;
  List<Map<String, dynamic>> _availableCameras = [];
  String _currentCameraId = "";
  Size _previewSize = const Size(0, 0);
  bool _isLoading = true;
  double _aspectRatio =
      4 / 3; // Default value, will be updated from native code

  // Camera control values
  double _currentZoom = 1.0;
  double _minZoom = 1.0;
  double _maxZoom = 1.0;
  bool _hasTorch = false;
  bool _isTorchOn = false;
  int _flashMode = 0; // 0: OFF, 1: ON, 2: AUTO

  // UI control flags
  bool _showControls = true;
  bool _showZoomSlider = false;
  bool _showFlashOptions = false;

  // Focus animation variables
  bool _showFocusCircle = false;
  double _focusX = 0;
  double _focusY = 0;

  // Preview fit control
  bool _fillScreen = false;
  BoxFit get _currentFit => _fillScreen ? BoxFit.cover : BoxFit.contain;

  // Permission status
  PermissionStatus _cameraPermissionStatus = PermissionStatus.denied;

  // Error tracking
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    // Initialize camera with a small delay to ensure platform channel is ready
    Future.delayed(Duration(milliseconds: 500), () {
      _requestCameraPermission();
    });
  }

  Future<void> _requestCameraPermission() async {
    debugPrint("Requesting camera permission...");

    try {
      // Request camera permission
      final status = await Permission.camera.request();

      debugPrint("Camera permission status: $status");

      setState(() {
        _cameraPermissionStatus = status;
      });

      if (status.isGranted) {
        // Permission granted, get cameras
        _getAvailableCameras();
      } else if (status.isPermanentlyDenied) {
        // The user opted to never again see the permission request dialog
        _showPermissionPermanentlyDeniedDialog();
      } else {
        // Permission denied but not permanently
        _showPermissionDeniedDialog();
      }
    } catch (e) {
      debugPrint("Error requesting permission: $e");
      setState(() {
        _errorMessage = "Failed to request camera permission: $e";
      });
    }
  }

  void _showPermissionDeniedDialog() {
    if (!mounted) return;

    showDialog(
      context: context,
      builder:
          (context) => AlertDialog(
            title: Text('Camera Permission'),
            content: Text('Camera permission is required to use the camera.'),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: Text('Cancel'),
              ),
              TextButton(
                onPressed: () {
                  Navigator.of(context).pop();
                  _requestCameraPermission();
                },
                child: Text('Try Again'),
              ),
            ],
          ),
    );
  }

  void _showPermissionPermanentlyDeniedDialog() {
    if (!mounted) return;

    showDialog(
      context: context,
      builder:
          (context) => AlertDialog(
            title: Text('Camera Permission'),
            content: Text(
              'Camera permission is required but has been permanently denied. '
              'Please enable it in app settings.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: Text('Close'),
              ),
              TextButton(
                onPressed: () {
                  Navigator.of(context).pop();
                  openAppSettings();
                },
                child: Text('Open Settings'),
              ),
            ],
          ),
    );
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    debugPrint("App lifecycle state changed to: $state");

    // App lifecycle management for camera
    if (state == AppLifecycleState.inactive ||
        state == AppLifecycleState.paused) {
      // Release camera resources when app is inactive
      debugPrint("Disposing camera due to app becoming inactive");
      _disposeCamera();
    } else if (state == AppLifecycleState.resumed) {
      // Check permission and reinitialize camera when app resumes
      debugPrint("App resumed, checking camera permission");

      Permission.camera.status.then((status) {
        debugPrint("Current camera permission status: $status");

        setState(() {
          _cameraPermissionStatus = status;
        });

        if (status.isGranted) {
          debugPrint("Permission is granted, reinitializing camera");
          if (_currentCameraId.isNotEmpty) {
            _initializeCamera(_currentCameraId);
          } else {
            _getAvailableCameras();
          }
        } else if (!status.isGranted) {
          // Permission lost between sessions
          debugPrint("Permission is not granted, requesting it again");
          _requestCameraPermission();
        }
      });
    }
  }

  Future<void> _getAvailableCameras() async {
    debugPrint("Getting available cameras...");

    // Ensure we have permission
    if (_cameraPermissionStatus != PermissionStatus.granted) {
      debugPrint("No camera permission, requesting it");
      await _requestCameraPermission();
      return;
    }

    try {
      setState(() {
        _isLoading = true;
        _errorMessage = null;
      });

      final List<dynamic>? cameras = await _channel.invokeMethod(
        'getAvailableCameras',
      );

      debugPrint("Received cameras: $cameras");

      if (cameras != null && cameras.isNotEmpty) {
        setState(() {
          _availableCameras =
              cameras
                  .map((camera) => Map<String, dynamic>.from(camera as Map))
                  .toList();
          _isLoading = false;
        });

        // Log all camera details for debugging
        for (var camera in _availableCameras) {
          debugPrint(
            "Camera ID: ${camera['id']}, Name: ${camera['name']}, "
            "Wide-angle: ${camera['isWideAngle']}, "
            "Default: ${camera['isDefault']}, "
            "Focal Length: ${camera['focalLength']}",
          );
        }

        // Try to find a wide-angle camera first if available
        final wideAngleCamera = _availableCameras.firstWhere(
          (camera) => camera['isWideAngle'] == true,
          orElse:
              () => _availableCameras.firstWhere(
                (camera) => camera['isDefault'] == true,
                orElse: () => _availableCameras.first,
              ),
        );

        debugPrint(
          "Selected camera: ${wideAngleCamera['id']}, Name: ${wideAngleCamera['name']}",
        );
        _initializeCamera(wideAngleCamera['id']);
      } else {
        debugPrint("No cameras found or empty camera list returned");
        setState(() {
          _isLoading = false;
          _errorMessage = "No cameras found on this device";
        });
      }
    } on PlatformException catch (e) {
      debugPrint("Platform Exception: Failed to get cameras: ${e.message}");
      setState(() {
        _isLoading = false;
        _errorMessage = "Failed to get cameras: ${e.message}";
      });
    } catch (e) {
      debugPrint("Exception: Failed to get cameras: $e");
      setState(() {
        _isLoading = false;
        _errorMessage = "Error: $e";
      });
    }
  }

  Future<void> _initializeCamera(String cameraId) async {
    debugPrint("Initializing camera with ID: $cameraId");

    // Ensure we have permission
    if (_cameraPermissionStatus != PermissionStatus.granted) {
      debugPrint("No camera permission, requesting it");
      await _requestCameraPermission();
      return;
    }

    try {
      setState(() {
        _isLoading = true;
        _errorMessage = null;
      });

      debugPrint("Invoking native initializeCamera method");
      final dynamic rawResult = await _channel.invokeMethod(
        'initializeCamera',
        {'cameraId': cameraId},
      );

      debugPrint("Received rawResult: $rawResult");

      final Map<String, dynamic>? result =
          rawResult != null
              ? Map<String, dynamic>.from(rawResult as Map)
              : null;

      if (result != null) {
        final textureId = result['textureId'] ?? -1;

        debugPrint("Camera initialized with textureId: $textureId");

        if (textureId < 0) {
          debugPrint("Invalid texture ID returned: $textureId");
          setState(() {
            _isLoading = false;
            _errorMessage = "Failed to initialize camera texture";
          });
          return;
        }

        setState(() {
          _textureId = textureId;
          _previewSize = Size(
            result['width']?.toDouble() ?? 0.0,
            result['height']?.toDouble() ?? 0.0,
          );
          // Use the aspect ratio directly from Camera2
          _aspectRatio =
              result['aspectRatio'] ??
              (_previewSize.width / _previewSize.height);
          _minZoom = result['minZoom']?.toDouble() ?? 1.0;
          _maxZoom = result['maxZoom']?.toDouble() ?? 1.0;
          _currentZoom = 1.0; // Start at normal zoom
          _hasTorch = result['hasTorch'] ?? false;
          _isInitialized = true;
          _currentCameraId = cameraId;
          _isLoading = false;
        });

        debugPrint(
          "Camera initialized with aspect ratio: $_aspectRatio, resolution: ${_previewSize.width}x${_previewSize.height}, zoom range: $_minZoom to $_maxZoom",
        );
      } else {
        debugPrint("Null result returned from initializeCamera");
        setState(() {
          _isLoading = false;
          _errorMessage = "Failed to initialize camera: null result";
        });
      }
    } on PlatformException catch (e) {
      debugPrint(
        "Platform Exception: Failed to initialize camera: ${e.message}, ${e.details}",
      );
      setState(() {
        _isLoading = false;
        _errorMessage = "Failed to initialize camera: ${e.message}";
      });
    } catch (e) {
      debugPrint("Exception: Failed to initialize camera: $e");
      setState(() {
        _isLoading = false;
        _errorMessage = "Error initializing camera: $e";
      });
    }
  }

  Future<void> _disposeCamera() async {
    debugPrint("Disposing camera resources");
    try {
      if (_isInitialized) {
        await _channel.invokeMethod('disposeCamera');
        setState(() {
          _isInitialized = false;
        });
      }
    } catch (e) {
      debugPrint("Error disposing camera: $e");
    }
  }

  Future<void> _takePicture() async {
    try {
      debugPrint("Taking picture...");
      final String? path = await _channel.invokeMethod('takePicture');
      debugPrint("Picture saved to: $path");

      if (path != null && mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('Picture saved to: $path')));
      }
    } on PlatformException catch (e) {
      debugPrint("Failed to take picture: ${e.message}");
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Failed to take picture: ${e.message}')),
      );
    }
  }

  Future<void> _setZoom(double zoom) async {
    try {
      final double result = await _channel.invokeMethod('setZoom', {
        'zoom': zoom,
      });
      setState(() {
        _currentZoom = result;
      });
    } on PlatformException catch (e) {
      debugPrint("Failed to set zoom: ${e.message}");
    }
  }

  Future<void> _setFlashMode(int mode) async {
    try {
      final bool success = await _channel.invokeMethod('setFlashMode', {
        'mode': mode,
      });
      if (success) {
        setState(() {
          _flashMode = mode;
          _showFlashOptions = false;
        });
      }
    } on PlatformException catch (e) {
      debugPrint("Failed to set flash mode: ${e.message}");
    }
  }

  Future<void> _setFocusPoint(double x, double y) async {
    try {
      await _channel.invokeMethod('setFocusPoint', {'x': x, 'y': y});
    } on PlatformException catch (e) {
      debugPrint("Failed to set focus point: ${e.message}");
    }
  }

  Future<void> _toggleTorch(bool enable) async {
    if (!_hasTorch) return;

    try {
      final bool success = await _channel.invokeMethod('toggleTorch', {
        'enable': enable,
      });
      if (success) {
        setState(() {
          _isTorchOn = enable;
        });
      }
    } on PlatformException catch (e) {
      debugPrint("Failed to toggle torch: ${e.message}");
    }
  }

  // Try to reinitialize the camera if it failed initially
  void _retryInitialization() {
    debugPrint("Retrying camera initialization...");
    setState(() {
      _errorMessage = null;
      _isLoading = true;
    });

    if (_currentCameraId.isNotEmpty) {
      _initializeCamera(_currentCameraId);
    } else {
      _getAvailableCameras();
    }
  }

  @override
  void dispose() {
    debugPrint("Disposing camera screen");
    WidgetsBinding.instance.removeObserver(this);
    _disposeCamera();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(backgroundColor: Colors.black, body: _buildCameraContent());
  }

  Widget _buildCameraContent() {
    // Show different UI based on permission status
    if (_cameraPermissionStatus != PermissionStatus.granted) {
      return _buildPermissionRequest();
    }

    if (_isLoading) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text(
              "Initializing camera...",
              style: TextStyle(color: Colors.white70),
            ),
          ],
        ),
      );
    }

    if (_errorMessage != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.error_outline, size: 48, color: Colors.red),
            SizedBox(height: 16),
            Text(
              "Camera Error",
              style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
            ),
            SizedBox(height: 8),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24),
              child: Text(
                _errorMessage!,
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.white70),
              ),
            ),
            SizedBox(height: 24),
            ElevatedButton(
              onPressed: _retryInitialization,
              child: Text("Retry"),
            ),
          ],
        ),
      );
    }

    if (!_isInitialized) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('Camera not initialized', style: TextStyle(fontSize: 18)),
            SizedBox(height: 16),
            ElevatedButton(
              onPressed: _retryInitialization,
              child: Text("Initialize Camera"),
            ),
          ],
        ),
      );
    }

    // Camera is initialized and permission granted
    return _buildCameraPreview();
  }

  Widget _buildPermissionRequest() {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.camera_alt, size: 64, color: Colors.white54),
          SizedBox(height: 16),
          Text(
            'Camera Permission Required',
            style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
          ),
          SizedBox(height: 8),
          Text(
            'This app needs camera access to function properly',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 16),
          ),
          SizedBox(height: 24),
          ElevatedButton(
            onPressed: _requestCameraPermission,
            child: Text('Grant Camera Permission'),
            style: ElevatedButton.styleFrom(
              padding: EdgeInsets.symmetric(horizontal: 24, vertical: 12),
            ),
          ),
          if (_cameraPermissionStatus.isPermanentlyDenied)
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: TextButton(
                onPressed: openAppSettings,
                child: Text('Open Settings'),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildCameraPreview() {
    return Stack(
      children: [
        // Camera preview
        GestureDetector(
          onTapUp: (details) {
            final RenderBox box = context.findRenderObject() as RenderBox;
            final offset = box.globalToLocal(details.globalPosition);

            // Convert tap position to normalized coordinates
            final double x = offset.dx / box.size.width;
            final double y = offset.dy / box.size.height;

            // Set focus point
            _setFocusPoint(x, y);

            // Show focus animation
            setState(() {
              _focusX = offset.dx;
              _focusY = offset.dy;
              _showFocusCircle = true;
            });

            // Hide focus circle after 2 seconds
            Future.delayed(Duration(seconds: 2), () {
              if (mounted) {
                setState(() {
                  _showFocusCircle = false;
                });
              }
            });
          },
          onDoubleTap: () {
            setState(() {
              _showControls = !_showControls;
            });
          },
          child: Container(
            width: MediaQuery.of(context).size.width,
            height: MediaQuery.of(context).size.height,
            color: Colors.black,
            alignment: Alignment.center,
            child: FittedBox(
              fit: _currentFit,
              child: SizedBox(
                width: _previewSize.width,
                height: _previewSize.height,
                child: Texture(textureId: _textureId),
              ),
            ),
          ),
        ),

        // Focus indicator
        if (_showFocusCircle)
          Positioned(
            left: _focusX - 40,
            top: _focusY - 40,
            child: Container(
              width: 80,
              height: 80,
              decoration: BoxDecoration(
                border: Border.all(color: Colors.white, width: 2.0),
                borderRadius: BorderRadius.circular(40.0),
              ),
            ),
          ),

        // Top controls bar
        if (_showControls)
          Positioned(
            top: MediaQuery.of(context).padding.top + 16,
            left: 0,
            right: 0,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                // Flash control
                GestureDetector(
                  onTap: () {
                    setState(() {
                      _showFlashOptions = !_showFlashOptions;
                      _showZoomSlider = false;
                    });
                  },
                  child: Container(
                    padding: EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: Colors.black26,
                      borderRadius: BorderRadius.circular(30),
                    ),
                    child: Icon(
                      _flashMode == 0
                          ? Icons.flash_off
                          : _flashMode == 1
                          ? Icons.flash_on
                          : Icons.flash_auto,
                      color: Colors.white,
                      size: 28,
                    ),
                  ),
                ),

                // Torch toggle
                if (_hasTorch)
                  GestureDetector(
                    onTap: () => _toggleTorch(!_isTorchOn),
                    child: Container(
                      padding: EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        color: Colors.black26,
                        borderRadius: BorderRadius.circular(30),
                      ),
                      child: Icon(
                        _isTorchOn ? Icons.highlight : Icons.highlight_outlined,
                        color: _isTorchOn ? Colors.amber : Colors.white,
                        size: 28,
                      ),
                    ),
                  ),

                // Zoom control
                GestureDetector(
                  onTap: () {
                    setState(() {
                      _showZoomSlider = !_showZoomSlider;
                      _showFlashOptions = false;
                    });
                  },
                  child: Container(
                    padding: EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: Colors.black26,
                      borderRadius: BorderRadius.circular(30),
                    ),
                    child: Row(
                      children: [
                        Icon(Icons.zoom_in, color: Colors.white, size: 28),
                        SizedBox(width: 4),
                        Text(
                          '${_currentZoom.toStringAsFixed(1)}x',
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),

                // Screen fill toggle
                GestureDetector(
                  onTap: () {
                    setState(() {
                      _fillScreen = !_fillScreen;
                    });
                  },
                  child: Container(
                    padding: EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: Colors.black26,
                      borderRadius: BorderRadius.circular(30),
                    ),
                    child: Icon(
                      _fillScreen ? Icons.fit_screen : Icons.fullscreen,
                      color: Colors.white,
                      size: 28,
                    ),
                  ),
                ),
              ],
            ),
          ),

        // Flash mode options
        if (_showControls && _showFlashOptions)
          Positioned(
            top: MediaQuery.of(context).padding.top + 80,
            left: 20,
            child: Container(
              padding: EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: Colors.black54,
                borderRadius: BorderRadius.circular(10),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _flashOptionTile(0, 'Off', Icons.flash_off),
                  _flashOptionTile(1, 'On', Icons.flash_on),
                  _flashOptionTile(2, 'Auto', Icons.flash_auto),
                ],
              ),
            ),
          ),

        // Zoom slider
        if (_showControls && _showZoomSlider)
          Positioned(
            top: MediaQuery.of(context).padding.top + 80,
            left: 0,
            right: 0,
            child: Container(
              margin: EdgeInsets.symmetric(horizontal: 40),
              padding: EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.black54,
                borderRadius: BorderRadius.circular(10),
              ),
              child: Column(
                children: [
                  Text(
                    'Zoom: ${_currentZoom.toStringAsFixed(1)}x',
                    style: TextStyle(color: Colors.white),
                  ),
                  Row(
                    children: [
                      Text(
                        '${_minZoom.toStringAsFixed(1)}x',
                        style: TextStyle(color: Colors.white),
                      ),
                      Expanded(
                        child: Slider(
                          value: _currentZoom,
                          min: _minZoom,
                          max: _maxZoom,
                          onChanged: (value) {
                            setState(() {
                              _currentZoom = value;
                            });
                            _setZoom(value);
                          },
                          activeColor: Colors.white,
                        ),
                      ),
                      Text(
                        '${_maxZoom.toStringAsFixed(1)}x',
                        style: TextStyle(color: Colors.white),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),

        // Bottom controls
        if (_showControls)
          Positioned(
            bottom: 20,
            left: 0,
            right: 0,
            child: Column(
              children: [
                // Camera selector
                if (_availableCameras.length > 1)
                  Container(
                    height: 60,
                    child: ListView.builder(
                      scrollDirection: Axis.horizontal,
                      itemCount: _availableCameras.length,
                      itemBuilder: (context, index) {
                        final camera = _availableCameras[index];
                        final isSelected = _currentCameraId == camera['id'];
                        final isWideAngle = camera['isWideAngle'] == true;
                        final name = camera['name'] as String;
                        final focalLength = camera['focalLength'] as double?;

                        String cameraInfo = name;
                        if (focalLength != null) {
                          cameraInfo += " ${focalLength.toStringAsFixed(1)}mm";
                        }

                        return Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 8.0),
                          child: ElevatedButton(
                            style: ElevatedButton.styleFrom(
                              backgroundColor:
                                  isSelected
                                      ? (isWideAngle
                                          ? Colors.teal
                                          : Colors.blue)
                                      : Colors.grey[800],
                            ),
                            onPressed: () => _initializeCamera(camera['id']),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Text(cameraInfo),
                                if (isWideAngle)
                                  Icon(Icons.panorama_wide_angle, size: 16),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
                  ),

                const SizedBox(height: 20),

                // Take picture button
                Center(
                  child: FloatingActionButton(
                    onPressed: _isInitialized ? _takePicture : null,
                    backgroundColor: Colors.white,
                    child: const Icon(Icons.camera_alt, color: Colors.black),
                  ),
                ),
              ],
            ),
          ),
      ],
    );
  }

  // Helper for flash option tiles
  Widget _flashOptionTile(int mode, String label, IconData icon) {
    return GestureDetector(
      onTap: () => _setFlashMode(mode),
      child: Container(
        padding: EdgeInsets.symmetric(vertical: 8, horizontal: 16),
        decoration: BoxDecoration(
          color: _flashMode == mode ? Colors.white24 : Colors.transparent,
          borderRadius: BorderRadius.circular(5),
        ),
        child: Row(
          children: [
            Icon(icon, color: Colors.white, size: 24),
            SizedBox(width: 8),
            Text(label, style: TextStyle(color: Colors.white, fontSize: 16)),
          ],
        ),
      ),
    );
  }
}
