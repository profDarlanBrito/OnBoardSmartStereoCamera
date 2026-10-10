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
import com.example.ultrawidecamera.data.export.ColmapExporter
import com.example.ultrawidecamera.data.local.AppDatabase
import com.example.ultrawidecamera.data.repository.CameraRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

data class CameraUiState(
    val isCameraReady: Boolean = false,
    val currentZoomRatio: Float = 1.0f,
    val minZoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 1.0f,
    val isUltraWideAvailable: Boolean = false,
    val isLogicalCamera: Boolean = false,
    val useUltraWideLens: Boolean = false,
    val deviceModel: String = "",
    val normalFocalLength: String = "N/A",
    val ultraWideFocalLength: String = "N/A",
    val hardwareFailureReason: String? = null,
    val lastCapturedUri: String? = null,
    val captureError: String? = null
)

class CameraViewModel : ViewModel() {
    private var appContext: Context? = null // Adicione esta linha

    private var currentSessionName = "Sessao_${System.currentTimeMillis()}"

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var normalCameraIdFk: String = "0"
    private var ultraWideCameraIdFk: String = "2"

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var photoRepository: PhotoRepository? = null

    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentSurfaceProvider: Preview.SurfaceProvider? = null

    private var cameraRepository: CameraRepository? = null
    private var colmapExporter: ColmapExporter? = null
    private val dateFormatter = SimpleDateFormat("yyyy--MM-dd-HH-mm-ss-SSS", Locale.US)
    private val poseEstimator = PoseEstimator()

    fun initializeCamera(context: Context, lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        this.appContext = context.applicationContext
        currentLifecycleOwner = lifecycleOwner
        currentSurfaceProvider = surfaceProvider

        if (photoRepository == null) {
            photoRepository = PhotoRepository(context.applicationContext)
        }

        if (cameraRepository == null) {
            val db = AppDatabase.getDatabase(context)
            cameraRepository = CameraRepository(db.cameraIntrinsicsDao(), db.capturedImageDao())
            colmapExporter = ColmapExporter(context.applicationContext, cameraRepository!!)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val scanner = HardwareScanner(context.applicationContext)
            val report = scanner.generateDualCameraReport()

            viewModelScope.launch {
                try {
                    // 1. Persiste a Calibração REAL da Câmera Normal (Principal)
                    report.normalIntrinsics?.let { norm ->
                        normalCameraIdFk = norm.cameraId // Captura o ID real do sensor!
                        cameraRepository?.saveCameraProfile(
                            cameraId = norm.cameraId,
                            cameraModel = "OPENCV",
                            width = norm.width,
                            height = norm.height,
                            fx = norm.fx, fy = norm.fy, cx = norm.cx, cy = norm.cy,
                            k1 = norm.k1, k2 = norm.k2, p1 = norm.p1, p2 = norm.p2
                        )
                        Log.i("CameraViewModel", "Intrínsecos Reais da Normal gravados [ID: ${norm.cameraId}]")
                    }

                    // 2. Persiste a Calibração REAL da Câmera Ultra-Wide (Se exposta pela HAL)
                    if (report.hasUltraWide) {
                        report.ultraWideIntrinsics?.let { uw ->
                            ultraWideCameraIdFk = uw.cameraId // Captura o ID real da UW
                            cameraRepository?.saveCameraProfile(
                                cameraId = uw.cameraId,
                                cameraModel = "OPENCV",
                                width = uw.width,
                                height = uw.height,
                                fx = uw.fx, fy = uw.fy, cx = uw.cx, cy = uw.cy,
                                k1 = uw.k1, k2 = uw.k2, p1 = uw.p1, p2 = uw.p2
                            )
                            Log.i("CameraViewModel", "Intrínsecos Reais da UW gravados [ID: ${uw.cameraId}]")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CameraViewModel", "Falha relacional ao persistir intrínsecos: ${e.message}", e)
                }
            }

            _uiState.update {
                it.copy(
                    isUltraWideAvailable = report.hasUltraWide,
                    isLogicalCamera = report.isLogicalMultiCamera,
                    deviceModel = report.deviceName,
                    normalFocalLength = report.normalFocalLength?.let { "${it}mm" } ?: "Não detectada",
                    ultraWideFocalLength = report.ultraWideFocalLength?.let { "${it}mm" } ?: "Não detectada",
                    hardwareFailureReason = report.failureReason
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    isUltraWideAvailable = false,
                    isLogicalCamera = false
                )
            }
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
        viewModelScope.launch { // Executa na Main thread por padrão para gerenciar UI e CameraX de forma segura
            try {
                val needsPhysicalSwitch = uiState.value.isUltraWideAvailable && uiState.value.minZoomRatio >= 1.0f

                // --- ETAPA 1: CAPTURA DA LENTE NORMAL (A Âncora - Pose Zero) ---
                if (needsPhysicalSwitch && uiState.value.useUltraWideLens) {
                    _uiState.update { it.copy(useUltraWideLens = false) }
                    bindCameraUseCases()
                    kotlinx.coroutines.delay(400) // Tempo de respiro seguro para o ISP
                }

                // Ajustes de controle de câmera devem ocorrer na Main thread
                camera?.cameraControl?.setZoomRatio(1.0f)
                kotlinx.coroutines.delay(50)

                val uri1 = withContext(Dispatchers.IO) { capturePhotoInternal("_1x") }
                if (uri1 == null) throw Exception("Colapso de IO na Lente Normal.")

                val timestamp1 = System.currentTimeMillis()
                val fileName1 = dateFormatter.format(timestamp1) + "_1x.jpg"

                withContext(Dispatchers.IO) {
                    cameraRepository?.saveCapturedImage(
                        imagePathUri = uri1, fileName = fileName1, sessionName = currentSessionName,
                        timestamp = timestamp1, cameraId = normalCameraIdFk,
                        qx = 0.0f, qy = 0.0f, qz = 0.0f, qw = 1.0f, tx = 0.0f, ty = 0.0f, tz = 0.0f
                    )
                }

                // --- ETAPA 2: CAPTURA DA LENTE ULTRA-WIDE ---
                var uri2: String? = null
                if (uiState.value.isUltraWideAvailable) {
                    if (needsPhysicalSwitch && !uiState.value.useUltraWideLens) {
                        _uiState.update { it.copy(useUltraWideLens = true) }
                        bindCameraUseCases()
                        kotlinx.coroutines.delay(400) // Concessão de tempo adequada para troca de lente física
                    }

                    val minZoom = uiState.value.minZoomRatio
                    camera?.cameraControl?.setZoomRatio(minZoom)
                    kotlinx.coroutines.delay(100) // Debounce físico para estabilização óptica

                    uri2 = withContext(Dispatchers.IO) { capturePhotoInternal("_uw") }

                    if (uri2 != null) {
                        val timestamp2 = System.currentTimeMillis()
                        val fileName2 = dateFormatter.format(timestamp2) + "_uw.jpg"

                        // --- ETAPA 3: A MAGIA MATEMÁTICA ISOLADA EM BACKGROUND ---
                        val extrinsics = withContext(Dispatchers.IO) {
                            val normalProfile = cameraRepository?.getProfileById(normalCameraIdFk)
                            val uwProfile = cameraRepository?.getProfileById(ultraWideCameraIdFk)

                            val fx1 = normalProfile?.fx ?: 1500.0; val fy1 = normalProfile?.fy ?: 1500.0
                            val cx1 = normalProfile?.cx ?: 500.0;  val cy1 = normalProfile?.cy ?: 500.0
                            val k1_1 = normalProfile?.k1 ?: 0.0;   val k2_1 = normalProfile?.k2 ?: 0.0
                            val p1_1 = normalProfile?.p1 ?: 0.0;   val p2_1 = normalProfile?.p2 ?: 0.0

                            val fx2 = uwProfile?.fx ?: 1000.0;     val fy2 = uwProfile?.fy ?: 1000.0
                            val cx2 = uwProfile?.cx ?: 500.0;      val cy2 = uwProfile?.cy ?: 500.0
                            val k1_2 = uwProfile?.k1 ?: 0.0;       val k2_2 = uwProfile?.k2 ?: 0.0
                            val p1_2 = uwProfile?.p1 ?: 0.0;       val p2_2 = uwProfile?.p2 ?: 0.0

                            Log.i("CameraViewModel", "Resolvendo Estéreo. L1(fx=$fx1) vs L2(fx=$fx2)")

                            // Extração segura do contexto
                            val safeContext = appContext ?: throw IllegalStateException("Pipeline acionado antes da inicialização do contexto.")

                            val pose = poseEstimator.estimateRelativePose(
                                context = safeContext, // AQUI ENTRA O CONTEXTO
                                imagePath1 = uri1, imagePath2 = uri2!!,
                                fx1 = fx1, fy1 = fy1, cx1 = cx1, cy1 = cy1, k1_1 = k1_1, k2_1 = k2_1, p1_1 = p1_1, p2_1 = p2_1,
                                fx2 = fx2, fy2 = fy2, cx2 = cx2, cy2 = cy2, k1_2 = k1_2, k2_2 = k2_2, p1_2 = p1_2, p2_2 = p2_2
                            )

                            pose ?: ExtrinsicPose(0f, 0f, 0f, 0f, 0f, 0f, 1f)
                        }

                        withContext(Dispatchers.IO) {
                            cameraRepository?.saveCapturedImage(
                                imagePathUri = uri2, fileName = fileName2, sessionName = currentSessionName,
                                timestamp = timestamp2, cameraId = ultraWideCameraIdFk,
                                qx = extrinsics.qx, qy = extrinsics.qy, qz = extrinsics.qz, qw = extrinsics.qw,
                                tx = extrinsics.tx, ty = extrinsics.ty, tz = extrinsics.tz
                            )
                        }
                    }
                }

                val finalUri = uri2 ?: uri1
                if (finalUri != null) {
                    _uiState.update { it.copy(lastCapturedUri = finalUri, captureError = null) }
                }

                withContext(Dispatchers.IO) {
                    triggerColmapExport()
                }

            } catch (e: Exception) {
                Log.e("CameraViewModel", "Falha de captura dupla e extração de pose", e)
                _uiState.update { it.copy(captureError = "Pipeline em colapso: ${e.message}") }
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

    private fun triggerColmapExport() {
        viewModelScope.launch {
            colmapExporter?.exportSessionToColmap(currentSessionName)?.onSuccess { path ->
                Log.i("ColmapExport", "Sucesso brutal! Verifique no PC: $path")
            }?.onFailure { exception ->
                Log.e("ColmapExport", "Erro de escrita no arquivo", exception)
            }
        }
    }

    public fun setProjectSessionName(cleanName: String) {
        this.currentSessionName = cleanName
    }
}
