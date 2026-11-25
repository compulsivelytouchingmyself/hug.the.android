package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_manifests")
data class CachedManifestEntity(
    @PrimaryKey val spaceId: String,
    val revisionSha: String,
    val runtimeStage: String,
    val manifestJson: String,
    val isManualOverride: Boolean,
    val updatedAtMs: Long
)

@Entity(tableName = "space_records")
data class SpaceRecordEntity(
    @PrimaryKey val spaceId: String,
    val author: String,
    val title: String,
    val emoji: String,
    val shortDescription: String,
    val hardware: String,
    val isZeroGpu: Boolean,
    val runtimeStage: String,
    val isFavorite: Boolean,
    val lastOpenedAtMs: Long
)

@Entity(tableName = "space_profiles")
data class SpaceProfileEntity(
    @PrimaryKey val spaceId: String,
    val profileJson: String,
    val updatedAtMs: Long
)

@Entity(tableName = "space_presets")
data class SpacePresetEntity(
    @PrimaryKey val id: String,
    val spaceId: String,
    val endpointId: String,
    val name: String,
    val valuesJson: String,
    val createdAtMs: Long
)

@Entity(tableName = "job_records")
data class JobRecordEntity(
    @PrimaryKey val jobId: String,
    val spaceId: String,
    val endpointLabel: String,
    val status: String,
    val transport: String,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val elapsedMs: Long,
    val declaredZeroGpuSeconds: Int?,
    val summaryText: String,
    val errorMessage: String?,
    val outputsJson: String
)

@Entity(tableName = "media_assets")
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    val spaceId: String,
    val jobId: String?,
    val mediaType: String, // image, audio, video, file
    val mimeType: String,
    val fileName: String,
    val localFilePath: String,
    val originalRemoteUrl: String?,
    val sizeBytes: Long,
    val createdAtMs: Long
)
