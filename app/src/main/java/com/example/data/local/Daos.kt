package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ManifestDao {
    @Query("SELECT * FROM cached_manifests WHERE spaceId = :spaceId LIMIT 1")
    suspend fun getManifest(spaceId: String): CachedManifestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertManifest(entity: CachedManifestEntity)

    @Query("DELETE FROM cached_manifests WHERE spaceId = :spaceId")
    suspend fun deleteManifest(spaceId: String)
}

@Dao
interface SpaceRecordDao {
    @Query("SELECT * FROM space_records WHERE isFavorite = 1 ORDER BY lastOpenedAtMs DESC")
    fun observeFavorites(): Flow<List<SpaceRecordEntity>>

    @Query("SELECT * FROM space_records ORDER BY lastOpenedAtMs DESC LIMIT 40")
    fun observeRecents(): Flow<List<SpaceRecordEntity>>

    @Query("SELECT * FROM space_records WHERE spaceId = :spaceId LIMIT 1")
    suspend fun getRecord(spaceId: String): SpaceRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecord(record: SpaceRecordEntity)

    @Query("UPDATE space_records SET isFavorite = :isFavorite WHERE spaceId = :spaceId")
    suspend fun setFavorite(spaceId: String, isFavorite: Boolean)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM space_profiles WHERE spaceId = :spaceId LIMIT 1")
    fun observeProfile(spaceId: String): Flow<SpaceProfileEntity?>

    @Query("SELECT * FROM space_profiles WHERE spaceId = :spaceId LIMIT 1")
    suspend fun getProfile(spaceId: String): SpaceProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: SpaceProfileEntity)

    @Query("DELETE FROM space_profiles WHERE spaceId = :spaceId")
    suspend fun deleteProfile(spaceId: String)

    @Query("SELECT * FROM space_presets WHERE spaceId = :spaceId ORDER BY createdAtMs DESC")
    fun observePresets(spaceId: String): Flow<List<SpacePresetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreset(preset: SpacePresetEntity)

    @Query("DELETE FROM space_presets WHERE id = :id")
    suspend fun deletePreset(id: String)
}

@Dao
interface JobRecordDao {
    @Query("SELECT * FROM job_records ORDER BY startedAtMs DESC LIMIT 100")
    fun observeAllJobs(): Flow<List<JobRecordEntity>>

    @Query("SELECT * FROM job_records WHERE spaceId = :spaceId ORDER BY startedAtMs DESC LIMIT 20")
    fun observeJobsForSpace(spaceId: String): Flow<List<JobRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJob(job: JobRecordEntity)

    @Query("UPDATE job_records SET status = 'DISCONNECTED', finishedAtMs = :nowMs, errorMessage = 'Session interrupted by app restart. Not auto-resubmitted.' WHERE status IN ('UPLOADING', 'QUEUED', 'RUNNING', 'STREAMING')")
    suspend fun markInterruptedJobsDisconnected(nowMs: Long)

    @Query("DELETE FROM job_records WHERE jobId = :jobId")
    suspend fun deleteJob(jobId: String)
}

@Dao
interface MediaAssetDao {
    @Query("SELECT * FROM media_assets ORDER BY createdAtMs DESC")
    fun observeAllAssets(): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets WHERE mediaType = :mediaType ORDER BY createdAtMs DESC")
    fun observeByType(mediaType: String): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MediaAssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAsset(asset: MediaAssetEntity)

    @Query("DELETE FROM media_assets WHERE id = :id")
    suspend fun deleteAsset(id: String)
}
