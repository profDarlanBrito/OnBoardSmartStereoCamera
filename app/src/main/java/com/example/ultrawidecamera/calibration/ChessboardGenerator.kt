package com.example.ultrawidecamera.calibration

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import androidx.core.graphics.createBitmap

object ChessboardGenerator {

    // resolução A4 Portrait a 300 DPI
    private const val A4_WIDTH_300DPI = 2480
    private const val A4_HEIGHT_300DPI = 3508

    fun generateA4ChessboardBitmap(
        innerCornersX: Int = 6,
        innerCornersY: Int = 8,
        marginMm: Float = 10f
    ): Bitmap {
        val squareCols = innerCornersX + 1
        val squareRows = innerCornersY + 1

        val bitmap = createBitmap(A4_WIDTH_300DPI, A4_HEIGHT_300DPI)
        val canvas = Canvas(bitmap)

        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = false
        }

        val marginPxX = ((marginMm / 25.4f) * 300f).toInt()
        val marginPxY = ((marginMm / 25.4f) * 300f).toInt()

        val usableWidth = A4_WIDTH_300DPI - (2 * marginPxX)
        val usableHeight = A4_HEIGHT_300DPI - (2 * marginPxY)

        val squareSizeX = usableWidth / squareCols
        val squareSizeY = usableHeight / squareRows
        val squareSizePx = minOf(squareSizeX, squareSizeY)

        val boardWidth = squareCols * squareSizePx
        val boardHeight = squareRows * squareSizePx
        val startX = (A4_WIDTH_300DPI - boardWidth) / 2
        val startY = (A4_HEIGHT_300DPI - boardHeight) / 2

        for (r in 0 until squareRows) {
            for (c in 0 until squareCols) {
                if ((r + c) % 2 == 0) {
                    val x = (startX + (c * squareSizePx)).toFloat()
                    val y = (startY + (r * squareSizePx)).toFloat()
                    canvas.drawRect(x, y, x + squareSizePx, y + squareSizePx, paint)
                }
            }
        }

        return bitmap
    }

    fun exportToPdf(context: Context, bitmap: Bitmap, fileName: String = "tabuleiro_calibracao_a4.pdf"): Boolean {
        val pdfDocument = PdfDocument()

        val pageWidthPt = 595
        val pageHeightPt = 842

        val pageInfo = PdfDocument.PageInfo.Builder(pageWidthPt, pageHeightPt, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        val srcRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
        val dstRect = android.graphics.RectF(0f, 0f, pageWidthPt.toFloat(), pageHeightPt.toFloat())

        val paint = Paint().apply { isFilterBitmap = true }
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)

        pdfDocument.finishPage(page)

        var success = false
        try {
            val outputStream: OutputStream? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let { resolver.openOutputStream(it) }
            } else {
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                file.parentFile?.mkdirs()
                FileOutputStream(file)
            }

            outputStream?.use { out ->
                pdfDocument.writeTo(out)
                success = true
            }
        } catch (e: Exception) {
            android.util.Log.e("ChessboardGenerator", "Falha de I/O ao salvar PDF no dispositivo: ${e.message}", e)
            success = false
        } finally {
            pdfDocument.close()
        }

        return success
    }
}