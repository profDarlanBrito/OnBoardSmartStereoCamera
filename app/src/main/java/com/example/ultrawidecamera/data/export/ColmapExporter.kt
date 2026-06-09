package com.example.ultrawidecamera.data.export

import android.content.Context
import android.os.Environment
import com.example.ultrawidecamera.data.repository.CameraRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter

class ColmapExporter (
    private val context: Context,
    private val cameraRepository: CameraRepository
) {
    suspend fun exportSessionToColmap(sessionName: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Busca os dados
            val images = cameraRepository.getImagesBySession(sessionName)
            if (images.isEmpty()) {
                return@withContext Result.failure(Exception("Nenhuma imagem encontrada para a sessão: $sessionName"))
            }

            // Escreve em Device Explorer e pasta Downloads
            val internalDir = context.filesDir
            val publicDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

            if (publicDir == null) {
                return@withContext Result.failure(Exception("Não foi possível acessar o armazenamento público de Downloads."))
            }

            val internalCamerasFile = File(internalDir, "cameras.txt")
            val internalImagesFile = File(internalDir, "images.txt")

            val publicCamerasFile = File(publicDir, "cameras.txt")
            val publicImagesFile = File(publicDir, "images.txt")

            // cameras.txt
            val uniqueCameraIds = images.map { it.cameraIdFk }.distinct()

            PrintWriter(FileOutputStream(internalCamerasFile)).use { internalWriter ->
                PrintWriter(FileOutputStream(publicCamerasFile)).use { publicWriter ->

                    val header = """
                        # Camera list with geometry of images.
                        # Number of cameras: ${uniqueCameraIds.size}
                        # Formato: CAMERA_ID MODEL WIDTH HEIGHT PARAMS[]
                    """.trimIndent()

                    internalWriter.println(header)
                    publicWriter.println(header)

                    for (cameraId in uniqueCameraIds) {
                        val profile = cameraRepository.getProfileById(cameraId)
                        if (profile != null) {
                            val line = "${profile.cameraId} ${profile.cameraModel} ${profile.width} ${profile.height} " +
                                    "${profile.fx} ${profile.fy} ${profile.cx} ${profile.cy} " +
                                    "${profile.k1} ${profile.k2} ${profile.p1} ${profile.p2}"

                            internalWriter.println(line)
                            publicWriter.println(line)
                        }
                    }
                }
            }

            // images.txt
            PrintWriter(FileOutputStream(internalImagesFile)).use { internalWriter ->
                PrintWriter(FileOutputStream(publicImagesFile)).use { publicWriter ->

                    val header = """
                        # Image list with poses and keypoints.
                        # Number of images: ${images.size}
                        # Formato por par de linhas: IMAGE_ID QW QX QY QZ TX TY TZ CAMERA_ID NAME
                    """.trimIndent()

                    internalWriter.println(header)
                    publicWriter.println(header)

                    images.forEachIndexed { index, imgEntity ->
                        val fileName = imgEntity.fileName

                        val line1 = "${index + 1} 1.0 0.0 0.0 0.0 0.0 0.0 0.0 ${imgEntity.cameraIdFk} $fileName"
                        val line2 = ""

                        internalWriter.println(line1)
                        internalWriter.println(line2)

                        publicWriter.println(line1)
                        publicWriter.println(line2)
                    }
                }
            }

            Result.success("Arquivos exportados com sucesso para: ${publicDir.absolutePath}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}