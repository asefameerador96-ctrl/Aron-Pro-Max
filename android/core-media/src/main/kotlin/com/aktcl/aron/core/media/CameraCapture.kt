package com.aktcl.aron.core.media

import android.content.Context
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aktcl.aron.core.system.permission.GatedFeature
import com.aktcl.aron.core.system.permission.PermissionGate
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronInfoDialog
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/**
 * The bridge between a capture call ([takePicture], from a controller) and the camera screen ([CameraCaptureOverlay],
 * drawn once at the top of the app's composition). One request at a time; the camera exists only while a request is open.
 */
class CameraCaptureHost : CameraSource {
    private val pending = MutableStateFlow<CompletableDeferred<ByteArray?>?>(null)
    val request: StateFlow<CompletableDeferred<ByteArray?>?> = pending.asStateFlow()

    /** A capture that ended without a photo for a reason the rep must see (no space, could not save). */
    enum class Notice { NO_SPACE, FAILED }
    private val notices = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = notices.asStateFlow()
    fun show(n: Notice) { notices.value = n }
    fun dismissNotice() { notices.value = null }

    override suspend fun takePicture(): ByteArray? {
        val d = CompletableDeferred<ByteArray?>()
        if (!pending.compareAndSet(null, d)) throw CameraBusyException()
        try {
            return d.await()
        } finally {
            pending.compareAndSet(d, null)
        }
    }

    /** Completes [request] only if it is still the open one, so a late shot never lands in a newer request. */
    fun deliver(request: CompletableDeferred<ByteArray?>, jpeg: ByteArray) { if (pending.value === request) request.complete(jpeg) }
    fun cancel() { pending.value?.complete(null) }
}

/** A capture was asked for while the camera screen is already open (a double tap): ignored, not a failure. */
class CameraBusyException : IllegalStateException("a photo is already being taken")

object CameraTags {
    const val SCREEN = "camera_screen"
    const val SHUTTER = "camera_shutter"
    const val CANCEL = "camera_cancel"
}

/** Draw once, above the app's screens. Shows the camera only while a capture is requested, behind the camera permission gate. */
@Composable
fun CameraCaptureOverlay(host: CameraCaptureHost) {
    val req by host.request.collectAsState()
    val notice by host.notice.collectAsState()
    notice?.let { n ->
        AronInfoDialog(
            title = stringResource(R.string.media_photo_title),
            message = stringResource(if (n == CameraCaptureHost.Notice.NO_SPACE) R.string.media_no_space else R.string.media_capture_failed),
            okLabel = stringResource(R.string.media_ok),
            onDismiss = host::dismissNotice,
        )
    }
    val open = req ?: return
    BackHandler { host.cancel() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(CameraTags.SCREEN)) {
        PermissionGate(GatedFeature.PHOTO, onBack = host::cancel) {
            CameraStill(onShot = { host.deliver(open, it) }, onCancel = host::cancel)
        }
    }
}

/**
 * CameraX preview plus one still (docs/24 s5.5, docs/17 s8.6): about 1280 x 720, flash auto, the JPEG written to memory
 * (CameraX records the orientation in EXIF; the compressor applies it and drops all EXIF). The camera is unbound the
 * moment the still is taken and whenever the screen leaves, so it is never kept warm.
 */
@Composable
private fun CameraStill(onShot: (ByteArray) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var shotFailed by remember { mutableStateOf(false) }
    // Bumped after a failed shot: the camera was released, so it is bound again before the shutter comes back.
    var binding by remember { mutableStateOf(0) }

    DisposableEffect(owner, binding) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val p = future.get()
                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
                val preview = Preview.Builder().setResolutionSelector(selector).build().also { it.surfaceProvider = previewView.surfaceProvider }
                val still = ImageCapture.Builder().setResolutionSelector(selector).setFlashMode(ImageCapture.FLASH_MODE_AUTO)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, still)
                provider = p
                capture = still
            }.onFailure { failed = true }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            if (shotFailed && !failed) AronBanner(stringResource(R.string.media_capture_failed), Modifier.align(Alignment.TopCenter).padding(AronTokens.Space.M), kind = BannerKind.Warning)
            if (failed) AronBanner(stringResource(R.string.media_camera_unavailable), Modifier.align(Alignment.TopCenter).padding(AronTokens.Space.M), kind = BannerKind.Error)
        }
        // Solid controls (docs/32 s2a): the shutter is the primary action and stays legible in sunlight.
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            AronPrimaryButton(
                stringResource(R.string.media_take_photo),
                onClick = {
                    val still = capture ?: return@AronPrimaryButton
                    busy = true
                    takeStill(context, still, onDone = { bytes ->
                        provider?.unbindAll() // release the camera at once
                        capture = null
                        busy = false
                        if (bytes != null) onShot(bytes) else { shotFailed = true; binding++ }
                    })
                },
                modifier = Modifier.fillMaxWidth().testTag(CameraTags.SHUTTER),
                enabled = capture != null && !busy,
            )
            AronSecondaryButton(stringResource(R.string.media_cancel), onCancel, Modifier.fillMaxWidth().testTag(CameraTags.CANCEL))
        }
    }
}

private fun takeStill(context: Context, still: ImageCapture, onDone: (ByteArray?) -> Unit) {
    val out = ByteArrayOutputStream(2 * 1024 * 1024)
    still.takePicture(
        ImageCapture.OutputFileOptions.Builder(out).build(),
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) = onDone(out.toByteArray())
            override fun onError(exception: ImageCaptureException) = onDone(null)
        },
    )
}
