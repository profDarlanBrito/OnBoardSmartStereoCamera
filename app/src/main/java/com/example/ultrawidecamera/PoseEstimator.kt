package com.example.ultrawidecamera

import android.util.Log
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.AKAZE
import org.opencv.features2d.DescriptorMatcher
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import org.opencv.android.Utils
import org.opencv.core.Mat

data class ExtrinsicPose(
    val tx: Float, val ty: Float, val tz: Float,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float
)

class PoseEstimator {

    companion object {
        init {
            if (!org.opencv.android.OpenCVLoader.initDebug()) {
                Log.e("PoseEstimator", "Falha Crítica: OpenCV não foi carregado.")
            } else {
                Log.i("PoseEstimator", "OpenCV Inicializado com Sucesso.")
            }
        }
    }

    fun estimateRelativePose(
        context: Context,
        imagePath1: String, imagePath2: String,
        fx1: Double, fy1: Double, cx1: Double, cy1: Double, k1_1: Double, k2_1: Double, p1_1: Double, p2_1: Double,
        fx2: Double, fy2: Double, cx2: Double, cy2: Double, k1_2: Double, k2_2: Double, p1_2: Double, p2_2: Double
    ): ExtrinsicPose? {

        val img1Raw = Mat()
        val img2Raw = Mat()

        try {
            // Decodifica URI 1 para Mat nativo
            context.contentResolver.openInputStream(Uri.parse(imagePath1))?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                val tempMat = Mat()
                Utils.bitmapToMat(bitmap, tempMat)
                Imgproc.cvtColor(tempMat, img1Raw, Imgproc.COLOR_RGB2GRAY)
                tempMat.release()
                bitmap.recycle()
            }

            // Decodifica URI 2 para Mat nativo
            context.contentResolver.openInputStream(Uri.parse(imagePath2))?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                val tempMat = Mat()
                Utils.bitmapToMat(bitmap, tempMat)
                Imgproc.cvtColor(tempMat, img2Raw, Imgproc.COLOR_RGB2GRAY)
                tempMat.release()
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e("PoseEstimator", "Falha de I/O ao decodificar URIs do Android", e)
            return null
        }

        if (img1Raw.empty() || img2Raw.empty()) {
            Log.e("PoseEstimator", "ABORTADO: Imagens nativas vazias após conversão de Bitmap. Paths: $imagePath1 | $imagePath2")
            img1Raw.release(); img2Raw.release()
            return null
        }

        try {
            val cameraMatrix1 = org.opencv.core.Mat(3, 3, org.opencv.core.CvType.CV_64F)
            cameraMatrix1.put(0, 0, fx1, 0.0, cx1)
            cameraMatrix1.put(1, 0, 0.0, fy1, cy1)
            cameraMatrix1.put(2, 0, 0.0, 0.0, 1.0)

            val distCoeffs1 = org.opencv.core.Mat(1, 4, org.opencv.core.CvType.CV_64F)
            distCoeffs1.put(0, 0, k1_1, k2_1, p1_1, p2_1)

            val cameraMatrix2 = org.opencv.core.Mat(3, 3, org.opencv.core.CvType.CV_64F)
            cameraMatrix2.put(0, 0, fx2, 0.0, cx2)
            cameraMatrix2.put(1, 0, 0.0, fy2, cy2)
            cameraMatrix2.put(2, 0, 0.0, 0.0, 1.0)

            val distCoeffs2 = org.opencv.core.Mat(1, 4, org.opencv.core.CvType.CV_64F)
            distCoeffs2.put(0, 0, k1_2, k2_2, p1_2, p2_2)

            val img1 = org.opencv.core.Mat()
            val img2 = org.opencv.core.Mat()

            // passa a própria cameraMatrix como newCameraMatrix para manter a escala original
            org.opencv.calib3d.Calib3d.undistort(img1Raw, img1, cameraMatrix1, distCoeffs1, cameraMatrix1)
            org.opencv.calib3d.Calib3d.undistort(img2Raw, img2, cameraMatrix2, distCoeffs2, cameraMatrix2)

            val detector = org.opencv.features2d.AKAZE.create()
            val keypoints1 = MatOfKeyPoint(); val descriptors1 = org.opencv.core.Mat()
            val keypoints2 = MatOfKeyPoint(); val descriptors2 = org.opencv.core.Mat()

            detector.detectAndCompute(img1, org.opencv.core.Mat(), keypoints1, descriptors1)
            detector.detectAndCompute(img2, org.opencv.core.Mat(), keypoints2, descriptors2)

            if (descriptors1.empty() || descriptors2.empty()) return null

            val matcher = org.opencv.features2d.DescriptorMatcher.create(org.opencv.features2d.DescriptorMatcher.BRUTEFORCE_HAMMING)
            val matches = MatOfDMatch()
            matcher.match(descriptors1, descriptors2, matches)

            val matchesList = matches.toList().sortedBy { it.distance }

            val goodMatches = matchesList.take((matchesList.size * 0.4).toInt())
            if (goodMatches.size < 5) return null

            val kp1List = keypoints1.toList()
            val kp2List = keypoints2.toList()
            val pts1 = mutableListOf<Point>()
            val pts2 = mutableListOf<Point>()

            for (match in goodMatches) {
                pts1.add(kp1List[match.queryIdx].pt)
                pts2.add(kp2List[match.trainIdx].pt)
            }

            val matPts1 = MatOfPoint2f().apply { fromList(pts1) }
            val matPts2 = MatOfPoint2f().apply { fromList(pts2) }

            val essentialMat: org.opencv.core.Mat = Calib3d.findEssentialMat(
                matPts1, matPts2,
                cameraMatrix1, Calib3d.RANSAC, 0.999, 3.0
            )

            if (essentialMat.nativeObj == 0L || essentialMat.rows() != 3) {
                essentialMat.release(); cameraMatrix1.release(); cameraMatrix2.release()
                return null
            }

            val rMat = org.opencv.core.Mat()
            val tMat = org.opencv.core.Mat()
            val mask = org.opencv.core.Mat()

            Calib3d.recoverPose(essentialMat, matPts1, matPts2, cameraMatrix1, rMat, tMat, mask)

            val tx = tMat.get(0, 0)[0].toFloat()
            val ty = tMat.get(1, 0)[0].toFloat()
            val tz = tMat.get(2, 0)[0].toFloat()
            val quaternion = rotationMatrixToQuaternion(rMat)

            img1Raw.release(); img2Raw.release(); img1.release(); img2.release()
            essentialMat.release(); rMat.release(); tMat.release(); mask.release()
            cameraMatrix1.release(); cameraMatrix2.release(); distCoeffs1.release(); distCoeffs2.release()

            return ExtrinsicPose(tx, ty, tz, quaternion[1], quaternion[2], quaternion[3], quaternion[0])

        } catch (e: Exception) {
            img1Raw.release(); img2Raw.release()
            return null
        }
    }

    private fun rotationMatrixToQuaternion(r: org.opencv.core.Mat): FloatArray {
        val m00 = r.get(0, 0)[0].toFloat(); val m01 = r.get(0, 1)[0].toFloat(); val m02 = r.get(0, 2)[0].toFloat()
        val m10 = r.get(1, 0)[0].toFloat(); val m11 = r.get(1, 1)[0].toFloat(); val m12 = r.get(1, 2)[0].toFloat()
        val m20 = r.get(2, 0)[0].toFloat(); val m21 = r.get(2, 1)[0].toFloat(); val m22 = r.get(2, 2)[0].toFloat()

        val tr = m00 + m11 + m22
        var qw: Float; var qx: Float; var qy: Float; var qz: Float

        if (tr > 0) {
            val s = sqrt(tr + 1.0f) * 2f
            qw = 0.25f * s
            qx = (m21 - m12) / s
            qy = (m02 - m20) / s
            qz = (m10 - m01) / s
        } else if ((m00 > m11) && (m00 > m22)) {
            val s = sqrt(1.0f + m00 - m11 - m22) * 2f
            qw = (m21 - m12) / s
            qx = 0.25f * s
            qy = (m01 + m10) / s
            qz = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1.0f + m11 - m00 - m22) * 2f
            qw = (m02 - m20) / s
            qx = (m01 + m10) / s
            qy = 0.25f * s
            qz = (m12 + m21) / s
        } else {
            val s = sqrt(1.0f + m22 - m00 - m11) * 2f
            qw = (m10 - m01) / s
            qx = (m02 + m20) / s
            qy = (m12 + m21) / s
            qz = 0.25f * s
        }
        return floatArrayOf(qw, qx, qy, qz)
    }
}