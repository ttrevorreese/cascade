package com.cascade.alarm.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
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
import com.cascade.alarm.data.challenge.PhotoHash
import com.cascade.alarm.engine.EngineLog
import kotlinx.coroutines.awaitCancellation
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Holds the bound capture use case so a button can take a picture. */
class PhotoCameraState {
    internal var capture: ImageCapture? = null

    /** An upright photo scaled to at most [MAX_SIDE] px, or null if the camera could not take one. */
    suspend fun take(context: Context): Bitmap? {
        val capture = capture ?: return null
        val image = suspendCancellableCoroutine<ImageProxy?> { cont ->
            capture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) = cont.resume(image)
                    override fun onError(exception: ImageCaptureException) {
                        EngineLog.e("photo capture failed", exception)
                        cont.resume(null)
                    }
                },
            )
        } ?: return null
        return image.use { upright(it.toBitmap(), it.imageInfo.rotationDegrees) }
    }

    private fun upright(bitmap: Bitmap, degrees: Int): Bitmap {
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(degrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    companion object {
        const val MAX_SIDE = 1280
    }
}

/**
 * Back camera preview for the photo-match challenge. [onUnavailable] is called if the camera
 * cannot be opened or never reaches the open state, so the caller can fall back (SPEC §6.8).
 */
@Composable
fun PhotoCamera(state: PhotoCameraState, onUnavailable: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val unavailable by rememberUpdatedState(onUnavailable)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }

    LaunchedEffect(lifecycleOwner) {
        val preview = Preview.Builder().build().apply { setSurfaceProvider { surfaceRequest = it } }
        val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        var provider: ProcessCameraProvider? = null
        try {
            provider = ProcessCameraProvider.awaitInstance(context)
            val camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            state.capture = capture
            // A camera that never opens must not leave the user stuck in front of a black box.
            delay(OPEN_TIMEOUT_MILLIS)
            if (camera.cameraInfo.cameraState.value?.type != CameraState.Type.OPEN) {
                EngineLog.e("camera did not open within ${OPEN_TIMEOUT_MILLIS / 1000}s")
                unavailable()
            }
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
            state.capture = null
            provider?.unbind(preview, capture)
        }
    }

    Box(modifier) {
        surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it) }
    }
}

/** Generous: CameraX can take several seconds to start, longer on emulators. */
private const val OPEN_TIMEOUT_MILLIS = 10_000L

/** Brightness grids for [PhotoHash], averaged rather than point-sampled so noise washes out. */
object PhotoGrids {
    /** The reference: the photo's central [PhotoHash.REFERENCE_CROP], as a [PhotoHash.GRID] grid. */
    fun reference(photo: Bitmap): IntArray {
        val crop = PhotoHash.REFERENCE_CROP
        val (w, h) = (photo.width * crop).roundToInt() to (photo.height * crop).roundToInt()
        val centre = Bitmap.createBitmap(photo, (photo.width - w) / 2, (photo.height - h) / 2, w, h)
        return grid(centre, PhotoHash.GRID)
    }

    /** A new photo, whole, as a [PhotoHash.SEARCH] grid. */
    fun candidate(photo: Bitmap): IntArray = grid(photo, PhotoHash.SEARCH)

    private fun grid(bitmap: Bitmap, n: Int): IntArray {
        val sub = 4
        val small = Bitmap.createScaledBitmap(bitmap, n * sub, n * sub, true)
        val pixels = IntArray(n * sub * n * sub).also { small.getPixels(it, 0, n * sub, 0, 0, n * sub, n * sub) }
        return IntArray(n * n) { cell ->
            val (cx, cy) = cell % n to cell / n
            var sum = 0
            for (y in 0 until sub) for (x in 0 until sub) {
                val p = pixels[(cy * sub + y) * n * sub + cx * sub + x]
                sum += (299 * Color.red(p) + 587 * Color.green(p) + 114 * Color.blue(p)) / 1000
            }
            sum / (sub * sub)
        }
    }
}
