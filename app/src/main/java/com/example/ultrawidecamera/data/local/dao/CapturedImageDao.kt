package com.example.ultrawidecamera.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.ultrawidecamera.data.local.entity.CapturedImageEntity

@Dao
interface CapturedImageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: CapturedImageEntity)

    @Delete
    suspend fun delete(image: CapturedImageEntity)

    @Query("SELECT * FROM captured_image WHERE sessionName = :session")
    suspend fun getImagesBySession(session: String): List<CapturedImageEntity>
}