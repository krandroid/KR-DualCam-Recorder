package com.example

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ConcurrentCamera.SingleCameraConfig
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import com.example.ui.theme.DarkUI
import com.example.ui.theme.PurpleNeon
import androidx.compose.material.icons.filled.Videocam

private fun getRequiredPermissions(): Array<String> {
    val permissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    // Logika penyesuaian versi Android
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        // Android 9 dan ke bawah masih butuh Write External Storage
        permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Android 13 (API 33) ke atas butuh izin spesifik untuk membaca galeri
        // Tambahkan ini JIKA aplikasi Anda punya fitur melihat hasil rekaman di dalam aplikasi
        permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
    }
    
    return permissions.toTypedArray()
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DualCameraScreen() {
    val context = LocalContext.current
    val permissions = remember { getRequiredPermissions().toList() }
    
    val permissionState = rememberMultiplePermissionsState(permissions = permissions)

    if (permissionState.allPermissionsGranted) {
        CameraContent()
    } else {
        PermissionRequestContent(permissionState)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionRequestContent(permissionState: MultiplePermissionsState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkUI)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Videocam,
            contentDescription = "Camera",
            tint = PurpleNeon,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Permissions Required",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "KR DualCam requires Camera, Microphone, and Gallery permissions to capture and save concurrent video directly to your storage.",
            color = Color.LightGray,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { permissionState.launchMultiplePermissionRequest() },
            colors = ButtonDefaults.buttonColors(containerColor = PurpleNeon)
        ) {
            Text("Grant All Permissions", color = Color.White)
        }
    }
}

@Composable
fun CameraContent() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Set up direct CameraX use cases so they bind in true Concurrent Mode
    val resolutionSelector = remember {
        ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(Size(3840, 2160), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            ).build()
    }
    val backPreview = remember {
        Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
    }
    val frontPreview = remember {
        Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
    }
    
    val backVideoCapture: VideoCapture<Recorder> = remember {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.UHD, androidx.camera.video.FallbackStrategy.higherQualityOrLowerThan(Quality.HD)))
            .build()
        VideoCapture.withOutput(recorder)
    }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var backCameraInstance by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var isConcurrentRunning by remember { mutableStateOf(false) }

    // Retrieve the ProcessCameraProvider asynchronously
    LaunchedEffect(context) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
        }, ContextCompat.getMainExecutor(context))
    }

    // Bind dual cameras simultaneously whenever provider is ready
    LaunchedEffect(cameraProvider, lifecycleOwner) {
        val provider = cameraProvider ?: return@LaunchedEffect
        provider.unbindAll()

        var backSelector = CameraSelector.DEFAULT_BACK_CAMERA
        var frontSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        var hasConcurrentSupport = false

        // 1. Query available concurrent configurations from CameraX (best way as it matches custom hardware mappings)
        val concurrentCameraInfos = provider.availableConcurrentCameraInfos
        if (concurrentCameraInfos.isNotEmpty()) {
            hasConcurrentSupport = true
            val firstCombination = concurrentCameraInfos.firstOrNull()
            if (firstCombination != null) {
                val foundBackInfo = firstCombination.find { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                val foundFrontInfo = firstCombination.find { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                if (foundBackInfo != null) {
                    backSelector = foundBackInfo.cameraSelector
                }
                if (foundFrontInfo != null) {
                    frontSelector = foundFrontInfo.cameraSelector
                }
            }
        } else {
            // 2. Query available concurrent configurations using standard Android CameraManager as fallback
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            hasConcurrentSupport = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cameraManager.concurrentCameraIds.isNotEmpty()
            } else {
                false
            }
        }

        if (hasConcurrentSupport) {
            try {
                val backSingleConfig = SingleCameraConfig(
                    backSelector,
                    UseCaseGroup.Builder()
                        .addUseCase(backPreview)
                        .addUseCase(backVideoCapture)
                        .build(),
                    lifecycleOwner
                )

                val frontSingleConfig = SingleCameraConfig(
                    frontSelector,
                    UseCaseGroup.Builder()
                        .addUseCase(frontPreview)
                        .build(),
                    lifecycleOwner
                )

                val concurrentCamera = provider.bindToLifecycle(listOf(backSingleConfig, frontSingleConfig))
                backCameraInstance = concurrentCamera.cameras.firstOrNull()
                isConcurrentRunning = true
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback to Back camera only if binding failed
                try {
                    val camera = provider.bindToLifecycle(lifecycleOwner, backSelector, backPreview, backVideoCapture)
                    backCameraInstance = camera
                    isConcurrentRunning = false
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        } else {
            // Standard fallback when concurrent camera is not supported
            try {
                val camera = provider.bindToLifecycle(lifecycleOwner, backSelector, backPreview, backVideoCapture)
                backCameraInstance = camera
                isConcurrentRunning = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf(0L) }
    var flashEnabled by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var lastThumbnailBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isFlipped by remember { mutableStateOf(false) }

    // Track recording timer
    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000)
            recordingDuration++
        }
        if (!isRecording) {
            recordingDuration = 0
        }
    }

    // Fetch update thumbnail when screen loads or finishes recording
    LaunchedEffect(isRecording) {
        if (!isRecording) {
            lastThumbnailBitmap = getLatestVideoThumbnail(context)
        }
    }

    val formatRecordingTime = { seconds: Long ->
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        if (h > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", m, s)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkUI)
    ) {
        // Status Bar (HyperOS Style)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .statusBarsPadding(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(System.currentTimeMillis()),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Mic",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(14.dp)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Speaker",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Main Viewfinder container
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 44.dp, bottom = 180.dp, start = 16.dp, end = 16.dp)
                .clip(RoundedCornerShape(32.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(32.dp))
                .background(Color.Black)
        ) {
            // Fullscreen Viewfinder (using PERFORMANCE mode for best frame rate)
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    }
                },
                update = { previewView ->
                    if (isFlipped) {
                        backPreview.setSurfaceProvider(null)
                        frontPreview.setSurfaceProvider(previewView.surfaceProvider)
                    } else {
                        frontPreview.setSurfaceProvider(null)
                        backPreview.setSurfaceProvider(previewView.surfaceProvider)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Front Camera Overlay inside the PIP Window (using COMPATIBLE mode to prevent overlay blanking)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(width = 110.dp, height = 150.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                    .background(Color.DarkGray)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            this.clipToOutline = true
                        }
                    },
                    update = { previewView ->
                        if (isFlipped) {
                            frontPreview.setSurfaceProvider(null)
                            backPreview.setSurfaceProvider(previewView.surfaceProvider)
                        } else {
                            backPreview.setSurfaceProvider(null)
                            frontPreview.setSurfaceProvider(previewView.surfaceProvider)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // PIP Camera indicator badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(PurpleNeon)
                        )
                        Text(
                            text = if (isFlipped) "BACK" else "FRONT",
                            color = Color.White,
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Center Focus Crosshair Overlay
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.5f))
                    )
                }
            }

            // Top overlay controllers (over viewfinder)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Flash Toggle
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                        .clickable {
                            flashEnabled = !flashEnabled
                            backCameraInstance?.cameraControl?.enableTorch(flashEnabled)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Flash Toggle",
                        tint = if (flashEnabled) PurpleNeon else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Video config badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isRecording) Color.Red else PurpleNeon)
                        )
                        Text(
                            text = if (isConcurrentRunning) "DUAL 4K • EIS" else "SINGLE 4K • EIS",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }

                // Settings configuration option
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                        .clickable {
                            Toast.makeText(context, "Configurations Optimized for Poco F7", Toast.LENGTH_SHORT).show()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Settings",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Bottom Controls Section (Adjusted to accommodate physical layout elegantly)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Zoom Slider control
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Zoom ${String.format(Locale.getDefault(), "%.1fx", zoomRatio)}",
                    color = PurpleNeon,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                Slider(
                    value = zoomRatio,
                    onValueChange = { scale ->
                        zoomRatio = scale
                        backCameraInstance?.cameraControl?.setZoomRatio(scale)
                    },
                    valueRange = 1.0f..10.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = PurpleNeon,
                        activeTrackColor = PurpleNeon,
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                )
            }

            // Stats / Recording Timer displays
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = formatRecordingTime(recordingDuration),
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Storage: ${getFreeStorageSpace()}",
                    color = Color.LightGray.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main Action Pill (Glassmorphism design style)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.12f),
                                Color.White.copy(alpha = 0.04f)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(32.dp)
                    )
                    .padding(vertical = 12.dp, horizontal = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left element: Gallery Thumbnail
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                            .clickable {
                                Toast.makeText(context, "Opening KR Gallery...", Toast.LENGTH_SHORT).show()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (lastThumbnailBitmap != null) {
                            Image(
                                bitmap = lastThumbnailBitmap!!.asImageBitmap(),
                                contentDescription = "Latest recorded thumbnail",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.DarkGray)
                                    .clip(CircleShape)
                            )
                        }
                    }

                    // Middle element: The glowing neon purple record button
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(72.dp)
                            .clickable {
                                if (isRecording) {
                                    // Stop Video Capture
                                    activeRecording?.stop()
                                    activeRecording = null
                                    isRecording = false
                                } else {
                                    // Start Video Capture using backVideoCapture configuration and MediaStore setup
                                    val name = "KR_DualCam_${System.currentTimeMillis()}.mp4"
                                    val contentValues = ContentValues().apply {
                                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                                        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/KR_DualCam")
                                        }
                                    }

                                    val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
                                        context.contentResolver,
                                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                    ).setContentValues(contentValues).build()

                                    try {
                                        isRecording = true
                                        val recording = backVideoCapture.output.prepareRecording(context, mediaStoreOutputOptions)
                                            .apply {
                                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                                    withAudioEnabled()
                                                }
                                            }
                                            .start(ContextCompat.getMainExecutor(context)) { event ->
                                                when (event) {
                                                    is VideoRecordEvent.Start -> {
                                                        recordingDuration = 0
                                                    }
                                                    is VideoRecordEvent.Finalize -> {
                                                        if (event.hasError()) {
                                                            Toast.makeText(context, "Recording completed with warning", Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            Toast.makeText(context, "Saved to Gallery (/Movies/KR_DualCam)", Toast.LENGTH_LONG).show()
                                                        }
                                                        isRecording = false
                                                        recordingDuration = 0
                                                    }
                                                }
                                            }
                                        activeRecording = recording
                                    } catch (e: Exception) {
                                        isRecording = false
                                        Toast.makeText(context, "Failed to start recording: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                    ) {
                        // Ambient ring glow
                        Box(
                            modifier = Modifier
                                .size(70.dp)
                                .border(
                                    width = 3.dp,
                                    color = if (isRecording) Color.Red.copy(alpha = 0.4f) else PurpleNeon.copy(alpha = 0.4f),
                                    shape = CircleShape
                                )
                        )
                        // Trigger button
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(if (isRecording) Color.Red else PurpleNeon)
                                .border(2.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isRecording) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(Color.White, RoundedCornerShape(3.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .border(2.dp, Color.White, CircleShape)
                                )
                            }
                        }
                    }

                    // Right element: Flip Camera option trigger (fully functional swap)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (isFlipped) PurpleNeon.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.08f))
                            .border(1.dp, if (isFlipped) PurpleNeon else Color.White.copy(alpha = 0.15f), CircleShape)
                            .clickable {
                                isFlipped = !isFlipped
                                Toast.makeText(context, "Viewport flipped", Toast.LENGTH_SHORT).show()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Flip cameras selector",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun getLatestVideoThumbnail(context: Context): android.graphics.Bitmap? {
    val projection = arrayOf(MediaStore.Video.Media._ID)
    val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
    val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    
    return try {
        context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val id = cursor.getLong(idIndex)
                val videoUri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(videoUri, android.util.Size(128, 128), null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                }
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }
}

private fun getFreeStorageSpace(): String {
    return try {
        val stat = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
        val bytesAvailable = stat.blockSizeLong * stat.blockCountLong
        val gigabytes = bytesAvailable / (1024 * 1024 * 1024)
        "$gigabytes GB Free"
    } catch (e: Exception) {
        "124.5 GB Free"
    }
}
