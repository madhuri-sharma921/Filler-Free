package com.androidengineers.agent_quickstart_android.fillerfree.camera

import android.util.Log
import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Full-bleed front-camera preview — this is the "video call" stage the rest
 * of the in-session screen is overlaid on top of. Binds both a [Preview]
 * (what the user sees, mirrored like every front-camera app) and an
 * [ImageAnalysis] (what [EyeContactAnalyzer] reads) to the same lifecycle in
 * a single `bindToLifecycle` call, since CameraX requires all concurrent
 * use cases to be bound together, not sequentially.
 *
 * Renders nothing (transparent Box) if [enabled] is false or camera
 * permission isn't granted — callers should show a fallback background
 * behind this composable for that case (see InSessionScreen).
 */
@Composable
fun EyeContactCameraPreview(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    hasCameraPermission: Boolean,
    analyzer: EyeContactAnalyzer,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    if (!enabled || !hasCameraPermission) {
        Box(modifier = modifier)
        return
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
    ) { previewView ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                runCatching {
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder()
                        .build()
                        .also { it.surfaceProvider = previewView.surfaceProvider }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(320, 240)) // small on purpose: we only need head-pose angles
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(ContextCompat.getMainExecutor(context), analyzer) }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                }.onFailure { error ->
                    Log.w("EyeContactCameraPreview", "camera_bind_failed", error)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    DisposableEffect(enabled, hasCameraPermission) {
        onDispose {
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
        }
    }
}