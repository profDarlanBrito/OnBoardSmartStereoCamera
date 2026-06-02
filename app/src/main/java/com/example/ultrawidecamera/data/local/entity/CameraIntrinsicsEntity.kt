package com.example.ultrawidecamera.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "camera_intrinsics")
data class CameraIntrinsicsEntity (
    @PrimaryKey val cameraId: String,
    val cameraModel: String = "OPENCV",
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