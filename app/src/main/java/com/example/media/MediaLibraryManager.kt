package com.example.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.example.data.local.MediaAssetDao
import com.example.data.local.MediaAssetEntity
import com.example.security.AllowedHosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MediaLibraryManager(
    private val context: Context,
    private val mediaAssetDao: MediaAssetDao,
    private val okHttpClient: OkHttpClient
) {
    private val libraryDir: File
        get() = File(context.filesDir, "media_library").apply { if (!exists()) mkdirs() }

    private val cameraDir: File
        get() = File(context.cacheDir, "camera_captures").apply { if (!exists()) mkdirs() }

    private val audioDir: File
        get() = File(context.cacheDir, "audio_captures").apply { if (!exists()) mkdirs() }

    private var activeRecorder: MediaRecorder? = null
    private var activeAudioFile: File? = null

    fun createCameraCaptureUri(): Pair<Uri, File> {
        val file = File(cameraDir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return uri to file
    }

    fun startAudioRecording(): Result<File> = runCatching {
        stopAudioRecording()
        val outFile = File(audioDir, "mic_${System.currentTimeMillis()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setOutputFile(outFile.absolutePath)
            prepare()
            start()
        }
        activeRecorder = recorder
        activeAudioFile = outFile
        outFile
    }

    fun stopAudioRecording(): File? {
        val file = activeAudioFile
        try {
            activeRecorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
            try { activeRecorder?.release() } catch (_: Exception) {}
        }
        activeRecorder = null
        activeAudioFile = null
        return file?.takeIf { it.exists() && it.length() > 0 }
    }

    suspend fun copyUriToLibrary(
        uri: Uri,
        spaceId: String = "imported",
        mediaTypeHint: String? = null
    ): Result<MediaAssetEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val name = queryDisplayName(uri) ?: "imported_${System.currentTimeMillis()}.${guessExtension(mime)}"
            val safeName = sanitizeFileName(name)
            val id = UUID.randomUUID().toString()
            val outFile = File(libraryDir, "${id}_$safeName")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Unable to read selected file.")

            val mType = mediaTypeHint ?: inferMediaType(mime, safeName)
            val entity = MediaAssetEntity(
                id = id,
                spaceId = spaceId,
                jobId = null,
                mediaType = mType,
                mimeType = mime,
                fileName = safeName,
                localFilePath = outFile.absolutePath,
                originalRemoteUrl = null,
                sizeBytes = outFile.length(),
                createdAtMs = System.currentTimeMillis()
            )
            mediaAssetDao.upsertAsset(entity)
            entity
        }
    }

    suspend fun cacheRemoteMedia(
        remoteUrl: String,
        spaceId: String,
        jobId: String?,
        fileNameHint: String?,
        mimeTypeHint: String?,
        token: String?
    ): Result<MediaAssetEntity> = withContext(Dispatchers.IO) {
        runCatching {
            if (!AllowedHosts.isAllowedNetworkTarget(remoteUrl)) {
                throw SecurityException("Refused media download from untrusted host.")
            }
            val reqBuilder = Request.Builder().url(remoteUrl)
            if (!token.isNullOrBlank() && AllowedHosts.isTrustedForCredentials(remoteUrl)) {
                reqBuilder.header("Authorization", "Bearer $token")
            }
            okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Media download failed (HTTP ${resp.code})")
                }
                val contentType = resp.header("Content-Type")?.substringBefore(';')?.trim()
                    ?: mimeTypeHint ?: "application/octet-stream"
                val rawName = fileNameHint?.takeIf { it.isNotBlank() }
                    ?: remoteUrl.substringAfterLast('/').substringBefore('?').ifBlank {
                        "output_${System.currentTimeMillis()}.${guessExtension(contentType)}"
                    }
                val safeName = sanitizeFileName(rawName)
                val id = UUID.randomUUID().toString()
                val outFile = File(libraryDir, "${id}_$safeName")

                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Empty remote media body")

                val entity = MediaAssetEntity(
                    id = id,
                    spaceId = spaceId,
                    jobId = jobId,
                    mediaType = inferMediaType(contentType, safeName),
                    mimeType = contentType,
                    fileName = safeName,
                    localFilePath = outFile.absolutePath,
                    originalRemoteUrl = remoteUrl,
                    sizeBytes = outFile.length(),
                    createdAtMs = System.currentTimeMillis()
                )
                mediaAssetDao.upsertAsset(entity)
                entity
            }
        }
    }

    suspend fun saveToPublicDownloads(localPath: String, fileName: String, mimeType: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val src = File(localPath)
                if (!src.exists()) throw IllegalStateException("Cached file no longer exists.")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, sanitizeFileName(fileName))
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SpaceDeck")
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("Could not create Downloads entry.")
                    resolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { input -> input.copyTo(out) }
                    }
                    "Downloads/SpaceDeck/${sanitizeFileName(fileName)}"
                } else {
                    val exportDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SpaceDeck")
                        .apply { mkdirs() }
                    val dst = File(exportDir, sanitizeFileName(fileName))
                    src.copyTo(dst, overwrite = true)
                    dst.absolutePath
                }
            }
        }

    fun buildShareIntent(localPath: String, mimeType: String, title: String = "Share from SpaceDeck"): Intent? {
        val file = File(localPath)
        if (!file.exists()) return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun deleteAsset(asset: MediaAssetEntity) = withContext(Dispatchers.IO) {
        runCatching { File(asset.localFilePath).delete() }
        mediaAssetDao.deleteAsset(asset.id)
    }

    suspend fun copyLocalFileToTempUpload(uri: Uri): Result<Triple<File, String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val name = sanitizeFileName(queryDisplayName(uri) ?: "upload_${System.currentTimeMillis()}.${guessExtension(mime)}")
            val tmpFile = File(context.cacheDir, "up_${System.currentTimeMillis()}_$name")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Cannot open selected URI.")
            Triple(tmpFile, name, mime)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "asset.bin" }
    }

    private fun inferMediaType(mime: String, fileName: String): String {
        val m = mime.lowercase()
        val f = fileName.lowercase()
        return when {
            m.startsWith("image/") || f.endsWith(".png") || f.endsWith(".jpg") || f.endsWith(".jpeg") || f.endsWith(".webp") -> "image"
            m.startsWith("audio/") || f.endsWith(".wav") || f.endsWith(".mp3") || f.endsWith(".flac") || f.endsWith(".m4a") || f.endsWith(".ogg") -> "audio"
            m.startsWith("video/") || f.endsWith(".mp4") || f.endsWith(".webm") || f.endsWith(".mov") || f.endsWith(".mkv") -> "video"
            else -> "file"
        }
    }

    private fun guessExtension(mime: String): String = when {
        mime.contains("png") -> "png"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("webp") -> "webp"
        mime.contains("wav") -> "wav"
        mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
        mime.contains("mp4") && mime.startsWith("audio") -> "m4a"
        mime.contains("mp4") -> "mp4"
        mime.contains("webm") -> "webm"
        mime.contains("json") -> "json"
        mime.contains("pdf") -> "pdf"
        else -> "bin"
    }
}
