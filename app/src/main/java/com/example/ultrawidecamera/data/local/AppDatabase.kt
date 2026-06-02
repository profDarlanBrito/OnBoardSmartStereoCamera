package com.example.ultrawidecamera.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.ultrawidecamera.data.local.dao.CameraIntrinsicsDao
import com.example.ultrawidecamera.data.local.dao.CapturedImageDao
import com.example.ultrawidecamera.data.local.entity.CameraIntrinsicsEntity
import com.example.ultrawidecamera.data.local.entity.CapturedImageEntity

@Database(entities = [CameraIntrinsicsEntity::class, CapturedImageEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun cameraIntrinsicsDao(): CameraIntrinsicsDao
    abstract fun capturedImageDao(): CapturedImageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "colmap_camera_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}