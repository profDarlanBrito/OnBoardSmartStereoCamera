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
    val cameraIdFk: String
)