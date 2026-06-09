package com.example.ultrawidecamera.data.repository

import com.example.ultrawidecamera.data.local.dao.CameraIntrinsicsDao
import com.example.ultrawidecamera.data.local.dao.CapturedImageDao
import com.example.ultrawidecamera.data.local.entity.CameraIntrinsicsEntity
import com.example.ultrawidecamera.data.local.entity.CapturedImageEntity

class CameraRepository (
    private val cameraIntrinsicsDao: CameraIntrinsicsDao,
    private val capturedImageDao: CapturedImageDao
) {

    suspend fun saveCameraProfile(
        cameraId: String,
        cameraModel: String,
        width: Int,
        height: Int,
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double,
        k1: Double,
        k2: Double,
        p1: Double,
        p2: Double
    ) {
        val camera = CameraIntrinsicsEntity(
            cameraId = cameraId,
            cameraModel = cameraModel,
            width = width,
            height = height,
            fx = fx,
            fy = fy,
            cx = cx,
            cy = cy,
            k1 = k1,
            k2 = k2,
            p1 = p1,
            p2 = p2
        )
        cameraIntrinsicsDao.insert(camera)
    }

    suspend fun getProfileById(cameraId: String): CameraIntrinsicsEntity? {
        return cameraIntrinsicsDao.getProfileById(cameraId)
    }

    suspend fun saveCapturedImage(
        imagePathUri: String,
        fileName: String,
        sessionName: String,
        timestamp: Long,
        cameraId: String
    ) {
        val image = CapturedImageEntity(
            imagePathUri = imagePathUri,
            fileName = fileName,
            sessionName = sessionName,
            timestamp = timestamp,
            cameraIdFk = cameraId
        )
        capturedImageDao.insert(image)
    }

    suspend fun removeCapturedImage(image: CapturedImageEntity) {
        capturedImageDao.delete(image)
    }

    suspend fun getImagesBySession(session: String): List<CapturedImageEntity> {
        return capturedImageDao.getImagesBySession(session)
    }
}