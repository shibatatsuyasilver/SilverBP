package com.silverbp.android.ui.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.capture.CaptureSessionHolder
import com.silverbp.android.core.Source
import com.silverbp.android.ui.confirm.BpReadingDraft
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

@Composable
fun CaptureScreen(
    onCaptured: (String) -> Unit,
    onManualEntry: () -> Unit,
    onClose: () -> Unit,
    vm: CaptureFlowViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val phase by vm.phase.collectAsStateWithLifecycle()

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
    ) { uri ->
        if (uri != null) {
            vm.loadFromUri(uri) { onCaptured("draft") }
        }
    }

    val previewView = remember { PreviewView(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    if (hasPermission) {
        LaunchedEffect(previewView) {
            val provider = ProcessCameraProvider.getInstance(context).get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder().build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture
                )
                imageCapture = capture
            } catch (e: Exception) {
                Log.e("CaptureScreen", "bind failed", e)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            FrameOverlay(modifier = Modifier.fillMaxSize())
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.camera_permission_title), color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.camera_permission_body), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(16.dp))
                Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.retry))
                }
            }
        }

        // Phase overlay
        when (val p = phase) {
            CapturePhase.Recognizing -> Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.recognizing), color = Color.White)
                }
            }
            is CapturePhase.Error -> Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(p.message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { vm.setCapturing() }) { Text(stringResource(R.string.retry)) }
                        Button(onClick = { onCaptured("draft") }) { Text(stringResource(R.string.manual_entry)) }
                    }
                }
            }
            else -> Unit
        }

        // Top close button
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) { Icon(Icons.Filled.Close, null, tint = Color.White) }

        // Bottom controls
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(onClick = {
                pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Icon(Icons.Filled.Image, null) }

            FilledIconButton(
                onClick = {
                    val capture = imageCapture ?: return@FilledIconButton
                    capturePhoto(context, capture) { bmp ->
                        if (bmp != null) {
                            vm.processCapturedImage(bmp) { onCaptured("draft") }
                        } else {
                            // failure already surfaced by VM via state; fall through to manual
                            CaptureSessionHolder.put(BpReadingDraft(timestamp = Instant.now(), source = Source.Manual))
                            onCaptured("draft")
                        }
                    }
                },
                modifier = Modifier.size(72.dp),
            ) { Icon(Icons.Filled.RadioButtonChecked, null, modifier = Modifier.size(48.dp)) }

            FilledIconButton(onClick = onManualEntry) { Icon(Icons.Outlined.Edit, null) }
        }
    }
}

@Composable
private fun FrameOverlay(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier.alpha(0.65f)) {
        val w = size.width; val h = size.height
        val rectW = w * 0.78f; val rectH = h * 0.32f
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
    capture.takePicture(
        output,
        Executors.newSingleThreadExecutor(),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                val bmp = com.silverbp.android.recognition.decodeFileWithExif(outFile)
                outFile.delete()
                onResult(bmp)
            }
            override fun onError(exception: ImageCaptureException) {
                Log.e("CaptureScreen", "capture failed", exception)
                onResult(null)
            }
        }
    )
}
