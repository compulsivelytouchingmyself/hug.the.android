package com.example.network

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.data.local.JobRecordDao
import com.example.data.local.JobRecordEntity
import com.example.media.MediaLibraryManager
import com.example.model.ActiveJobState
import com.example.model.ComponentField
import com.example.model.ComponentKind
import com.example.model.EndpointManifest
import com.example.model.FieldValue
import com.example.model.JobStatus
import com.example.model.OutputItem
import com.example.model.SpaceManifest
import com.example.security.AuthCredentialSource
import com.example.security.ResolvedCredential
import com.example.security.TokenRedactor
import com.example.service.SpaceJobMonitorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class SpaceExecutionEngine(
    private val context: Context,
    private val hubClient: HfHubClient,
    private val hybridSdkBridge: HybridSdkBridge,
    private val gradioNativeClient: GradioNativeClient,
    private val mediaLibraryManager: MediaLibraryManager,
    private val jobRecordDao: JobRecordDao
) {
    private enum class JsAttemptOutcome {
        COMPLETED,
        FALLBACK_TO_NATIVE,
        RETRY_NEXT_AUTH,
        FAILED
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionHashes = mutableMapOf<String, String>()

    private val _activeJob = MutableStateFlow(ActiveJobState())
    val activeJob: StateFlow<ActiveJobState> = _activeJob.asStateFlow()

    private val _outputs = MutableStateFlow<List<OutputItem>>(emptyList())
    val outputs: StateFlow<List<OutputItem>> = _outputs.asStateFlow()

    private var currentRunJob: Job? = null
    private var timerJob: Job? = null
    private var currentSpaceHost: String = ""
    private var currentApiPrefix: String = ""
    private var currentToken: String? = null
    private var currentSpaceJwt: String? = null
    private var currentSessionHash: String = ""
    private var currentTransport: String = ""

    fun clearOutputs() {
        _outputs.value = emptyList()
        if (_activeJob.value.status !in setOf(JobStatus.UPLOADING, JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.STREAMING)) {
            _activeJob.value = ActiveJobState()
        }
    }

    fun execute(
        manifest: SpaceManifest,
        endpoint: EndpointManifest,
        fieldValues: Map<String, FieldValue>,
        credentialChain: List<ResolvedCredential>,
        onDiagnostic: (String) -> Unit
    ) {
        currentRunJob?.cancel()
        timerJob?.cancel()

        val jobId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val sessionHash = sessionHashes.getOrPut(manifest.identity.spaceId) {
            UUID.randomUUID().toString().replace("-", "").take(12)
        }
        currentSpaceHost = manifest.identity.hostUrl
        currentApiPrefix = manifest.provenance.apiPrefix
        currentToken = credentialChain.firstOrNull()?.token
        currentSpaceJwt = null
        currentSessionHash = sessionHash
        currentTransport = ""

        val declaredGpuSec = endpoint.zeroGpuDecorator?.declaredDurationSeconds
            ?: manifest.hardware.decorators.firstOrNull()?.declaredDurationSeconds

        _outputs.value = emptyList()
        _activeJob.value = ActiveJobState(
            jobId = jobId,
            spaceId = manifest.identity.spaceId,
            endpointLabel = endpoint.label,
            status = JobStatus.UPLOADING,
            transport = "initializing",
            progressMessage = "Preparing inputs & Space auth…",
            startedAtMs = startedAt,
            elapsedMs = 0L,
            declaredZeroGpuSeconds = declaredGpuSec
        )

        SpaceJobMonitorService.updateProgress(
            context,
            "${manifest.identity.title} • ${endpoint.label}",
            "Preparing inputs…"
        )

        timerJob = scope.launch {
            while (isActive) {
                delay(500)
                val st = _activeJob.value.status
                if (st in setOf(JobStatus.UPLOADING, JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.STREAMING)) {
                    _activeJob.update { it.copy(elapsedMs = System.currentTimeMillis() - startedAt) }
                } else {
                    break
                }
            }
        }

        currentRunJob = scope.launch {
            persistJobRecord(
                jobId = jobId,
                manifest = manifest,
                endpoint = endpoint,
                status = JobStatus.UPLOADING,
                transport = "pending",
                startedAt = startedAt,
                finishedAt = null,
                declaredGpuSec = declaredGpuSec,
                summary = "Uploading inputs…",
                error = null,
                outputs = emptyList()
            )

            try {
                val attempts: List<ResolvedCredential?> = credentialChain.ifEmpty { listOf(null) }
                val inputSummary = endpoint.inputs.joinToString(", ") { f ->
                    val v = fieldValues[f.id] ?: defaultValueForField(f)
                    when (v) {
                        is FieldValue.MediaRef -> "${f.parameterName}:${f.kind.name}(${v.fileName.ifBlank { "empty" }})"
                        is FieldValue.Text -> "${f.parameterName}:Text(${v.value.length}ch)"
                        is FieldValue.NumberVal -> "${f.parameterName}:${v.value}"
                        is FieldValue.BoolVal -> "${f.parameterName}:${v.value}"
                        is FieldValue.MultiSelect -> "${f.parameterName}:[${v.values.size}]"
                        is FieldValue.Empty -> "${f.parameterName}:null"
                        else -> "${f.parameterName}:${f.kind.name}"
                    }
                }
                onDiagnostic("Prepared ${endpoint.inputs.size} inputs for ${endpoint.callableTarget}: $inputSummary")

                val jsPayload = buildJsSdkArgsIfSmallEnough(endpoint.inputs, fieldValues)

                for ((attemptIdx, credential) in attempts.withIndex()) {
                    val hasFallback = attemptIdx < attempts.lastIndex
                    val token = credential?.token
                    val sourceLabel = credential?.source?.label ?: AuthCredentialSource.ANONYMOUS.label

                    // Exchange user token / OAuth token for a Space-scoped signed JWT (?__sign=<jwt>)
                    val spaceJwt = if (!token.isNullOrBlank()) {
                        hubClient.fetchSpaceJwt(manifest.identity.spaceId, token).getOrNull()
                    } else null

                    currentToken = token
                    currentSpaceJwt = spaceJwt

                    val authTransportNote = when {
                        !token.isNullOrBlank() && !spaceJwt.isNullOrBlank() -> "Bearer + Space JWT (__sign)"
                        !token.isNullOrBlank() -> "Bearer only (JWT unavailable)"
                        else -> "Anonymous"
                    }
                    onDiagnostic("Auth path ${attemptIdx + 1}/${attempts.size}: Using $sourceLabel ($authTransportNote)")

                    var retryWithNextCredential = false
                    var finishedJob = false

                    // Priority 1: Official Bundled @gradio/client@2.8.0 with incremental streaming SSE + per-job channel
                    if (jsPayload != null && hybridSdkBridge.awaitReady(timeoutMs = 2500L)) {
                        val jsAttemptJobId = "${jobId}_$attemptIdx"
                        currentTransport = "@gradio/client@2.8.0 ($sourceLabel)"
                        _activeJob.update {
                            it.copy(
                                status = JobStatus.QUEUED,
                                transport = currentTransport,
                                progressMessage = "Submitting via @gradio/client ($sourceLabel)…"
                            )
                        }
                        onDiagnostic("Priority 1: Submitting ${endpoint.callableTarget} via bundled @gradio/client@2.8.0.")

                        // Open per-job buffered channel BEFORE calling submitJob so fast events are never lost
                        val eventChannel = hybridSdkBridge.openJobEventChannel(jsAttemptJobId)
                        try {
                            val submitRes = hybridSdkBridge.submitJob(
                                jobId = jsAttemptJobId,
                                sessionKey = manifest.identity.spaceId,
                                spaceRef = manifest.identity.spaceId,
                                token = token,
                                spaceJwt = spaceJwt,
                                endpoint = endpoint.callableTarget,
                                rawArgsJson = jsPayload.toString()
                            )

                            if (submitRes.isSuccess) {
                                when (
                                    consumeJsJobEvents(
                                        eventChannel = eventChannel,
                                        recordJobId = jobId,
                                        manifest = manifest,
                                        endpoint = endpoint,
                                        startedAt = startedAt,
                                        declaredGpuSec = declaredGpuSec,
                                        token = token,
                                        spaceJwt = spaceJwt,
                                        hasFallbackAuth = hasFallback,
                                        onDiagnostic = onDiagnostic
                                    )
                                ) {
                                    JsAttemptOutcome.COMPLETED, JsAttemptOutcome.FAILED -> {
                                        finishedJob = true
                                    }
                                    JsAttemptOutcome.RETRY_NEXT_AUTH -> {
                                        retryWithNextCredential = true
                                    }
                                    JsAttemptOutcome.FALLBACK_TO_NATIVE -> {
                                        // proceed to Priority 2 native client
                                    }
                                }
                            } else {
                                val err = TokenRedactor.sanitize(submitRes.exceptionOrNull()?.message.orEmpty())
                                onDiagnostic("@gradio/client submission fell back to native REST/SSE: $err")
                            }
                        } finally {
                            hybridSdkBridge.closeJobEventChannel(jsAttemptJobId)
                        }
                    }

                    if (finishedJob) break
                    if (retryWithNextCredential && hasFallback) {
                        val nextLabel = attempts[attemptIdx + 1]?.source?.label ?: "fallback"
                        onDiagnostic("Switching from $sourceLabel to fallback auth ($nextLabel)…")
                        _activeJob.update {
                            it.copy(
                                status = JobStatus.QUEUED,
                                progressMessage = "Retrying with $nextLabel…"
                            )
                        }
                        continue
                    }

                    // Priority 2 & 3: Version-Aware Native Kotlin Gradio REST/SSE + Native OkHttp Multipart Upload
                    currentTransport = "native_gradio_sse ($sourceLabel)"
                    _activeJob.update {
                        it.copy(
                            status = JobStatus.UPLOADING,
                            transport = currentTransport,
                            progressMessage = "Uploading files via native HTTP ($sourceLabel)…"
                        )
                    }
                    onDiagnostic("Priority 2: Running ${endpoint.callableTarget} via Native Gradio REST/SSE ($authTransportNote).")

                    val nativeArgs = buildNativeArgsWithUploads(
                        hostUrl = manifest.identity.hostUrl,
                        apiPrefix = manifest.provenance.apiPrefix,
                        fields = endpoint.inputs,
                        values = fieldValues,
                        token = token,
                        spaceJwt = spaceJwt,
                        onDiagnostic = onDiagnostic
                    )

                    _activeJob.update {
                        it.copy(status = JobStatus.QUEUED, progressMessage = "Joining Space queue ($sourceLabel)…")
                    }
                    SpaceJobMonitorService.updateProgress(
                        context,
                        "${manifest.identity.title} • ${endpoint.label}",
                        "Running on remote Space…"
                    )

                    var nativeAuthFallbackTriggered = false

                    gradioNativeClient.executeEndpoint(
                        hostUrl = manifest.identity.hostUrl,
                        apiPrefix = manifest.provenance.apiPrefix,
                        apiName = endpoint.apiName,
                        fnIndex = endpoint.fnIndex,
                        dataArray = nativeArgs,
                        sessionHash = sessionHash,
                        token = token,
                        spaceJwt = spaceJwt,
                        onDiagnostic = onDiagnostic
                    ) { event ->
                        when (event) {
                            is NativeStreamEvent.QueueStatus -> {
                                val nextStatus = if (event.position == 0 || event.progress != null) JobStatus.RUNNING else JobStatus.QUEUED
                                _activeJob.update {
                                    it.copy(
                                        status = nextStatus,
                                        queuePosition = event.position ?: it.queuePosition,
                                        queueSize = event.queueSize ?: it.queueSize,
                                        etaSeconds = event.etaSeconds ?: it.etaSeconds,
                                        progressFraction = event.progress ?: it.progressFraction,
                                        progressMessage = event.message.ifBlank { it.progressMessage },
                                        elapsedMs = System.currentTimeMillis() - startedAt
                                    )
                                }
                            }
                            is NativeStreamEvent.Generating -> {
                                val parsed = parseOutputs(
                                    dataArray = event.data,
                                    outputSpecs = endpoint.outputs,
                                    hostUrl = manifest.identity.hostUrl,
                                    apiPrefix = manifest.provenance.apiPrefix,
                                    spaceId = manifest.identity.spaceId,
                                    jobId = jobId,
                                    token = token,
                                    spaceJwt = spaceJwt,
                                    cacheMedia = false
                                )
                                _outputs.value = parsed
                                _activeJob.update {
                                    it.copy(
                                        status = JobStatus.STREAMING,
                                        isIntermediate = true,
                                        progressMessage = event.message,
                                        elapsedMs = System.currentTimeMillis() - startedAt
                                    )
                                }
                            }
                            is NativeStreamEvent.Completed -> {
                                val parsed = parseOutputs(
                                    dataArray = event.data,
                                    outputSpecs = endpoint.outputs,
                                    hostUrl = manifest.identity.hostUrl,
                                    apiPrefix = manifest.provenance.apiPrefix,
                                    spaceId = manifest.identity.spaceId,
                                    jobId = jobId,
                                    token = token,
                                    spaceJwt = spaceJwt,
                                    cacheMedia = true
                                )
                                _outputs.value = parsed
                                val now = System.currentTimeMillis()
                                _activeJob.update {
                                    it.copy(
                                        status = JobStatus.COMPLETED,
                                        isIntermediate = false,
                                        progressFraction = 1f,
                                        progressMessage = "Completed (${parsed.size} output${if (parsed.size == 1) "" else "s"})",
                                        elapsedMs = now - startedAt
                                    )
                                }
                                persistJobRecord(
                                    jobId = jobId,
                                    manifest = manifest,
                                    endpoint = endpoint,
                                    status = JobStatus.COMPLETED,
                                    transport = currentTransport,
                                    startedAt = startedAt,
                                    finishedAt = now,
                                    declaredGpuSec = declaredGpuSec,
                                    summary = summarizeOutputs(parsed),
                                    error = null,
                                    outputs = parsed
                                )
                                SpaceJobMonitorService.notifyComplete(
                                    context,
                                    "${manifest.identity.title} finished",
                                    summarizeOutputs(parsed)
                                )
                                onDiagnostic("Completed in ${(now - startedAt) / 1000.0}s with ${parsed.size} outputs ($sourceLabel).")
                                finishedJob = true
                            }
                            is NativeStreamEvent.Error -> {
                                if ((event.isAuthError || event.isQuotaError) && hasFallback) {
                                    nativeAuthFallbackTriggered = true
                                    val nextLabel = attempts[attemptIdx + 1]?.source?.label ?: "fallback"
                                    onDiagnostic("$sourceLabel hit auth/usage limit (${event.message.take(120)}). Falling back to $nextLabel…")
                                } else {
                                    handleJobFailure(
                                        jobId = jobId,
                                        manifest = manifest,
                                        endpoint = endpoint,
                                        startedAt = startedAt,
                                        declaredGpuSec = declaredGpuSec,
                                        error = event.message,
                                        isQuota = event.isQuotaError,
                                        onDiagnostic = onDiagnostic
                                    )
                                    finishedJob = true
                                }
                            }
                        }
                    }

                    if (finishedJob) break
                    if (nativeAuthFallbackTriggered && hasFallback) {
                        val nextLabel = attempts[attemptIdx + 1]?.source?.label ?: "fallback"
                        _activeJob.update {
                            it.copy(
                                status = JobStatus.QUEUED,
                                progressMessage = "Falling back to $nextLabel…"
                            )
                        }
                        continue
                    }
                    break
                }
            } catch (_: CancellationException) {
                val now = System.currentTimeMillis()
                _activeJob.update {
                    it.copy(
                        status = JobStatus.CANCELLED,
                        progressMessage = "Cancelled by user",
                        elapsedMs = now - startedAt
                    )
                }
                persistJobRecord(
                    jobId = jobId,
                    manifest = manifest,
                    endpoint = endpoint,
                    status = JobStatus.CANCELLED,
                    transport = currentTransport,
                    startedAt = startedAt,
                    finishedAt = now,
                    declaredGpuSec = declaredGpuSec,
                    summary = "Cancelled",
                    error = null,
                    outputs = _outputs.value
                )
                SpaceJobMonitorService.stop(context)
            } catch (e: Exception) {
                val clean = TokenRedactor.sanitize(e.message ?: "Execution failed")
                handleJobFailure(
                    jobId = jobId,
                    manifest = manifest,
                    endpoint = endpoint,
                    startedAt = startedAt,
                    declaredGpuSec = declaredGpuSec,
                    error = clean,
                    isQuota = isQuotaOrHigherUsageError(clean),
                    onDiagnostic = onDiagnostic
                )
            } finally {
                timerJob?.cancel()
            }
        }
    }

    fun cancelActiveJob(onDiagnostic: (String) -> Unit) {
        val active = _activeJob.value
        if (active.jobId.isBlank()) return
        onDiagnostic("Cancelling job ${active.jobId.take(8)}…")
        scope.launch {
            if (currentTransport.contains("gradio/client")) {
                hybridSdkBridge.cancelJob(active.jobId)
            } else if (currentSpaceHost.isNotBlank() && currentSessionHash.isNotBlank()) {
                gradioNativeClient.cancelSession(
                    hostUrl = currentSpaceHost,
                    apiPrefix = currentApiPrefix,
                    sessionHash = currentSessionHash,
                    token = currentToken,
                    spaceJwt = currentSpaceJwt
                )
            }
        }
        currentRunJob?.cancel()
        timerJob?.cancel()
        SpaceJobMonitorService.stop(context)
    }

    private suspend fun consumeJsJobEvents(
        eventChannel: Channel<SdkJobEvent>,
        recordJobId: String,
        manifest: SpaceManifest,
        endpoint: EndpointManifest,
        startedAt: Long,
        declaredGpuSec: Int?,
        token: String?,
        spaceJwt: String?,
        hasFallbackAuth: Boolean,
        onDiagnostic: (String) -> Unit
    ): JsAttemptOutcome {
        var sawData = false
        while (true) {
            val ev = eventChannel.receive()
            when (ev.type) {
                "status" -> {
                    val stage = ev.payload.optString("stage", "")
                    val pos = if (ev.payload.has("position") && !ev.payload.isNull("position")) ev.payload.optInt("position") else null
                    val size = if (ev.payload.has("size") && !ev.payload.isNull("size")) ev.payload.optInt("size") else null
                    val eta = if (ev.payload.has("eta") && !ev.payload.isNull("eta")) ev.payload.optDouble("eta") else null
                    val title = ev.payload.optString("title", "").trim()
                    val rawMsg = ev.payload.optString("message", "").trim()
                    val combinedMsg = listOf(title, rawMsg).filter { it.isNotBlank() }.distinct().joinToString(" — ")
                    if (stage == "error") {
                        val clean = TokenRedactor.sanitize(combinedMsg.ifBlank { "Gradio client reported stage=error with no details" })
                        val isAuthOrQuota = isQuotaOrHigherUsageError(clean) || isAuthError(clean)
                        if (isAuthOrQuota && hasFallbackAuth) {
                            onDiagnostic("@gradio/client reported auth/usage limit; switching to fallback credential: $clean")
                            return JsAttemptOutcome.RETRY_NEXT_AUTH
                        }
                        // If @gradio/client failed before any output arrived and it isn't an explicit quota/auth rejection,
                        // fall back to Native OkHttp Gradio REST/SSE (which also captures exact HTTP status & Python error details).
                        if (!sawData && !isAuthOrQuota) {
                            onDiagnostic("@gradio/client failed before output ($clean); falling back to Native Gradio REST/SSE…")
                            return JsAttemptOutcome.FALLBACK_TO_NATIVE
                        }
                        handleJobFailure(
                            recordJobId, manifest, endpoint, startedAt, declaredGpuSec,
                            error = clean,
                            isQuota = isQuotaOrHigherUsageError(clean),
                            onDiagnostic = onDiagnostic
                        )
                        return JsAttemptOutcome.FAILED
                    }
                    _activeJob.update {
                        it.copy(
                            status = if (stage == "pending") JobStatus.QUEUED else JobStatus.RUNNING,
                            queuePosition = pos ?: it.queuePosition,
                            queueSize = size ?: it.queueSize,
                            etaSeconds = eta ?: it.etaSeconds,
                            progressMessage = combinedMsg.ifBlank { "Running via @gradio/client…" },
                            elapsedMs = System.currentTimeMillis() - startedAt
                        )
                    }
                }
                "data" -> {
                    sawData = true
                    val dataArr = ev.payload.optJSONArray("data") ?: JSONArray()
                    val parsed = parseOutputs(
                        dataArray = dataArr,
                        outputSpecs = endpoint.outputs,
                        hostUrl = manifest.identity.hostUrl,
                        apiPrefix = manifest.provenance.apiPrefix,
                        spaceId = manifest.identity.spaceId,
                        jobId = recordJobId,
                        token = token,
                        spaceJwt = spaceJwt,
                        cacheMedia = true
                    )
                    _outputs.value = parsed
                    _activeJob.update {
                        it.copy(
                            status = JobStatus.STREAMING,
                            isIntermediate = true,
                            progressMessage = "Received output stream…",
                            elapsedMs = System.currentTimeMillis() - startedAt
                        )
                    }
                }
                "done" -> {
                    val now = System.currentTimeMillis()
                    val finalOutputs = _outputs.value
                    _activeJob.update {
                        it.copy(
                            status = JobStatus.COMPLETED,
                            isIntermediate = false,
                            progressFraction = 1f,
                            progressMessage = "Completed via @gradio/client",
                            elapsedMs = now - startedAt
                        )
                    }
                    persistJobRecord(
                        jobId = recordJobId,
                        manifest = manifest,
                        endpoint = endpoint,
                        status = JobStatus.COMPLETED,
                        transport = currentTransport,
                        startedAt = startedAt,
                        finishedAt = now,
                        declaredGpuSec = declaredGpuSec,
                        summary = summarizeOutputs(finalOutputs),
                        error = null,
                        outputs = finalOutputs
                    )
                    SpaceJobMonitorService.notifyComplete(
                        context,
                        "${manifest.identity.title} finished",
                        summarizeOutputs(finalOutputs)
                    )
                    return JsAttemptOutcome.COMPLETED
                }
                "error" -> {
                    val msg = TokenRedactor.sanitize(ev.payload.optString("message", "Execution error"))
                    val isAuthOrQuota = isQuotaOrHigherUsageError(msg) || isAuthError(msg)
                    if (isAuthOrQuota && hasFallbackAuth) {
                        onDiagnostic("@gradio/client hit auth/usage limit; switching to fallback credential: $msg")
                        return JsAttemptOutcome.RETRY_NEXT_AUTH
                    }
                    if (!sawData && !isAuthOrQuota) {
                        onDiagnostic("@gradio/client stream error before data ($msg); falling back to Native Gradio REST/SSE…")
                        return JsAttemptOutcome.FALLBACK_TO_NATIVE
                    }
                    handleJobFailure(
                        recordJobId, manifest, endpoint, startedAt, declaredGpuSec,
                        error = msg,
                        isQuota = isQuotaOrHigherUsageError(msg),
                        onDiagnostic = onDiagnostic
                    )
                    return JsAttemptOutcome.FAILED
                }
            }
        }
    }

    private fun isQuotaOrHigherUsageError(msg: String): Boolean {
        val low = msg.lowercase()
        return low.contains("quota") ||
            low.contains("zerogpu") ||
            low.contains("gpu duration") ||
            low.contains("exceeded") ||
            low.contains("higher usage") ||
            low.contains("unauthenticated")
    }

    private fun isAuthError(msg: String): Boolean {
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

    /**
     * Builds arguments for JS SDK. If any binary file exceeds 6MB, returns null to use streaming
     * native multipart HTTP upload (avoids WebView IPC memory overhead for large videos/audio).
     */
    private suspend fun buildJsSdkArgsIfSmallEnough(
        fields: List<ComponentField>,
        values: Map<String, FieldValue>
    ): JSONArray? {
        val arr = JSONArray()
        for (field in fields) {
            val value = values[field.id] ?: defaultValueForField(field)
            when (value) {
                is FieldValue.MediaRef -> {
                    val obj = JSONObject().put("__spacedeck_file", true)
                    val localFile = resolveMediaFile(value)
                    if (localFile != null) {
                        if (localFile.length() > 6 * 1024 * 1024) return null // Prefer native multipart upload for >6MB
                        val b64 = Base64.encodeToString(localFile.readBytes(), Base64.NO_WRAP)
                        obj.put("base64", b64)
                        obj.put("fileName", value.fileName)
                        obj.put("mimeType", value.mimeType)
                        arr.put(obj)
                    } else if (!value.remoteUrl.isNullOrBlank()) {
                        obj.put("url", value.remoteUrl)
                        arr.put(obj)
                    } else {
                        arr.put(JSONObject.NULL)
                    }
                }
                is FieldValue.Multimodal -> return null // Handle multimodal natively for clean FileData dicts
                else -> arr.put(serializeSimpleValue(field, value))
            }
        }
        return arr
    }

    private suspend fun buildNativeArgsWithUploads(
        hostUrl: String,
        apiPrefix: String,
        fields: List<ComponentField>,
        values: Map<String, FieldValue>,
        token: String?,
        spaceJwt: String?,
        onDiagnostic: (String) -> Unit
    ): JSONArray {
        val arr = JSONArray()
        for (field in fields) {
            val value = values[field.id] ?: defaultValueForField(field)
            when (value) {
                is FieldValue.MediaRef -> {
                    val uploaded = uploadMediaRef(hostUrl, apiPrefix, value, token, spaceJwt, onDiagnostic)
                    arr.put(uploaded ?: JSONObject.NULL)
                }
                is FieldValue.Multimodal -> {
                    val fileObjects = JSONArray()
                    for (ref in value.files) {
                        uploadMediaRef(hostUrl, apiPrefix, ref, token, spaceJwt, onDiagnostic)?.let { fileObjects.put(it) }
                    }
                    arr.put(JSONObject().apply {
                        put("text", value.text)
                        put("files", fileObjects)
                    })
                }
                else -> arr.put(serializeSimpleValue(field, value))
            }
        }
        return arr
    }

    private suspend fun uploadMediaRef(
        hostUrl: String,
        apiPrefix: String,
        ref: FieldValue.MediaRef,
        token: String?,
        spaceJwt: String?,
        onDiagnostic: (String) -> Unit
    ): JSONObject? {
        val localFile = resolveMediaFile(ref)
        if (localFile != null && localFile.exists()) {
            onDiagnostic("Uploading ${ref.fileName} (${localFile.length() / 1024} KB)…")
            val res = gradioNativeClient.uploadFile(
                hostUrl = hostUrl,
                apiPrefix = apiPrefix,
                file = localFile,
                fileName = ref.fileName,
                mimeType = ref.mimeType,
                token = token,
                spaceJwt = spaceJwt
            )
            return res.getOrThrow()
        }
        if (!ref.remoteUrl.isNullOrBlank()) {
            return JSONObject().apply {
                put("url", ref.remoteUrl)
                put("orig_name", ref.fileName)
                put("mime_type", ref.mimeType)
                put("meta", JSONObject().put("_type", "gradio.FileData"))
            }
        }
        return null
    }

    private suspend fun resolveMediaFile(ref: FieldValue.MediaRef): File? {
        if (!ref.localPath.isNullOrBlank()) {
            val f = File(ref.localPath)
            if (f.exists()) return f
        }
        if (!ref.uriString.isNullOrBlank()) {
            val uri = runCatching { Uri.parse(ref.uriString) }.getOrNull() ?: return null
            val copied = mediaLibraryManager.copyLocalFileToTempUpload(uri).getOrNull()
            return copied?.first
        }
        return null
    }

    private fun serializeSimpleValue(field: ComponentField, value: FieldValue): Any {
        return when (value) {
            is FieldValue.Text -> value.value
            is FieldValue.NumberVal -> {
                if (field.apiDataType.equals("integer", true) || (field.step != null && field.step % 1.0 == 0.0 && value.value % 1.0 == 0.0)) {
                    value.value.toLong()
                } else {
                    value.value
                }
            }
            is FieldValue.BoolVal -> value.value
            is FieldValue.MultiSelect -> JSONArray(value.values)
            is FieldValue.StructuredJson -> {
                val trimmed = value.rawJson.trim()
                when {
                    trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }.getOrDefault(trimmed)
                    trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrDefault(trimmed)
                    trimmed == "null" || trimmed.isEmpty() -> JSONObject.NULL
                    else -> trimmed
                }
            }
            is FieldValue.Empty -> JSONObject.NULL
            else -> JSONObject.NULL
        }
    }

    private suspend fun parseOutputs(
        dataArray: JSONArray,
        outputSpecs: List<ComponentField>,
        hostUrl: String,
        apiPrefix: String,
        spaceId: String,
        jobId: String,
        token: String?,
        spaceJwt: String?,
        cacheMedia: Boolean
    ): List<OutputItem> {
        val result = mutableListOf<OutputItem>()
        for (i in 0 until dataArray.length()) {
            val raw = dataArray.opt(i)
            val spec = outputSpecs.getOrNull(i)
            val label = spec?.label ?: "Output ${i + 1}"
            val specKind = spec?.kind ?: ComponentKind.UNKNOWN
            result.add(
                interpretSingleOutput(
                    index = i,
                    label = label,
                    hintKind = specKind,
                    raw = raw,
                    hostUrl = hostUrl,
                    apiPrefix = apiPrefix,
                    spaceId = spaceId,
                    jobId = jobId,
                    token = token,
                    spaceJwt = spaceJwt,
                    cacheMedia = cacheMedia
                )
            )
        }
        return result
    }

    private suspend fun interpretSingleOutput(
        index: Int,
        label: String,
        hintKind: ComponentKind,
        raw: Any?,
        hostUrl: String,
        apiPrefix: String,
        spaceId: String,
        jobId: String,
        token: String?,
        spaceJwt: String?,
        cacheMedia: Boolean
    ): OutputItem {
        if (raw == null || raw === JSONObject.NULL) {
            return OutputItem(index = index, label = label, kind = hintKind, textValue = "(empty)", rawJson = "null")
        }

        // Handle Gradio Gallery (JSONArray of image objects/captions)
        if (hintKind == ComponentKind.GALLERY && raw is JSONArray) {
            val galleryItems = mutableListOf<OutputItem>()
            for (g in 0 until raw.length()) {
                val entry = raw.opt(g)
                val imgObj = if (entry is JSONObject && entry.has("image")) entry.opt("image") else entry
                val caption = if (entry is JSONObject) entry.optString("caption", "Image ${g + 1}") else "Image ${g + 1}"
                galleryItems.add(
                    interpretSingleOutput(g, caption, ComponentKind.IMAGE, imgObj, hostUrl, apiPrefix, spaceId, jobId, token, spaceJwt, cacheMedia)
                )
            }
            return OutputItem(index = index, label = label, kind = ComponentKind.GALLERY, galleryItems = galleryItems, rawJson = raw.toString())
        }

        // Handle Gradio Chatbot (JSONArray of [user, bot] pairs or {role, content} objects)
        if (hintKind == ComponentKind.CHATBOT && raw is JSONArray) {
            val msgs = mutableListOf<Pair<String, String>>()
            for (c in 0 until raw.length()) {
                val item = raw.opt(c)
                when (item) {
                    is JSONArray -> {
                        val u = item.optString(0, "")
                        val b = item.optString(1, "")
                        if (u.isNotBlank()) msgs.add("User" to u)
                        if (b.isNotBlank()) msgs.add("Space" to b)
                    }
                    is JSONObject -> {
                        val role = item.optString("role", "Space")
                        val content = item.optString("content", "")
                        msgs.add(role to content)
                    }
                }
            }
            return OutputItem(index = index, label = label, kind = ComponentKind.CHATBOT, chatMessages = msgs, rawJson = raw.toString())
        }

        // Handle Gradio DataFrame ({headers: [...], data: [[...]]})
        if (raw is JSONObject && raw.has("headers") && raw.has("data") && raw.opt("data") is JSONArray) {
            val hArr = raw.optJSONArray("headers") ?: JSONArray()
            val dArr = raw.optJSONArray("data") ?: JSONArray()
            val headers = (0 until hArr.length()).map { hArr.optString(it) }
            val rows = (0 until dArr.length()).map { r ->
                val rowArr = dArr.optJSONArray(r) ?: JSONArray()
                (0 until rowArr.length()).map { c -> rowArr.optString(c) }
            }
            return OutputItem(index = index, label = label, kind = ComponentKind.DATAFRAME, tableHeaders = headers, tableRows = rows, rawJson = raw.toString())
        }

        // Handle Gradio FileData / Media object ({url, path, orig_name, mime_type} or {video: {...}})
        if (raw is JSONObject) {
            val nestedVideo = raw.optJSONObject("video")
            val mediaObj = nestedVideo ?: raw
            val urlField = mediaObj.optString("url", "")
            val pathField = mediaObj.optString("path", "")
            if (urlField.isNotBlank() || pathField.isNotBlank()) {
                val resolvedUrl = resolveRemoteFileUrl(urlField, pathField, hostUrl, apiPrefix)
                val downloadUrl = GradioNativeClient.appendSignParam(resolvedUrl, spaceJwt)
                val fileName = mediaObj.optString("orig_name", "").ifBlank {
                    resolvedUrl.substringAfterLast('/').substringBefore('?').ifBlank { "output_$index" }
                }
                val mime = mediaObj.optString("mime_type", "").ifBlank { guessMimeFromName(fileName, hintKind) }
                val resolvedKind = refineMediaKind(hintKind, mime, fileName)

                var cachedPath: String? = null
                var assetId: String? = null
                if (cacheMedia && resolvedUrl.startsWith("https://")) {
                    val cached = mediaLibraryManager.cacheRemoteMedia(
                        remoteUrl = downloadUrl,
                        spaceId = spaceId,
                        jobId = jobId,
                        fileNameHint = fileName,
                        mimeTypeHint = mime,
                        token = token
                    ).getOrNull()
                    if (cached != null) {
                        cachedPath = cached.localFilePath
                        assetId = cached.id
                    }
                }

                return OutputItem(
                    index = index,
                    label = label,
                    kind = resolvedKind,
                    mediaUrl = resolvedUrl,
                    localCachedPath = cachedPath,
                    mimeType = mime,
                    fileName = fileName,
                    rawJson = raw.toString(),
                    mediaAssetId = assetId
                )
            }
            return OutputItem(index = index, label = label, kind = ComponentKind.JSON, textValue = raw.toString(2), rawJson = raw.toString())
        }

        // Handle direct URL string to media
        val str = raw.toString()
        if (str.startsWith("https://") && (hintKind in setOf(ComponentKind.IMAGE, ComponentKind.AUDIO, ComponentKind.VIDEO, ComponentKind.FILE))) {
            val fileName = str.substringAfterLast('/').substringBefore('?').ifBlank { "output_$index" }
            val mime = guessMimeFromName(fileName, hintKind)
            var cachedPath: String? = null
            var assetId: String? = null
            if (cacheMedia) {
                val downloadUrl = GradioNativeClient.appendSignParam(str, spaceJwt)
                mediaLibraryManager.cacheRemoteMedia(downloadUrl, spaceId, jobId, fileName, mime, token).getOrNull()?.let {
                    cachedPath = it.localFilePath
                    assetId = it.id
                }
            }
            return OutputItem(
                index = index,
                label = label,
                kind = refineMediaKind(hintKind, mime, fileName),
                mediaUrl = str,
                localCachedPath = cachedPath,
                mimeType = mime,
                fileName = fileName,
                rawJson = JSONObject.quote(str),
                mediaAssetId = assetId
            )
        }

        val finalKind = if (raw is JSONArray) ComponentKind.JSON else if (hintKind == ComponentKind.UNKNOWN) ComponentKind.TEXTBOX else hintKind
        return OutputItem(
            index = index,
            label = label,
            kind = finalKind,
            textValue = if (raw is JSONArray) raw.toString(2) else str,
            rawJson = raw.toString()
        )
    }

    private fun resolveRemoteFileUrl(urlField: String, pathField: String, hostUrl: String, apiPrefix: String): String {
        val base = hostUrl.trimEnd('/')
        if (urlField.startsWith("http://") || urlField.startsWith("https://")) return urlField
        if (urlField.startsWith("/")) return "$base$urlField"
        val prefix = apiPrefix.ifBlank { "/gradio_api" }
        return "$base$prefix/file=$pathField"
    }

    private fun refineMediaKind(hint: ComponentKind, mime: String, fileName: String): ComponentKind {
        val m = mime.lowercase()
        val f = fileName.lowercase()
        return when {
            m.startsWith("image/") || f.endsWith(".png") || f.endsWith(".jpg") || f.endsWith(".jpeg") || f.endsWith(".webp") -> ComponentKind.IMAGE
            m.startsWith("audio/") || f.endsWith(".wav") || f.endsWith(".mp3") || f.endsWith(".flac") || f.endsWith(".m4a") || f.endsWith(".ogg") -> ComponentKind.AUDIO
            m.startsWith("video/") || f.endsWith(".mp4") || f.endsWith(".webm") || f.endsWith(".mov") -> ComponentKind.VIDEO
            hint in setOf(ComponentKind.IMAGE, ComponentKind.AUDIO, ComponentKind.VIDEO, ComponentKind.FILE) -> hint
            else -> ComponentKind.FILE
        }
    }

    private fun guessMimeFromName(fileName: String, hint: ComponentKind): String {
        val f = fileName.lowercase()
        return when {
            f.endsWith(".png") -> "image/png"
            f.endsWith(".jpg") || f.endsWith(".jpeg") -> "image/jpeg"
            f.endsWith(".webp") -> "image/webp"
            f.endsWith(".wav") -> "audio/wav"
            f.endsWith(".mp3") -> "audio/mpeg"
            f.endsWith(".m4a") -> "audio/mp4"
            f.endsWith(".flac") -> "audio/flac"
            f.endsWith(".mp4") -> "video/mp4"
            f.endsWith(".webm") -> "video/webm"
            hint == ComponentKind.IMAGE -> "image/png"
            hint == ComponentKind.AUDIO -> "audio/wav"
            hint == ComponentKind.VIDEO -> "video/mp4"
            else -> "application/octet-stream"
        }
    }

    private suspend fun handleJobFailure(
        jobId: String,
        manifest: SpaceManifest,
        endpoint: EndpointManifest,
        startedAt: Long,
        declaredGpuSec: Int?,
        error: String,
        isQuota: Boolean,
        onDiagnostic: (String) -> Unit
    ) {
        val now = System.currentTimeMillis()
        val friendlyError = when {
            isQuota -> "ZeroGPU quota limit or higher-usage authentication required. Verify your Hugging Face Access Token or Sign in with HF OAuth in Vault, or retry after quota resets.\nDetails: $error"
            error.contains("503") || error.contains("sleeping", true) -> "Space is waking up or paused. Give it a moment, or open Full Space View to trigger startup."
            error.contains("401") || error.contains("403") -> "Authentication error. Check your Hugging Face Access Token or OAuth sign-in in Vault."
            else -> error
        }
        _activeJob.update {
            it.copy(
                status = JobStatus.FAILED,
                progressMessage = if (isQuota) "ZeroGPU Quota Limit" else "Failed",
                quotaMessage = if (isQuota) error else null,
                errorMessage = friendlyError,
                elapsedMs = now - startedAt
            )
        }
        persistJobRecord(
            jobId = jobId,
            manifest = manifest,
            endpoint = endpoint,
            status = JobStatus.FAILED,
            transport = currentTransport,
            startedAt = startedAt,
            finishedAt = now,
            declaredGpuSec = declaredGpuSec,
            summary = "Failed",
            error = friendlyError,
            outputs = _outputs.value
        )
        SpaceJobMonitorService.notifyComplete(context, "${manifest.identity.title} failed", friendlyError.take(120))
        onDiagnostic("Job failed: $friendlyError")
    }

    private suspend fun persistJobRecord(
        jobId: String,
        manifest: SpaceManifest,
        endpoint: EndpointManifest,
        status: JobStatus,
        transport: String,
        startedAt: Long,
        finishedAt: Long?,
        declaredGpuSec: Int?,
        summary: String,
        error: String?,
        outputs: List<OutputItem>
    ) {
        val outSummaryJson = JSONArray().apply {
            outputs.forEach { o ->
                put(JSONObject().apply {
                    put("label", o.label)
                    put("kind", o.kind.name)
                    o.textValue?.let { put("text", it.take(300)) }
                    o.localCachedPath?.let { put("localPath", it) }
                    o.mediaAssetId?.let { put("mediaAssetId", it) }
                })
            }
        }.toString()

        jobRecordDao.upsertJob(
            JobRecordEntity(
                jobId = jobId,
                spaceId = manifest.identity.spaceId,
                endpointLabel = endpoint.label,
                status = status.name,
                transport = transport,
                startedAtMs = startedAt,
                finishedAtMs = finishedAt,
                elapsedMs = (finishedAt ?: System.currentTimeMillis()) - startedAt,
                declaredZeroGpuSeconds = declaredGpuSec,
                summaryText = summary,
                errorMessage = error,
                outputsJson = outSummaryJson
            )
        )
    }

    private fun summarizeOutputs(items: List<OutputItem>): String {
        if (items.isEmpty()) return "No outputs returned"
        return items.joinToString(", ") { item ->
            when (item.kind) {
                ComponentKind.IMAGE -> "Image (${item.fileName ?: item.label})"
                ComponentKind.GALLERY -> "Gallery (${item.galleryItems.size} items)"
                ComponentKind.AUDIO -> "Audio (${item.fileName ?: item.label})"
                ComponentKind.VIDEO -> "Video (${item.fileName ?: item.label})"
                ComponentKind.FILE -> "File (${item.fileName ?: item.label})"
                else -> item.textValue?.take(48)?.replace("\n", " ") ?: item.label
            }
        }
    }

    companion object {
        fun defaultValueForField(field: ComponentField): FieldValue {
            val raw = field.defaultValueJson?.trim()
            return when (field.kind) {
                ComponentKind.SLIDER, ComponentKind.NUMBER -> {
                    val d = raw?.toDoubleOrNull() ?: field.min ?: 0.0
                    FieldValue.NumberVal(d)
                }
                ComponentKind.CHECKBOX -> {
                    FieldValue.BoolVal(raw.equals("true", ignoreCase = true))
                }
                ComponentKind.CHECKBOX_GROUP -> {
                    val parsed = if (raw != null && raw.startsWith("[")) {
                        runCatching {
                            val a = JSONArray(raw)
                            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
                        }.getOrDefault(emptyList())
                    } else emptyList()
                    FieldValue.MultiSelect(parsed)
                }
                ComponentKind.DROPDOWN -> {
                    if (field.multiSelect) {
                        val parsed = if (raw != null && raw.startsWith("[")) {
                            runCatching {
                                val a = JSONArray(raw)
                                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
                            }.getOrDefault(emptyList())
                        } else emptyList()
                        FieldValue.MultiSelect(parsed)
                    } else {
                        val s = unquoteJson(raw) ?: field.choices.firstOrNull().orEmpty()
                        FieldValue.Text(s)
                    }
                }
                ComponentKind.RADIO -> {
                    val s = unquoteJson(raw) ?: field.choices.firstOrNull().orEmpty()
                    FieldValue.Text(s)
                }
                ComponentKind.IMAGE, ComponentKind.AUDIO, ComponentKind.VIDEO, ComponentKind.FILE -> FieldValue.Empty
                ComponentKind.MULTIMODAL -> FieldValue.Multimodal(text = unquoteJson(raw).orEmpty())
                ComponentKind.JSON, ComponentKind.UNKNOWN, ComponentKind.DATAFRAME -> {
                    FieldValue.StructuredJson(raw ?: "")
                }
                ComponentKind.STATE -> FieldValue.Empty
                else -> FieldValue.Text(unquoteJson(raw).orEmpty())
            }
        }

        private fun unquoteJson(raw: String?): String? {
            if (raw == null || raw == "null") return null
            return if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
                runCatching { JSONObject("{\"v\":$raw}").getString("v") }.getOrDefault(raw.trim('"'))
            } else raw
        }
    }
}
