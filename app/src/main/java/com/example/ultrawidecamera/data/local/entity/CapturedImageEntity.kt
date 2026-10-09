package com.example.ultrawidecamera.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "captured_image",
    foreignKeys = [
        ForeignKey(
            entity = CameraIntrinsicsEntity::class,
            parentColumns = ["cameraId"],
            childColumns = ["cameraIdFk"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("cameraIdFk")]
)
data class CapturedImageEntity (
    @PrimaryKey(autoGenerate = true) val imageId: Int = 0,
    val imagePathUri: String,
    val fileName: String,
    val sessionName: String,
    val timestamp: Long,
    val cameraIdFk: String,
    val qx: Float = 0.0f,
    val qy: Float = 0.0f,
    val qz: Float = 0.0f,
    val qw: Float = 1.0f,
    val tx: Float = 0.0f,
    val ty: Float = 0.0f,
    val tz: Float = 0.0f
)