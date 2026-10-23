package com.cascade.alarm.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cascade.alarm.engine.EngineLog
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.awaitCancellation
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * Back camera preview that reports every barcode or QR code it reads, using ML Kit's bundled
 * model so it works offline and before first unlock (SPEC §6.8). [onUnavailable] is called if the
 * camera cannot be opened, so the caller can fall back rather than leave the user stuck.
 */
@Composable
fun BarcodeScanner(onScanned: (String) -> Unit, onUnavailable: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanned by rememberUpdatedState(onScanned)
    val unavailable by rememberUpdatedState(onUnavailable)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    val gotFrame = remember { AtomicBoolean(false) }

    // A camera can also fail by staying black without an error. No frame in time counts as
    // unavailable, so the caller's fallback runs instead of leaving the user scanning nothing.
    LaunchedEffect(Unit) {
        delay(FIRST_FRAME_TIMEOUT_MILLIS)
        if (!gotFrame.get()) {
            EngineLog.e("no camera frame within ${FIRST_FRAME_TIMEOUT_MILLIS / 1000}s")
            unavailable()
        }
    }

    LaunchedEffect(lifecycleOwner) {
        val scanner = BarcodeScanning.getClient()
        val executor = Executors.newSingleThreadExecutor()
        val preview = Preview.Builder().build().apply { setSurfaceProvider { surfaceRequest = it } }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { proxy ->
            gotFrame.set(true)
            val image = proxy.image
            if (image == null) {
                proxy.close()
                return@setAnalyzer
            }
            scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                // Listeners run on the main thread by default.
                .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let { scanned(it) } }
                .addOnCompleteListener { proxy.close() }
        }
        var provider: ProcessCameraProvider? = null
        try {
            provider = ProcessCameraProvider.awaitInstance(context)
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            awaitCancellation()
        } catch (e: CancellationException) {
            // Leaving the screen, not a camera failure. Some cancellations are also
            // IllegalStateExceptions, so this must come first.
            throw e
        } catch (e: IllegalArgumentException) {
            EngineLog.e("no usable back camera", e)
            unavailable()
        } catch (e: IllegalStateException) {
            EngineLog.e("camera could not be opened", e)
            unavailable()
        } finally {
            provider?.unbind(preview, analysis)
            scanner.close()
            executor.shutdown()
        }
    }

    Box(modifier) {
        surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it) }
    }
}

/** Generous: CameraX can take several seconds to start, longer on emulators. */
private const val FIRST_FRAME_TIMEOUT_MILLIS = 10_000L
