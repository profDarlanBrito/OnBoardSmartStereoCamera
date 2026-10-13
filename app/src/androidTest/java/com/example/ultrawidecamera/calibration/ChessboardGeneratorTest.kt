package com.example.ultrawidecamera.calibration

import android.Manifest
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChessboardGeneratorTest {

    @get:Rule
    val grantPermissionRule: GrantPermissionRule = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        GrantPermissionRule.grant(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    } else {
        GrantPermissionRule.grant()
    }

    @Test
    fun testGenerateAndExportChessboardPdf() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val bitmap = ChessboardGenerator.generateA4ChessboardBitmap(
            innerCornersX = 6,
            innerCornersY = 8,
            marginMm = 10f
        )

        assertTrue("O Bitmap não pode ser nulo", bitmap != null)
        assertTrue("A largura deve corresponder a 300 DPI (2480px)", bitmap.width == 2480)
        assertTrue("A altura deve corresponder a 300 DPI (3508px)", bitmap.height == 3508)

        // exportação para PDF no armazenamento
        val testFileName = "TESTE_TABULEIRO_${System.currentTimeMillis()}.pdf"
        val success = ChessboardGenerator.exportToPdf(context, bitmap, testFileName)

        bitmap.recycle()

        assertTrue("A exportação do PDF para a pasta Downloads falhou", success)
    }
}