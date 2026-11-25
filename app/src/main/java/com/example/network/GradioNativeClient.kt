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
import java.net.URLEncoder
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
    data class Error(
        val message: String,
        val isQuotaError: Boolean = false,
        val isAuthError: Boolean = false
    ) : NativeStreamEvent()
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

    companion object {
        /**
         * Appends ?__sign=<jwt> or &__sign=<jwt> ONLY to trusted Hugging Face hosts.
         */
        fun appendSignParam(url: String, spaceJwt: String?): String {
            if (spaceJwt.isNullOrBlank()) return url
            if (!AllowedHosts.isTrustedForCredentials(url)) return url
            if (url.contains("__sign=")) return url
            val encoded = URLEncoder.encode(spaceJwt, "UTF-8")
            val sep = if (url.contains("?")) "&" else "?"
            return "$url${sep}__sign=$encoded"
        }
    }

    private fun buildRequest(url: String, token: String?, spaceJwt: String? = null): Request.Builder {
        val signedUrl = appendSignParam(url, spaceJwt)
        val builder = Request.Builder()
            .url(signedUrl)
            .header("User-Agent", "SpaceDeck-Android/1.0")
        if (!token.isNullOrBlank() && AllowedHosts.isTrustedForCredentials(signedUrl)) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    suspend fun fetchRuntimeBundle(
        hostUrl: String,
        token: String?,
        spaceJwt: String? = null
    ): GradioRuntimeBundle = withContext(Dispatchers.IO) {
        val base = hostUrl.trimEnd('/')
        var configJson: JSONObject? = null
        var apiPrefix = ""

        // Probe /config first (Gradio 4/5 both serve /config, Gradio 5 includes api_prefix="/gradio_api")
        for (candidate in listOf("$base/config", "$base/gradio_api/config")) {
            val res = getJson(candidate, token, spaceJwt)
            if (res != null && (res.has("components") || res.has("version") || res.has("dependencies"))) {
                configJson = res
                apiPrefix = res.optString("api_prefix", if (candidate.contains("/gradio_api/")) "/gradio_api" else "")
                break
            }
        }

        val gradioVersion = configJson?.optString("version", "").orEmpty()
        val majorVersion = gradioVersion.substringBefore('.').toIntOrNull() ?: 0
        if (apiPrefix.isBlank() && majorVersion >= 5) {
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
            val res = getJson(candidate, token, spaceJwt)
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
        token: String?,
        spaceJwt: String? = null
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

                val req = buildRequest(url, token, spaceJwt)
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
        spaceJwt: String? = null,
        onDiagnostic: ((String) -> Unit)? = null,
        onEvent: suspend (NativeStreamEvent) -> Unit
    ) = withContext(Dispatchers.IO) {
        val base = hostUrl.trimEnd('/')
        val cleanApiName = apiName?.trimStart('/')
        val failureNotes = mutableListOf<String>()

        // Strategy A: Gradio 4/5/6 /call/<api_name> two-step SSE
        if (!cleanApiName.isNullOrBlank()) {
            val callUrls = buildList {
                if (apiPrefix.isNotBlank()) add("$base$apiPrefix/call/$cleanApiName")
                add("$base/gradio_api/call/$cleanApiName")
                add("$base/call/$cleanApiName")
            }.distinct()

            for (callUrl in callUrls) {
                val payload = JSONObject().put("data", dataArray).toString()
                val postReq = buildRequest(callUrl, token, spaceJwt)
                    .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                val eventId = try {
                    okHttpClient.newCall(postReq).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            val eid = runCatching { JSONObject(body).optString("event_id") }.getOrNull()
                            onDiagnostic?.invoke("POST ${callUrl.removePrefix(base)} -> HTTP ${resp.code} (event_id=${eid?.take(10) ?: "none"})")
                            eid
                        } else if (resp.code == 401 || resp.code == 403) {
                            val cleanBody = TokenRedactor.sanitize(body.take(180))
                            onEvent(
                                NativeStreamEvent.Error(
                                    message = "Authentication required on ${callUrl.removePrefix(base)} (HTTP ${resp.code}): $cleanBody",
                                    isQuotaError = false,
                                    isAuthError = true
                                )
                            )
                            return@withContext
                        } else if (resp.code == 503) {
                            onEvent(NativeStreamEvent.Error("Space is sleeping or starting up (HTTP 503). Wait a moment and retry."))
                            return@withContext
                        } else {
                            val note = "POST ${callUrl.removePrefix(base)} -> HTTP ${resp.code}: ${TokenRedactor.sanitize(body.take(200))}"
                            failureNotes.add(note)
                            onDiagnostic?.invoke(note)
                            null
                        }
                    }
                } catch (e: Exception) {
                    val note = "POST ${callUrl.removePrefix(base)} exception: ${TokenRedactor.sanitize(e.message ?: "network error")}"
                    failureNotes.add(note)
                    onDiagnostic?.invoke(note)
                    null
                }

                if (!eventId.isNullOrBlank()) {
                    var blankCallError = false
                    streamCallSse("$callUrl/$eventId", token, spaceJwt) { ev ->
                        if (ev is NativeStreamEvent.Error && fnIndex != null && ev.message.contains("returned no details", ignoreCase = true)) {
                            blankCallError = true
                            failureNotes.add("/call/$cleanApiName returned empty error event; retrying via /queue/join for full traceback")
                            onDiagnostic?.invoke("/call/$cleanApiName returned empty error event; retrying via /queue/join…")
                        } else {
                            onEvent(ev)
                        }
                    }
                    if (!blankCallError) return@withContext
                    break
                }
            }
        }

        // Strategy B: Queue join + SSE /queue/data (supports unnamed fn_index endpoints and full error/progress payloads)
        if (fnIndex != null) {
            val prefix = apiPrefix.ifBlank { "/gradio_api" }
            val joinCandidates = listOf("$base$prefix/queue/join", "$base/queue/join").distinct()
            for (joinUrl in joinCandidates) {
                val joinBody = JSONObject().apply {
                    put("data", dataArray)
                    put("fn_index", fnIndex)
                    put("session_hash", sessionHash)
                }.toString()
                val req = buildRequest(joinUrl, token, spaceJwt)
                    .post(joinBody.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                val (joinStatus, joinRespBody) = try {
                    okHttpClient.newCall(req).execute().use { resp ->
                        resp.code to resp.body?.string().orEmpty()
                    }
                } catch (e: Exception) {
                    val note = "POST ${joinUrl.removePrefix(base)} exception: ${TokenRedactor.sanitize(e.message ?: "network error")}"
                    failureNotes.add(note)
                    onDiagnostic?.invoke(note)
                    -1 to ""
                }
                if (joinStatus == 401 || joinStatus == 403) {
                    onEvent(
                        NativeStreamEvent.Error(
                            message = "Authentication required for queue join (HTTP $joinStatus): ${TokenRedactor.sanitize(joinRespBody.take(160))}",
                            isQuotaError = false,
                            isAuthError = true
                        )
                    )
                    return@withContext
                }
                if (joinStatus in 200..299) {
                    onDiagnostic?.invoke("Joined queue via ${joinUrl.removePrefix(base)} (HTTP $joinStatus, fn_index=$fnIndex)")
                    val dataUrl = joinUrl.replace("/queue/join", "/queue/data?session_hash=$sessionHash")
                    streamQueueDataSse(dataUrl, token, spaceJwt, onEvent)
                    return@withContext
                } else if (joinStatus > 0) {
                    val note = "POST ${joinUrl.removePrefix(base)} -> HTTP $joinStatus: ${TokenRedactor.sanitize(joinRespBody.take(200))}"
                    failureNotes.add(note)
                    onDiagnostic?.invoke(note)
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
        val req = buildRequest(predictUrl, token, spaceJwt)
            .post(predictBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        try {
            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val sanitized = TokenRedactor.sanitize(body.take(240))
                    val priorSummary = failureNotes.takeLast(2).joinToString(" | ")
                    val combined = buildString {
                        append("Remote Space returned HTTP ${resp.code} on ${predictUrl.removePrefix(base)}: $sanitized")
                        if (priorSummary.isNotBlank()) append("\nPrevious attempts: $priorSummary")
                    }
                    onEvent(
                        NativeStreamEvent.Error(
                            message = combined,
                            isQuotaError = isQuotaHint(combined),
                            isAuthError = resp.code == 401 || resp.code == 403 || isAuthHint(combined)
                        )
                    )
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

    suspend fun cancelSession(
        hostUrl: String,
        apiPrefix: String,
        sessionHash: String,
        token: String?,
        spaceJwt: String? = null
    ) = withContext(Dispatchers.IO) {
        val base = hostUrl.trimEnd('/')
        val candidates = buildList {
            if (apiPrefix.isNotBlank()) add("$base$apiPrefix/cancel")
            add("$base/gradio_api/cancel")
            add("$base/cancel")
        }.distinct()
        val body = JSONObject().put("session_hash", sessionHash).toString()
        for (url in candidates) {
            runCatching {
                val req = buildRequest(url, token, spaceJwt)
                    .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                okHttpClient.newCall(req).execute().close()
            }
        }
    }

    private suspend fun streamCallSse(
        sseUrl: String,
        token: String?,
        spaceJwt: String?,
        onEvent: suspend (NativeStreamEvent) -> Unit
    ) {
        val req = buildRequest(sseUrl, token, spaceJwt)
            .header("Accept", "text/event-stream")
            .get()
            .build()
        try {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val msg = TokenRedactor.sanitize(body.take(220))
                    onEvent(
                        NativeStreamEvent.Error(
                            message = "Stream failed (HTTP ${resp.code}): $msg",
                            isQuotaError = isQuotaHint(msg),
                            isAuthError = resp.code == 401 || resp.code == 403 || isAuthHint(msg)
                        )
                    )
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
                                    onEvent(
                                        NativeStreamEvent.Error(
                                            message = clean,
                                            isQuotaError = isQuotaHint(clean),
                                            isAuthError = isAuthHint(clean)
                                        )
                                    )
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
        spaceJwt: String?,
        onEvent: suspend (NativeStreamEvent) -> Unit
    ) {
        val req = buildRequest(sseUrl, token, spaceJwt)
            .header("Accept", "text/event-stream")
            .get()
            .build()
        try {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    onEvent(
                        NativeStreamEvent.Error(
                            message = "Queue stream error (HTTP ${resp.code})",
                            isAuthError = resp.code == 401 || resp.code == 403
                        )
                    )
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
                                onEvent(
                                    NativeStreamEvent.Error(
                                        message = err,
                                        isQuotaError = isQuotaHint(err),
                                        isAuthError = isAuthHint(err)
                                    )
                                )
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

    private fun getJson(url: String, token: String?, spaceJwt: String? = null): JSONObject? {
        return try {
            val req = buildRequest(url, token, spaceJwt)
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
            return "Remote Space /call endpoint returned no details (data: null) — likely a Python runtime error, invalid input, or ZeroGPU limit."
        }
        return runCatching {
            val o = JSONObject(clean)
            o.optString("message", o.optString("error", o.optString("title", clean)))
        }.getOrDefault(clean)
    }

    private fun isQuotaHint(msg: String): Boolean {
        val low = msg.lowercase()
        return low.contains("quota") ||
            low.contains("zerogpu") ||
            low.contains("gpu duration") ||
            low.contains("exceeded") ||
            low.contains("higher usage") ||
            low.contains("unauthenticated")
    }

    private fun isAuthHint(msg: String): Boolean {
        val low = msg.lowercase()
        return low.contains("authenticate") ||
            low.contains("higher usage") ||
            low.contains("sign in") ||
            low.contains("log in") ||
            low.contains("login") ||
            low.contains("unauthorized") ||
            low.contains("401") ||
            low.contains("403")
    }
}
