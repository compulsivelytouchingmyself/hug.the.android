package com.example.network

import com.example.security.AllowedHosts
import com.example.security.TokenRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

data class GradioRuntimeBundle(
    val apiPrefix: String,
    val configJson: JSONObject?,
    val apiInfoJson: JSONObject?,
    val gradioVersion: String
)

sealed class NativeStreamEvent {
    data class QueueStatus(
        val position: Int?,
        val queueSize: Int?,
        val etaSeconds: Double?,
        val message: String,
        val progress: Float? = null
    ) : NativeStreamEvent()
    data class Generating(val data: JSONArray, val message: String = "Streaming output…") : NativeStreamEvent()
    data class Completed(val data: JSONArray, val durationSeconds: Double? = null) : NativeStreamEvent()
    data class Error(val message: String, val isQuotaError: Boolean = false) : NativeStreamEvent()
}

/**
 * Version-aware Native Kotlin Gradio HTTP/SSE client supporting:
 * - Gradio 5+ (/gradio_api/config, /gradio_api/info, /gradio_api/upload, /gradio_api/call/<api>)
 * - Gradio 4 (/config, /info, /upload, /call/<api>, /queue/join + /queue/data)
 * - Gradio 3 (/run/predict)
 */
class GradioNativeClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // Required for long-running SSE streams
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    private fun buildRequest(url: String, token: String?): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "SpaceDeck-Android/1.0")
        if (!token.isNullOrBlank() && AllowedHosts.isTrustedForCredentials(url)) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    suspend fun fetchRuntimeBundle(hostUrl: String, token: String?): GradioRuntimeBundle = withContext(Dispatchers.IO) {
        val base = hostUrl.trimEnd('/')
        var configJson: JSONObject? = null
        var apiPrefix = ""

        // Probe /config first (Gradio 4/5 both serve /config, Gradio 5 includes api_prefix="/gradio_api")
        for (candidate in listOf("$base/config", "$base/gradio_api/config")) {
            val res = getJson(candidate, token)
            if (res != null && (res.has("components") || res.has("version") || res.has("dependencies"))) {
                configJson = res
                apiPrefix = res.optString("api_prefix", if (candidate.contains("/gradio_api/")) "/gradio_api" else "")
                break
            }
        }

        val gradioVersion = configJson?.optString("version", "").orEmpty()
        if (apiPrefix.isBlank() && gradioVersion.startsWith("5")) {
            apiPrefix = "/gradio_api"
        }

        // Probe API info schema
        val infoCandidates = buildList {
            if (apiPrefix.isNotBlank()) add("$base$apiPrefix/info")
            add("$base/gradio_api/info")
            add("$base/info")
        }.distinct()

        var apiInfoJson: JSONObject? = null
        for (candidate in infoCandidates) {
            val res = getJson(candidate, token)
            if (res != null && (res.has("named_endpoints") || res.has("unnamed_endpoints"))) {
                apiInfoJson = res
                if (candidate.contains("/gradio_api/") && apiPrefix.isBlank()) {
                    apiPrefix = "/gradio_api"
                }
                break
            }
        }

        GradioRuntimeBundle(
            apiPrefix = apiPrefix.trimEnd('/'),
            configJson = configJson,
            apiInfoJson = apiInfoJson,
            gradioVersion = gradioVersion
        )
    }

    suspend fun uploadFile(
        hostUrl: String,
        apiPrefix: String,
        file: File,
        fileName: String,
        mimeType: String,
        token: String?
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val base = hostUrl.trimEnd('/')
            val uploadId = UUID.randomUUID().toString().replace("-", "").take(12)
            val candidates = buildList {
                if (apiPrefix.isNotBlank()) add("$base$apiPrefix/upload?upload_id=$uploadId")
                add("$base/gradio_api/upload?upload_id=$uploadId")
                add("$base/upload?upload_id=$uploadId")
            }.distinct()

            var lastError = "File upload failed"
            for (url in candidates) {
                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "files",
                        fileName,
                        file.asRequestBody(mimeType.toMediaTypeOrNull())
                    )
                    .build()

                val req = buildRequest(url, token)
                    .post(multipart)
                    .build()

                okHttpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val arr = runCatching { JSONArray(body) }.getOrNull()
                        val serverPath = arr?.optString(0)?.takeIf { it.isNotBlank() }
                            ?: body.trim().trim('"', '[', ']')
                        if (serverPath.isNotBlank()) {
                            val fileUrlPrefix = if (url.contains("/gradio_api/")) "$base/gradio_api/file=" else "$base/file="
                            return@runCatching JSONObject().apply {
                                put("path", serverPath)
                                put("url", "$fileUrlPrefix$serverPath")
                                put("orig_name", fileName)
                                put("mime_type", mimeType)
                                put("size", file.length())
                                put("meta", JSONObject().put("_type", "gradio.FileData"))
                            }
                        }
                    } else {
                        lastError = "Upload HTTP ${resp.code}: ${TokenRedactor.sanitize(body.take(150))}"
                    }
                }
            }
            throw IllegalStateException(lastError)
        }
    }

    /**
     * Executes a Gradio endpoint via the modern /call/<api_name> POST + GET SSE protocol,
     * falling back to /queue/join + /queue/data SSE or /run/predict if needed.
     */
    suspend fun executeEndpoint(
        hostUrl: String,
        apiPrefix: String,
        apiName: String?,
        fnIndex: Int?,
        dataArray: JSONArray,
        sessionHash: String,
        token: String?,
        onEvent: suspend (NativeStreamEvent) -> Unit
    ) = withContext(Dispatchers.IO) {
        val base = hostUrl.trimEnd('/')
        val cleanApiName = apiName?.trimStart('/')

        // Strategy A: Gradio 4/5 /call/<api_name> two-step SSE
        if (!cleanApiName.isNullOrBlank()) {
            val callUrls = buildList {
                if (apiPrefix.isNotBlank()) add("$base$apiPrefix/call/$cleanApiName")
                add("$base/gradio_api/call/$cleanApiName")
                add("$base/call/$cleanApiName")
            }.distinct()

            for (callUrl in callUrls) {
                val payload = JSONObject().put("data", dataArray).toString()
                val postReq = buildRequest(callUrl, token)
                    .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                val eventId = try {
                    okHttpClient.newCall(postReq).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            runCatching { JSONObject(body).optString("event_id") }.getOrNull()
                        } else if (resp.code == 401 || resp.code == 403) {
                            onEvent(NativeStreamEvent.Error("Authentication required (HTTP ${resp.code}). Configure your Hugging Face token in Vault."))
                            return@withContext
                        } else if (resp.code == 503) {
                            onEvent(NativeStreamEvent.Error("Space is sleeping or starting up (HTTP 503). Wait a moment and retry."))
                            return@withContext
                        } else null
                    }
                } catch (_: Exception) {
                    null
                }

                if (!eventId.isNullOrBlank()) {
                    streamCallSse("$callUrl/$eventId", token, onEvent)
                    return@withContext
                }
            }
        }

        // Strategy B: Queue join + SSE /queue/data (supports unnamed fn_index endpoints too)
        if (fnIndex != null) {
            val prefix = apiPrefix.ifBlank { "/gradio_api" }
            val joinCandidates = listOf("$base$prefix/queue/join", "$base/queue/join").distinct()
            for (joinUrl in joinCandidates) {
                val joinBody = JSONObject().apply {
                    put("data", dataArray)
                    put("fn_index", fnIndex)
                    put("session_hash", sessionHash)
                }.toString()
                val req = buildRequest(joinUrl, token)
                    .post(joinBody.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                val joined = try {
                    okHttpClient.newCall(req).execute().use { it.isSuccessful }
                } catch (_: Exception) {
                    false
                }
                if (joined) {
                    val dataUrl = joinUrl.replace("/queue/join", "/queue/data?session_hash=$sessionHash")
                    streamQueueDataSse(dataUrl, token, onEvent)
                    return@withContext
                }
            }
        }

        // Strategy C: Direct /run/predict fallback
        val predictUrl = if (!cleanApiName.isNullOrBlank()) "$base/run/$cleanApiName" else "$base/run/predict"
        val predictBody = JSONObject().apply {
            put("data", dataArray)
            if (fnIndex != null) put("fn_index", fnIndex)
            put("session_hash", sessionHash)
        }.toString()
        val req = buildRequest(predictUrl, token)
            .post(predictBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        try {
            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val sanitized = TokenRedactor.sanitize(body.take(220))
                    onEvent(NativeStreamEvent.Error("Remote Space returned HTTP ${resp.code}: $sanitized", isQuotaError = isQuotaHint(sanitized)))
                    return@withContext
                }
                val json = JSONObject(body)
                val outData = json.optJSONArray("data") ?: JSONArray()
                val duration = if (json.has("duration")) json.optDouble("duration") else null
                onEvent(NativeStreamEvent.Completed(outData, duration))
            }
        } catch (e: Exception) {
            onEvent(NativeStreamEvent.Error(TokenRedactor.sanitize(e.message ?: "Network execution failed")))
        }
    }

    suspend fun cancelSession(hostUrl: String, apiPrefix: String, sessionHash: String, token: String?) = withContext(Dispatchers.IO) {
        val base = hostUrl.trimEnd('/')
        val candidates = buildList {
            if (apiPrefix.isNotBlank()) add("$base$apiPrefix/cancel")
            add("$base/gradio_api/cancel")
            add("$base/cancel")
        }.distinct()
        val body = JSONObject().put("session_hash", sessionHash).toString()
        for (url in candidates) {
            runCatching {
                val req = buildRequest(url, token)
                    .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                okHttpClient.newCall(req).execute().close()
            }
        }
    }

    private suspend fun streamCallSse(
        sseUrl: String,
        token: String?,
        onEvent: suspend (NativeStreamEvent) -> Unit
    ) {
        val req = buildRequest(sseUrl, token)
            .header("Accept", "text/event-stream")
            .get()
            .build()
        try {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val msg = TokenRedactor.sanitize(body.take(220))
                    onEvent(NativeStreamEvent.Error("Stream failed (HTTP ${resp.code}): $msg", isQuotaError = isQuotaHint(msg)))
                    return
                }
                val stream = resp.body?.byteStream() ?: return
                val reader = BufferedReader(InputStreamReader(stream))
                var currentEvent = "message"
                var line: String? = null
                while (currentCoroutineContext().isActive && reader.readLine().also { line = it } != null) {
                    val l = line ?: break
                    when {
                        l.startsWith("event:") -> currentEvent = l.removePrefix("event:").trim()
                        l.startsWith("data:") -> {
                            val dataStr = l.removePrefix("data:").trim()
                            when (currentEvent) {
                                "complete" -> {
                                    val arr = runCatching { JSONArray(dataStr) }.getOrElse { JSONArray().put(dataStr) }
                                    onEvent(NativeStreamEvent.Completed(arr))
                                    return
                                }
                                "generating" -> {
                                    val arr = runCatching { JSONArray(dataStr) }.getOrElse { JSONArray().put(dataStr) }
                                    onEvent(NativeStreamEvent.Generating(arr))
                                }
                                "error" -> {
                                    val clean = parseErrorMessage(dataStr)
                                    onEvent(NativeStreamEvent.Error(clean, isQuotaError = isQuotaHint(clean)))
                                    return
                                }
                                "heartbeat" -> {
                                    onEvent(NativeStreamEvent.QueueStatus(null, null, null, "Running on remote Space…"))
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                onEvent(NativeStreamEvent.Error(TokenRedactor.sanitize(e.message ?: "SSE stream interrupted")))
            }
        }
    }

    private suspend fun streamQueueDataSse(
        sseUrl: String,
        token: String?,
        onEvent: suspend (NativeStreamEvent) -> Unit
    ) {
        val req = buildRequest(sseUrl, token)
            .header("Accept", "text/event-stream")
            .get()
            .build()
        try {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    onEvent(NativeStreamEvent.Error("Queue stream error (HTTP ${resp.code})"))
                    return
                }
                val stream = resp.body?.byteStream() ?: return
                val reader = BufferedReader(InputStreamReader(stream))
                var line: String? = null
                while (currentCoroutineContext().isActive && reader.readLine().also { line = it } != null) {
                    val l = line ?: break
                    if (!l.startsWith("data:")) continue
                    val json = runCatching { JSONObject(l.removePrefix("data:").trim()) }.getOrNull() ?: continue
                    when (json.optString("msg")) {
                        "estimation" -> {
                            onEvent(
                                NativeStreamEvent.QueueStatus(
                                    position = if (json.has("rank")) json.optInt("rank") + 1 else null,
                                    queueSize = if (json.has("queue_size")) json.optInt("queue_size") else null,
                                    etaSeconds = if (json.has("rank_eta") && !json.isNull("rank_eta")) json.optDouble("rank_eta") else null,
                                    message = "Waiting in remote queue…"
                                )
                            )
                        }
                        "process_starts" -> {
                            onEvent(
                                NativeStreamEvent.QueueStatus(
                                    position = 0,
                                    queueSize = null,
                                    etaSeconds = if (json.has("eta") && !json.isNull("eta")) json.optDouble("eta") else null,
                                    message = "Processing on Space infrastructure…"
                                )
                            )
                        }
                        "progress" -> {
                            val progArr = json.optJSONArray("progress_data")
                            val first = progArr?.optJSONObject(0)
                            val frac = first?.optDouble("progress")?.takeIf { !it.isNaN() }?.toFloat()
                            val desc = first?.optString("desc", "Running…").orEmpty()
                            onEvent(NativeStreamEvent.QueueStatus(null, null, null, desc, progress = frac))
                        }
                        "process_generating" -> {
                            val out = json.optJSONObject("output")?.optJSONArray("data")
                            if (out != null) onEvent(NativeStreamEvent.Generating(out))
                        }
                        "process_completed" -> {
                            val success = json.optBoolean("success", true)
                            val outObj = json.optJSONObject("output") ?: JSONObject()
                            if (success) {
                                val data = outObj.optJSONArray("data") ?: JSONArray()
                                val dur = if (outObj.has("duration")) outObj.optDouble("duration") else null
                                onEvent(NativeStreamEvent.Completed(data, dur))
                            } else {
                                val err = TokenRedactor.sanitize(
                                    outObj.optString("error", json.optString("title", "Execution failed on Space"))
                                )
                                onEvent(NativeStreamEvent.Error(err, isQuotaError = isQuotaHint(err)))
                            }
                            return
                        }
                        "queue_full" -> {
                            onEvent(NativeStreamEvent.Error("Remote Space queue is currently full. Please retry shortly."))
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                onEvent(NativeStreamEvent.Error(TokenRedactor.sanitize(e.message ?: "Queue stream interrupted")))
            }
        }
    }

    private fun getJson(url: String, token: String?): JSONObject? {
        return try {
            val req = buildRequest(url, token)
                .header("Accept", "application/json")
                .get()
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string()?.trim() ?: return null
                if (!text.startsWith("{")) return null
                JSONObject(text)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseErrorMessage(raw: String): String {
        val clean = TokenRedactor.sanitize(raw)
        if (clean == "null" || clean.isBlank()) {
            return "The Space encountered a runtime or ZeroGPU quota error."
        }
        return runCatching {
            val o = JSONObject(clean)
            o.optString("message", o.optString("error", clean))
        }.getOrDefault(clean)
    }

    private fun isQuotaHint(msg: String): Boolean {
        val low = msg.lowercase()
        return low.contains("quota") || low.contains("zerogpu") || low.contains("gpu duration") || low.contains("exceeded")
    }
}
