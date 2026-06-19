package com.silverbp.android.ui.sync

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.silverbp.android.R
import com.silverbp.android.sync.pairing.QrPairingPayload
import com.silverbp.android.ui.components.AppTopBar
import com.silverbp.android.ui.components.ExpressivePrimaryButton
import com.silverbp.android.ui.components.ExpressiveSecondaryButton
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import java.util.concurrent.Executors

/**
 * Top-level pairing screen. Drives the [PairingViewModel] state machine
 * and renders one of: picker, show-QR, scan-QR, confirm-SAS, done, error.
 *
 * Mirrors iOS `SyncPairingSheet` behaviour. Camera permission is requested
 * lazily when the user picks "Scan QR" — the rest of the flow needs no
 * runtime permissions (NSD multicast is granted at install).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: PairingViewModel = viewModel(
        factory = PairingViewModel.Factory(context),
    )
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.pairing_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.pairing_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                is PairingViewModel.State.Picker -> PickerStage(
                    onShowQr = vm::onShowQrTapped,
                    onScan = vm::onScanQrTapped,
                )
                is PairingViewModel.State.ShowingQr -> ShowQrStage(
                    qrUrl = s.qrUrl,
                    status = s.statusText,
                )
                is PairingViewModel.State.Scanning -> ScanQrStage(
                    onScanned = vm::onScanned,
                )
                is PairingViewModel.State.ConfirmingSas -> ConfirmSasStage(
                    outcome = s.outcome,
                    asJoiner = s.asJoiner,
                    onResult = vm::onConfirmSas,
                )
                is PairingViewModel.State.Syncing -> SyncingStage(peerDeviceId = s.peerDeviceId)
                is PairingViewModel.State.Done -> DoneStage(
                    peerDeviceId = s.peerDeviceId,
                    syncedCount = s.syncedCount,
                    onClose = onBack,
                )
                is PairingViewModel.State.Error -> ErrorStage(
                    message = s.message,
                    onRetry = vm::onDismissError,
                )
            }
        }
    }
}

// MARK: - stages

@Composable
private fun PickerStage(
    onShowQr: () -> Unit,
    onScan: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.screenH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StandardCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.height(AppSpacing.sectionGap))
                Text(
                    text = stringResource(R.string.pairing_subtitle),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(AppSpacing.itemGap))
                Text(
                    text = stringResource(R.string.pairing_wifi_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(AppSpacing.sectionGap))
        ExpressivePrimaryButton(
            text = stringResource(R.string.pairing_show_qr),
            onClick = onShowQr,
            icon = Icons.Default.QrCode,
            fillWidth = true,
        )
        Spacer(Modifier.height(AppSpacing.itemGap))
        ExpressiveSecondaryButton(
            text = stringResource(R.string.pairing_scan_qr),
            onClick = onScan,
            icon = Icons.Default.QrCodeScanner,
            fillWidth = true,
        )
    }
}

@Composable
private fun ShowQrStage(qrUrl: String, status: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.screenH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StandardCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.pairing_scan_instruction),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(AppSpacing.sectionGap))
                QrImage(text = qrUrl, sizeDp = 240)
                Spacer(Modifier.height(AppSpacing.sectionGap))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ScanQrStage(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }
    if (!hasPermission) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppSpacing.screenH),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            StandardCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.pairing_camera_permission),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(AppSpacing.sectionGap))
                    ExpressivePrimaryButton(
                        text = stringResource(R.string.pairing_grant_permission),
                        onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    )
                }
            }
        }
        return
    }
    QrCameraPreview(onScanned = onScanned)
}

@Composable
private fun ConfirmSasStage(
    outcome: com.silverbp.android.sync.pairing.PairingService.HandshakeOutcome,
    asJoiner: Boolean,
    onResult: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.screenH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StandardCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (asJoiner) stringResource(R.string.pairing_verify_code_joiner) else stringResource(R.string.pairing_verify_code_host),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(AppSpacing.sectionGap))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = outcome.sas,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(AppSpacing.sectionGap))
                Text(
                    text = stringResource(R.string.pairing_mitm_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(AppSpacing.itemGap))
                Text(
                    text = stringResource(R.string.pairing_peer, outcome.peerDeviceId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Spacer(Modifier.height(AppSpacing.sectionGap))
        Row(modifier = Modifier.fillMaxWidth()) {
            ExpressiveSecondaryButton(
                text = stringResource(R.string.pairing_numbers_differ),
                onClick = { onResult(false) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(AppSpacing.itemGap))
            ExpressivePrimaryButton(
                text = stringResource(R.string.pairing_numbers_match),
                onClick = { onResult(true) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SyncingStage(peerDeviceId: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.screenH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(AppSpacing.sectionGap))
        Text(
            stringResource(R.string.pairing_syncing),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(AppSpacing.itemGap))
        Text(
            peerDeviceId,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun DoneStage(peerDeviceId: String, syncedCount: Int, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.screenH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StandardCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Done,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.height(AppSpacing.itemGap))
                Text(
                    stringResource(R.string.pairing_complete),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(AppSpacing.tight))
                Text(
                    if (syncedCount > 0) stringResource(R.string.pairing_synced_count, syncedCount) else stringResource(R.string.pairing_already_synced),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(AppSpacing.itemGap))
                Text(
                    peerDeviceId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(AppSpacing.sectionGap))
        ExpressivePrimaryButton(
            text = stringResource(R.string.pairing_done),
            onClick = onClose,
        )
    }
}

@Composable
private fun ErrorStage(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.screenH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StandardCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(AppSpacing.sectionGap))
                ExpressivePrimaryButton(
                    text = stringResource(R.string.pairing_retry),
                    onClick = onRetry,
                )
            }
        }
    }
}

// MARK: - QR rendering

@Composable
private fun QrImage(text: String, sizeDp: Int) {
    val bitmap = remember(text, sizeDp) {
        renderQrBitmap(text = text, size = sizeDp * 4)
    }
    Image(
        painter = BitmapPainter(bitmap.asImageBitmap()),
        contentDescription = stringResource(R.string.pairing_qr_cd),
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(AppSpacing.cardCorner))
            .background(androidx.compose.ui.graphics.Color.White)
            .padding(8.dp),
    )
}

private fun renderQrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            pixels[y * w + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

// MARK: - Camera preview + barcode analyzer

@Composable
private fun QrCameraPreview(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    var emitted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()
                val preview = CameraPreview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { proxy ->
                    if (emitted) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    analyseProxy(proxy, scanner) { url ->
                        if (!emitted &&
                            url.startsWith("${QrPairingPayload.SCHEME}://${QrPairingPayload.HOST}")
                        ) {
                            emitted = true
                            onScanned(url)
                        }
                    }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }
    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun analyseProxy(
    proxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onResult: (String) -> Unit,
) {
    val mediaImage = proxy.image
    if (mediaImage == null) {
        proxy.close()
        return
    }
    val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            barcodes.firstOrNull()?.rawValue?.let(onResult)
        }
        .addOnCompleteListener { proxy.close() }
}
