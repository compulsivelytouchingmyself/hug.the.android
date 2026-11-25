package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedManifestEntity::class,
        SpaceRecordEntity::class,
        SpaceProfileEntity::class,
        SpacePresetEntity::class,
        JobRecordEntity::class,
        MediaAssetEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SpaceDeckDatabase : RoomDatabase() {
    abstract fun manifestDao(): ManifestDao
    abstract fun spaceRecordDao(): SpaceRecordDao
    abstract fun profileDao(): ProfileDao
    abstract fun jobRecordDao(): JobRecordDao
    abstract fun mediaAssetDao(): MediaAssetDao

    companion object {
        @Volatile
        private var INSTANCE: SpaceDeckDatabase? = null

        fun getInstance(context: Context): SpaceDeckDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SpaceDeckDatabase::class.java,
                    "spacedeck.db"
                ).fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
