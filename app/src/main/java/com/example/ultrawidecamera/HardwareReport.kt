package com.example.ultrawidecamera
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

data class HardwareReport(
    val deviceName: String = Build.MODEL,
    val hasUltraWide: Boolean = false,
    val isLogicalMultiCamera: Boolean = false,
    val hardwareLevel: String = "DESCONHECIDO",
    val normalFocalLength: Float? = null,
    val ultraWideFocalLength: Float? = null,
    val failureReason: String? = null,
    val normalIntrinsics: CameraIntrinsicsReport? = null,
    val ultraWideIntrinsics: CameraIntrinsicsReport? = null
)

data class CameraIntrinsicsReport(
    val cameraId: String,
    val width: Int,
    val height: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val k1: Double,
    val k2: Double,
    val p1: Double,
    val p2: Double
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

            val hwLevelInt = mainChars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val hwLevelStr = when (hwLevelInt) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY (Emulação)"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3 (Científico)"
                else -> "DESCONHECIDO"
            }

            val capabilities = mainChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val isLogical = capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true

            var normalReport: CameraIntrinsicsReport? = null
            var uwReport: CameraIntrinsicsReport? = null
            var normalFocal = 0f
            var uwFocal = 0f

            for (id in backCameras) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focal = focalLengths?.firstOrNull() ?: continue

                val intrinsics = extractIntrinsics(id, chars)

                if (focal < 3.0f) {
                    uwFocal = focal
                    uwReport = intrinsics
                    Log.i("HardwareScanner", "Lente UW detectada [ID: $id | Focal: ${focal}mm]")
                } else if (normalReport == null) {
                    normalFocal = focal
                    normalReport = intrinsics
                    Log.i("HardwareScanner", "Lente Normal detectada [ID: $id | Focal: ${focal}mm]")
                }
            }

            val hasUw = uwReport != null
            val failure = if (hwLevelInt == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                "Alerta: Hardware LEGACY. Dados ópticos podem sofrer emulação pelo driver."
            } else if (!hasUw) {
                "Hardware não possui ou não expõe a lente Ultra-Wide via API do sistema."
            } else null

            return HardwareReport(
                deviceName = Build.MODEL,
                hasUltraWide = hasUw,
                isLogicalMultiCamera = isLogical,
                hardwareLevel = hwLevelStr,
                normalFocalLength = if (normalReport != null) normalFocal else null,
                ultraWideFocalLength = if (hasUw) uwFocal else null,
                failureReason = failure,
                normalIntrinsics = normalReport,
                ultraWideIntrinsics = uwReport
            )

        } catch (e: Exception) {
            Log.e("HardwareScanner", "Falha de acesso à Camera HAL", e)
            return HardwareReport(failureReason = "Erro fatal de I/O na HAL: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun extractIntrinsics(id: String, chars: CameraCharacteristics): CameraIntrinsicsReport {
        val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val width = if (pixelArray != null && pixelArray.width > 0) pixelArray.width else 4000
        val height = if (pixelArray != null && pixelArray.height > 0) pixelArray.height else 3000

        val calibration = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val distortion = chars.get(CameraCharacteristics.LENS_DISTORTION)

        if (calibration != null && calibration.size >= 5 && calibration[0] > 0f && calibration[2] > 0f) {
            val fx = calibration[0].toDouble()
            val fy = calibration[1].toDouble()
            val cx = calibration[2].toDouble()
            val cy = calibration[3].toDouble()

            val k1 = if (distortion != null && distortion.size >= 1) distortion[0].toDouble() else 0.0
            val k2 = if (distortion != null && distortion.size >= 2) distortion[1].toDouble() else 0.0
            val p1 = if (distortion != null && distortion.size >= 4) distortion[3].toDouble() else 0.0
            val p2 = if (distortion != null && distortion.size >= 5) distortion[4].toDouble() else 0.0

            return CameraIntrinsicsReport(id, width, height, fx, fy, cx, cy, k1, k2, p1, p2)
        }

        val focalLengthMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 4.0f
        val sensorSizeMm = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

        val fxFallback: Double = if (sensorSizeMm != null && sensorSizeMm.width > 0) {
            (focalLengthMm.toDouble() / sensorSizeMm.width.toDouble()) * width.toDouble()
        } else {
            width.toDouble() * 0.8
        }

        val fyFallback: Double = if (sensorSizeMm != null && sensorSizeMm.height > 0) {
            (focalLengthMm.toDouble() / sensorSizeMm.height.toDouble()) * height.toDouble()
        } else {
            height.toDouble() * 0.8
        }

        val cxFallback = width / 2.0
        val cyFallback = height / 2.0

        Log.w("HardwareScanner", "Lente $id sem calibração nativa. Aplicando fallback trigonométrico em pixels.")
        return CameraIntrinsicsReport(id, width, height, fxFallback, fyFallback, cxFallback, cyFallback, 0.0, 0.0, 0.0, 0.0)
    }
}