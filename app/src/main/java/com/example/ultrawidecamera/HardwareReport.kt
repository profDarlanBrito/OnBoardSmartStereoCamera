package com.example.ultrawidecamera
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

data class HardwareReport(
    val deviceName: String = android.os.Build.MODEL,
    val hasUltraWide: Boolean = false,
    val isLogicalMultiCamera: Boolean = false,
    val normalFocalLength: Float? = null,
    val ultraWideFocalLength: Float? = null,
    val failureReason: String? = null
)

class HardwareScanner(private val context: Context) {

    @RequiresApi(Build.VERSION_CODES.P)
    fun generateDualCameraReport(): HardwareReport {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            val cameraIds = cameraManager.cameraIdList
            val backCameras = cameraIds.filter { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }

            if (backCameras.isEmpty()) {
                return HardwareReport(failureReason = "Dispositivo sem câmera traseira.")
            }

            val mainBackCameraId = backCameras.first() // Geralmente o ID "0"
            val mainChars = cameraManager.getCameraCharacteristics(mainBackCameraId)

            val capabilities = mainChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val isLogical = capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true

            if (isLogical) {
                val physicalIds = mainChars.physicalCameraIds
                Log.i("HardwareScanner", "Logical Multi-Camera detectada. Lentes embutidas: $physicalIds")

                // Se tem mais de um ID físico, ele suporta
                if (physicalIds.size > 1) {
                    return HardwareReport(
                        hasUltraWide = true,
                        isLogicalMultiCamera = true,
                        failureReason = null
                    )
                }
            }

            var foundNormal = false
            var foundUw = false
            var normalFocal = 0f
            var uwFocal = 0f

            for (id in backCameras) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val mainFocal = focalLengths?.firstOrNull() ?: continue

                // Geralmente uma UW tem distância focal menor que 3.0mm
                if (mainFocal < 3.0f) {
                    foundUw = true
                    uwFocal = mainFocal
                    Log.i("HardwareScanner", "ID $id é uma Ultra-wide física explícita (Focal: $uwFocal)")
                } else {
                    foundNormal = true
                    normalFocal = mainFocal
                    Log.i("HardwareScanner", "ID $id é a Normal (Focal: $normalFocal)")
                }
            }

            if (foundNormal && foundUw) {
                return HardwareReport(
                    hasUltraWide = true,
                    isLogicalMultiCamera = false,
                    normalFocalLength = normalFocal,
                    ultraWideFocalLength = uwFocal,
                    failureReason = "Possui as lentes separadas, mas será lento pois não é Logical Camera."
                )
            }

            return HardwareReport(
                hasUltraWide = false,
                isLogicalMultiCamera = false,
                failureReason = "Hardware não possui ou não expõe a lente Ultra-Wide via API do sistema."
            )

        } catch (e: Exception) {
            Log.e("HardwareScanner", "Falha catastrófica ao acessar Camera HAL", e)
            return HardwareReport(failureReason = "Erro ao acessar HAL: ${e.message}")
        }
    }
}