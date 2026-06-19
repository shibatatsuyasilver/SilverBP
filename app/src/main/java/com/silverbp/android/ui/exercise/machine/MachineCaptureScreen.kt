package com.silverbp.android.ui.exercise.machine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.recognition.decodeFileWithExif
import com.silverbp.android.ui.components.ModelLoadBanner
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.PillShape
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Live in-app camera capture for a gym-machine console — mirrors the BP
 * [com.silverbp.android.ui.capture.CaptureScreen]: a full-screen CameraX
 * preview with a dashed framing guide and a shutter button, so the user aims at
 * the console and shoots directly (and the OCR gets a full-resolution photo).
 * The captured bitmap is fed to [MachineCaptureViewModel.analyzeBitmap]; on
 * success/failure [onAnalyzed] navigates to [MachineConfirmScreen].
 */
@Composable
fun MachineCaptureScreen(
    onAnalyzed: () -> Unit,
    onBack: () -> Unit,
    vm: MachineCaptureViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val capture by vm.capturePhase.collectAsStateWithLifecycle()
    val readiness by vm.readiness.collectAsStateWithLifecycle()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA)
    }

    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.analyzeUri(uri, onAnalyzed) }

    val previewView = remember { PreviewView(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val cameraProviderState = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    if (hasPermission) {
        LaunchedEffect(previewView) {
            val provider = ProcessCameraProvider.getInstance(context).get()
            cameraProviderState.value = provider
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val cap = ImageCapture.Builder().build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, cap
                )
                imageCapture = cap
            } catch (e: Exception) {
                Log.e("MachineCapture", "[Capture] bind failed", e)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            cameraProviderState.value?.unbindAll()
        }
    }

    val showCameraControls = capture !is MachineCapturePhase.Analyzing

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            FrameOverlay(modifier = Modifier.fillMaxSize())
            // Framing hint pill near the top.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp, start = AppSpacing.screenH, end = AppSpacing.screenH)
                    .clip(PillShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = AppSpacing.cardPadding, vertical = AppSpacing.itemGap),
            ) {
                Text(
                    stringResource(R.string.machine_capture_hint),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(AppSpacing.screenH),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.camera_permission_title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.size(AppSpacing.itemGap))
                Text(
                    stringResource(R.string.camera_permission_body),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(AppSpacing.sectionGap))
                Button(
                    onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
                    shape = PillShape,
                ) {
                    Text(stringResource(R.string.retry))
                }
            }
        }

        // Phase overlay.
        when (val p = capture) {
            MachineCapturePhase.Analyzing -> Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.size(AppSpacing.itemGap))
                    Text(stringResource(R.string.machine_analyzing), color = Color.White)
                }
            }
            is MachineCapturePhase.Error -> Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.machine_analyze_failed),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.size(AppSpacing.sectionGap))
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                        OutlinedButton(
                            onClick = { vm.discardPendingDraft(); vm.resetCapture() },
                            shape = PillShape,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                        Button(onClick = { vm.resetCapture(); onAnalyzed() }, shape = PillShape) {
                            Text(stringResource(R.string.machine_manual_entry))
                        }
                    }
                }
            }
            else -> Unit
        }

        if (showCameraControls) {
            CameraTopBar(
                onClose = { vm.discardPendingDraft(); onBack() },
                onPickPhoto = {
                    pickPhoto.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                pickEnabled = readiness.ready,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            // No local model loaded → block recognition (shutter + gallery
            // disabled above); point the user to Settings / manual entry.
            if (readiness.showModelBanner) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 112.dp),
                ) {
                    ModelLoadBanner(phase = readiness.phase)
                    Text(
                        stringResource(R.string.capture_model_needed_hint),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = AppSpacing.sectionGap),
                    )
                }
            }

            if (hasPermission) {
                ShutterButton(
                    onClick = {
                        val cap = imageCapture ?: return@ShutterButton
                        capturePhoto(context, cap) { bmp ->
                            if (bmp != null) {
                                vm.analyzeBitmap(bmp, onAnalyzed)
                            } else {
                                // Capture failed before any draft was staged — drop any
                                // stale draft so the confirm form opens blank, not from
                                // an earlier analysis.
                                vm.discardPendingDraft()
                                onAnalyzed()
                            }
                        }
                    },
                    enabled = readiness.ready,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
                )
            }
        }
    }
}

@Composable
private fun CameraTopBar(
    onClose: () -> Unit,
    onPickPhoto: () -> Unit,
    pickEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = AppSpacing.itemGap, vertical = AppSpacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onClose,
            shape = PillShape,
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        ) {
            Text(stringResource(R.string.cancel))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onPickPhoto, enabled = pickEnabled) {
            Icon(
                Icons.Filled.Image,
                contentDescription = stringResource(R.string.pick_from_library),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun ShutterButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    // 72-dp white circle with a thin inner ring — matches the BP CaptureScreen shutter.
    Box(
        modifier = modifier
            .size(72.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(Color.White)
            .border(BorderStroke(2.dp, Color.Black.copy(alpha = 0.25f)), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color.White)
                .border(BorderStroke(1.dp, Color.Black.copy(alpha = 0.2f)), CircleShape),
        )
    }
}

@Composable
private fun FrameOverlay(modifier: Modifier = Modifier) {
    // Wide dashed rectangle to frame a cardio console (wider than the BP LCD frame).
    androidx.compose.foundation.Canvas(modifier = modifier.alpha(0.65f)) {
        val w = size.width; val h = size.height
        val rectW = w * 0.84f; val rectH = h * 0.40f
        val left = (w - rectW) / 2f; val top = (h - rectH) / 2f
        val effect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(20f, 16f))
        drawRect(
            color = Color.White,
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(rectW, rectH),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f, pathEffect = effect),
        )
    }
}

private fun capturePhoto(
    context: Context,
    capture: ImageCapture,
    onResult: (Bitmap?) -> Unit,
) {
    val cacheDir = File(context.cacheDir, "capture").apply { mkdirs() }
    val outFile = File(cacheDir, "${UUID.randomUUID()}.jpg")
    val output = ImageCapture.OutputFileOptions.Builder(outFile).build()
    // Decode on a per-shot worker; deliver the final callback on main for navigation.
    val executor = Executors.newSingleThreadExecutor()
    val mainExecutor = ContextCompat.getMainExecutor(context)
    fun finish(bmp: Bitmap?) {
        executor.shutdown()
        mainExecutor.execute { onResult(bmp) }
    }
    capture.takePicture(
        output,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                val bmp = try {
                    decodeFileWithExif(outFile)
                } catch (t: Throwable) {
                    Log.e("MachineCapture", "[Capture] decode failed", t)
                    null
                } finally {
                    outFile.delete()
                }
                finish(bmp)
            }
            override fun onError(exception: ImageCaptureException) {
                Log.e("MachineCapture", "[Capture] capture failed", exception)
                finish(null)
            }
        }
    )
}
