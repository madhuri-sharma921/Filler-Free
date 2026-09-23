package com.androidengineers.agent_quickstart_android.fillerfree.camera

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.EyeContactState
import com.androidengineers.agent_quickstart_android.fillerfree.domain.usecase.DetectEyeContactUseCase
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges CameraX frames -> ML Kit on-device face detection -> the pure
 * [DetectEyeContactUseCase]. Everything here runs on-device; frames are
 * handed to ML Kit's local model and never leave the process, nothing is
 * written to disk, and no frame is retained past its own analysis pass.
 *
 * Owned and closed by [com.androidengineers.agent_quickstart_android.fillerfree.presentation.FillerFreeViewModel];
 * call [close] when the session ends or the toggle is switched off so the
 * underlying ML Kit detector releases its native resources.
 */
class EyeContactAnalyzer : ImageAnalysis.Analyzer {

    private val detectEyeContact = DetectEyeContactUseCase()

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .build()
    )

    private val _eyeContactState = MutableStateFlow(EyeContactState.NO_FACE)
    val eyeContactState: StateFlow<EyeContactState> = _eyeContactState.asStateFlow()

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                _eyeContactState.value = detectEyeContact(
                    headEulerAngleY = primaryFace?.headEulerAngleY,
                    headEulerAngleZ = primaryFace?.headEulerAngleZ ?: 0f,
                )
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "face_detection_failed", error)
            }
            .addOnCompleteListener {
                // Must close every frame we're handed, success or failure,
                // or CameraX stalls the analysis pipeline waiting for it back.
                imageProxy.close()
            }
    }

    fun reset() {
        detectEyeContact.reset()
        _eyeContactState.value = EyeContactState.NO_FACE
    }

    fun close() {
        faceDetector.close()
    }

    companion object {
        private const val TAG = "EyeContactAnalyzer"
    }
}