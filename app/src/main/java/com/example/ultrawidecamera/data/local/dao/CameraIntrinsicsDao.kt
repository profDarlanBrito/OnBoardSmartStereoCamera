package com.example.ultrawidecamera.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.ultrawidecamera.data.local.entity.CameraIntrinsicsEntity

@Dao
interface CameraIntrinsicsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cameraIntrinsics: CameraIntrinsicsEntity)

    @Query("SELECT * FROM camera_intrinsics WHERE cameraId = :cameraId")
    suspend fun getProfileById(cameraId: String): CameraIntrinsicsEntity?

}