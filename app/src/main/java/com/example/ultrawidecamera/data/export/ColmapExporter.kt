package com.example.ultrawidecamera.data.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.ultrawidecamera.data.repository.CameraRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ColmapExporter(
    private val context: Context,
    private val cameraRepository: CameraRepository
) {
    suspend fun isProjectDirectoryOccupied(sessionName: String): Boolean = withContext(Dispatchers.IO) {
        if (sessionName.isBlank()) return@withContext false
        val cleanName = sessionName.trim().replace(" ", "_")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%${Environment.DIRECTORY_DOWNLOADS}/COLMAP_Export/$cleanName/%")

            try {
                resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
                    return@withContext cursor.count > 0
                }
            } catch (e: Exception) {
                Log.e("ColmapExporter", "Erro ao auditar MediaStore", e)
            }
            return@withContext false
        } else {
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "COLMAP_Export/$cleanName")
            return@withContext publicDir.exists() && (publicDir.list()?.isNotEmpty() == true)
        }
    }

    suspend fun exportSessionToColmap(sessionName: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanName = sessionName.trim().replace(" ", "_")
            val images = cameraRepository.getImagesBySession(cleanName)

            if (images.isEmpty()) {
                return@withContext Result.failure(Exception("Nenhuma imagem encontrada no banco para a sessão: $cleanName"))
            }

            val uniqueCameraIds = images.map { it.cameraIdFk }.distinct()

            val camerasContent = buildString {
                appendLine("# Camera list with geometry of images.")
                appendLine("# Number of cameras: ${uniqueCameraIds.size}")
                appendLine("# Formato: CAMERA_ID MODEL WIDTH HEIGHT PARAMS[]")
                for (cameraId in uniqueCameraIds) {
                    val profile = cameraRepository.getProfileById(cameraId)
                        ?: return@withContext Result.failure(Exception("Erro Crítico: Perfil intrínseco ausente para a câmera ID '$cameraId'."))
                    appendLine("${profile.cameraId} ${profile.cameraModel} ${profile.width} ${profile.height} ${profile.fx} ${profile.fy} ${profile.cx} ${profile.cy} ${profile.k1} ${profile.k2} ${profile.p1} ${profile.p2}")
                }
            }

            val imagesContent = buildString {
                appendLine("# Image list with poses and keypoints.")
                appendLine("# Number of images: ${images.size}")
                appendLine("# Formato por par de linhas: IMAGE_ID QW QX QY QZ TX TY TZ CAMERA_ID NAME")
                images.forEachIndexed { index, imgEntity ->
                    appendLine("${index + 1} ${imgEntity.qw} ${imgEntity.qx} ${imgEntity.qy} ${imgEntity.qz} ${imgEntity.tx} ${imgEntity.ty} ${imgEntity.tz} ${imgEntity.cameraIdFk} ${imgEntity.fileName}")
                    appendLine("")
                }
            }

            val internalDir = context.filesDir
            File(internalDir, "cameras.txt").writeText(camerasContent)
            File(internalDir, "images.txt").writeText(imagesContent)

            saveToPublicDownloads(cleanName, "cameras.txt", camerasContent)
            saveToPublicDownloads(cleanName, "images.txt", imagesContent)

            images.forEach { imgEntity ->
                try {
                    exportImageFileToProjectFolder(cleanName, imgEntity.imagePathUri, imgEntity.fileName)
                } catch (e: Exception) {
                    Log.e("ColmapExporter", "Falha ao exportar binário da imagem: ${imgEntity.fileName}", e)
                }
            }

            Result.success("Arquivos gravados com sucesso em: Downloads/COLMAP_Export/$cleanName")
        } catch (e: Exception) {
            Log.e("ColmapExporter", "Erro fatal na exportação de I/O", e)
            Result.failure(e)
        }
    }

    private fun saveToPublicDownloads(sessionName: String, fileName: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/COLMAP_Export/$sessionName"

            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val selectionArgs = arrayOf(fileName, relativePath + "/")

            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID), selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    throw IllegalStateException("O arquivo '$fileName' já existe na sessão '$sessionName'. Exportação abortada para manter integridade.")
                }
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray())
                }
            }
        } else {
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "COLMAP_Export/$sessionName")
            if (!publicDir.exists()) publicDir.mkdirs()
            val file = File(publicDir, fileName)
            if (file.exists()) {
                throw IllegalStateException("O arquivo '$fileName' já existe na sessão '$sessionName'.")
            }
            file.writeText(content)
        }
    }

    private suspend fun exportImageFileToProjectFolder(sessionName: String, imageUriString: String, fileName: String) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val imageUri = android.net.Uri.parse(imageUriString)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/COLMAP_Export/$sessionName"

            // Verifica se o arquivo físico já existe para evitar sufixos (1), (2)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val selectionArgs = arrayOf(fileName, "$relativePath/")
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID), selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) return@withContext
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }

            val destUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (destUri != null) {
                resolver.openInputStream(imageUri)?.use { input ->
                    resolver.openOutputStream(destUri)?.use { output ->
                        input.copyTo(output)
                    }
                }
            }
        } else {
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "COLMAP_Export/$sessionName")
            if (!publicDir.exists()) publicDir.mkdirs()
            val destFile = File(publicDir, fileName)
            if (destFile.exists()) return@withContext

            resolver.openInputStream(imageUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}