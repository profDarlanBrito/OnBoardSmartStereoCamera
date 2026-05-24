package com.example.ultrawidecamera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

data class CameraUiState(
    val isCameraReady: Boolean = false,
    val currentZoomRatio: Float = 1.0f,
    val minZoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 1.0f,
    val isUltraWideAvailable: Boolean = false,
    val useUltraWideLens: Boolean = false,
    val lastCapturedUri: String? = null,
    val captureError: String? = null
)

class CameraViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var photoRepository: PhotoRepository? = null

    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentSurfaceProvider: Preview.SurfaceProvider? = null

    fun initializeCamera(context: Context, lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        currentLifecycleOwner = lifecycleOwner
        currentSurfaceProvider = surfaceProvider
        
        if (photoRepository == null) {
            photoRepository = PhotoRepository(context.applicationContext)
        }
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: run {
            Log.w("CameraViewModel", "Tentativa de bind abortada: CameraProvider nulo.")
            return
        }
        val lifecycleOwner = currentLifecycleOwner ?: return
        val surfaceProvider = currentSurfaceProvider ?: return
        
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val cameraSelector = getCameraSelector(uiState.value.useUltraWideLens)

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )

            observeZoomState()
            _uiState.update { currentState ->
                currentState.copy(isCameraReady = true)
            }

        } catch (e: Exception) {
            Log.e("CameraViewModel", "Use case binding failed", e)
            _uiState.update { it.copy(captureError = "Erro ao iniciar sensor: ${e.message}") }
        }
    }

    private fun getCameraSelector(useUltraWide: Boolean): CameraSelector {
        if (!useUltraWide) return CameraSelector.DEFAULT_BACK_CAMERA

        return CameraSelector.Builder()
            .addCameraFilter { cameraInfos ->
                val backCameras = cameraInfos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                
                val ultraWideByIntrinsic = backCameras.filter { it.intrinsicZoomRatio < 1.0f }
                if (ultraWideByIntrinsic.isNotEmpty()) {
                    return@addCameraFilter ultraWideByIntrinsic
                }

                val ultraWideByFocalLength = backCameras.filter { info ->
                    isUltraWideByFocalLength(info)
                }

                ultraWideByFocalLength.ifEmpty {
                    listOf(backCameras.firstOrNull() ?: cameraInfos.first())
                }
            }
            .build()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun isUltraWideByFocalLength(info: CameraInfo): Boolean {
        val camera2Info = Camera2CameraInfo.from(info)
        val focalLengths = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
        )
        val focalLength = focalLengths?.getOrNull(0) ?: 5.0f
        return focalLength < 3.0f
    }

    private fun observeZoomState() {
        camera?.cameraInfo?.zoomState?.observeForever { state ->
            _uiState.update { 
                it.copy(
                    currentZoomRatio = state.zoomRatio,
                    minZoomRatio = state.minZoomRatio,
                    maxZoomRatio = state.maxZoomRatio
                )
            }
        }
    }

    private fun checkUltraWideCapability(): Boolean {
        if (uiState.value.minZoomRatio < 1.0f) return true

        val provider = cameraProvider ?: return false
        val backCameras = provider.availableCameraInfos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        
        return backCameras.any { info ->
            info.intrinsicZoomRatio < 1.0f || isUltraWideByFocalLength(info)
        }
    }

    fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    fun toggleLens() {
        val newState = !uiState.value.useUltraWideLens
        _uiState.update { it.copy(useUltraWideLens = newState) }
        
        if (newState && uiState.value.minZoomRatio < 1.0f) {
            setZoom(uiState.value.minZoomRatio)
        } else if (!newState && uiState.value.minZoomRatio < 1.0f) {
            setZoom(1.0f)
        } else {
            bindCameraUseCases()
        }
    }

    fun takePhoto() {
        viewModelScope.launch {
            try {
                // Determine if we need to switch physical cameras
                val needsPhysicalSwitch = uiState.value.isUltraWideAvailable && uiState.value.minZoomRatio >= 1.0f
                
                // 1. Capture 1x Photo
                if (needsPhysicalSwitch && uiState.value.useUltraWideLens) {
                    _uiState.update { it.copy(useUltraWideLens = false) }
                    bindCameraUseCases()
                    delay(800)
                }
                camera?.cameraControl?.setZoomRatio(1.0f)
                delay(300) 
                val uri1 = capturePhotoInternal("_1x")
                Log.d("CameraViewModel", "1x photo captured: $uri1")

                // 2. Capture Ultra-Wide Photo (Smallest zoom)
                if (uiState.value.isUltraWideAvailable) {
                    if (needsPhysicalSwitch && !uiState.value.useUltraWideLens) {
                        _uiState.update { it.copy(useUltraWideLens = true) }
                        bindCameraUseCases()
                        delay(600)
                    }
                    
                    val minZoom = uiState.value.minZoomRatio
                    camera?.cameraControl?.setZoomRatio(minZoom)
                    delay(50)
                    val uri2 = capturePhotoInternal("_uw")
                    Log.d("CameraViewModel", "Ultra-wide photo captured: $uri2")
                    
                    val finalUri = uri2 ?: uri1
                    if (finalUri != null) {
                        _uiState.update { it.copy(lastCapturedUri = finalUri, captureError = null) }
                    }
                } else {
                    uri1?.let { uri ->
                        _uiState.update { it.copy(lastCapturedUri = uri, captureError = null) }
                    }
                }
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Dual capture failed", e)
                _uiState.update { it.copy(captureError = "Capture failed: ${e.message}") }
            }
        }
    }

    private suspend fun capturePhotoInternal(suffix: String): String? = suspendCancellableCoroutine { continuation ->
        val capture = imageCapture ?: run { 
            continuation.resume(null)
            return@suspendCancellableCoroutine 
        }
        val repo = photoRepository ?: run { 
            continuation.resume(null)
            return@suspendCancellableCoroutine 
        }

        repo.takePhoto(
            imageCapture = capture,
            suffix = suffix,
            onImageSaved = { uri -> continuation.resume(uri) },
            onError = { exc -> 
                Log.e("CameraViewModel", "Internal capture failed", exc)
                continuation.resume(null)
            }
        )
    }

    fun clearCaptureStatus() {
        _uiState.update { it.copy(lastCapturedUri = null, captureError = null) }
    }

    override fun onCleared() {
        super.onCleared()
        cameraExecutor.shutdown()
    }
}
