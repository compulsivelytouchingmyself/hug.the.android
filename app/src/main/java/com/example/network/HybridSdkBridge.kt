package com.example.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.example.security.AllowedHosts
import com.example.security.TokenRedactor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class SdkJobEvent(
    val jobId: String,
    val type: String,
    val payload: JSONObject
)

/**
 * Headless local-origin WebView hosting the pinned official JS SDKs:
 *   - @huggingface/hub@2.17.11
 *   - @gradio/client@2.8.0
 *
 * Strictly isolated to https://appassets.androidplatform.net/assets/sdk/runtime.html.
 * NEVER loads remote Space HTML or untrusted scripts.
 *
 * Provides two host-allowlisted native OkHttp bridges:
 *   1. nativeFetchAsync       — for ordinary REST & multipart uploads (non-SSE)
 *   2. nativeStreamFetchAsync — incremental chunk-by-chunk streaming for Server-Sent Events (SSE)
 */
class HybridSdkBridge(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val sseHttpClient: OkHttpClient = okHttpClient.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS) // Required for long-lived SSE streams
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callSeq = AtomicInteger(0)
    private val pendingCalls = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val activeStreams = ConcurrentHashMap<String, Call>()
    private val jobEventChannels = ConcurrentHashMap<String, Channel<SdkJobEvent>>()
    private val readyDeferred = CompletableDeferred<Boolean>()

    private val _jobEvents = MutableSharedFlow<SdkJobEvent>(
        replay = 16,
        extraBufferCapacity = 128
    )
    val jobEvents: SharedFlow<SdkJobEvent> = _jobEvents.asSharedFlow()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    var sdkVersionsLabel: String = "@huggingface/hub@2.17.11 + @gradio/client@2.8.0"
        private set

    /**
     * Registers a dedicated buffered channel for a job BEFORE calling submitJob(),
     * guaranteeing that fast JS events emitted right after submission are never dropped.
     */
    fun openJobEventChannel(jobId: String): Channel<SdkJobEvent> {
        val channel = Channel<SdkJobEvent>(capacity = Channel.UNLIMITED)
        jobEventChannels[jobId] = channel
        return channel
    }

    fun closeJobEventChannel(jobId: String) {
        jobEventChannels.remove(jobId)?.close()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun ensureInitialized() {
        if (webView != null) return
        mainHandler.post {
            if (webView != null) return@post
            try {
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .build()

                val wv = WebView(context.applicationContext)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        return assetLoader.shouldInterceptRequest(request.url)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        // Never navigate the headless SDK runner away from local appassets
                        return true
                    }
                }
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
                }
                wv.addJavascriptInterface(JsBridgeReceiver(), "SpaceDeckBridge")
                webView = wv
                wv.loadUrl("https://appassets.androidplatform.net/assets/sdk/runtime.html")
            } catch (e: Throwable) {
                readyDeferred.complete(false)
            }
        }
    }

    suspend fun awaitReady(timeoutMs: Long = 6000L): Boolean {
        ensureInitialized()
        return runCatching {
            withTimeout(timeoutMs) { readyDeferred.await() }
        }.getOrDefault(false)
    }

    suspend fun whoAmI(token: String): Result<JSONObject> = invokeSdk(
        jsCall = { callId -> "window.SpaceDeckSdk.whoAmI(${JSONObject.quote(callId)}, ${JSONObject.quote(token)});" },
        timeoutMs = 15_000L
    )

    suspend fun listFiles(spaceId: String, token: String?): Result<JSONObject> = invokeSdk(
        jsCall = { callId ->
            "window.SpaceDeckSdk.listFiles(${JSONObject.quote(callId)}, ${JSONObject.quote(spaceId)}, ${quoteNullable(token)});"
        },
        timeoutMs = 20_000L
    )

    suspend fun downloadFile(spaceId: String, path: String, token: String?): Result<JSONObject> = invokeSdk(
        jsCall = { callId ->
            "window.SpaceDeckSdk.downloadFile(${JSONObject.quote(callId)}, ${JSONObject.quote(spaceId)}, ${JSONObject.quote(path)}, ${quoteNullable(token)});"
        },
        timeoutMs = 20_000L
    )

    suspend fun connectAndInspect(
        sessionKey: String,
        spaceRef: String,
        token: String?,
        spaceJwt: String? = null
    ): Result<JSONObject> = invokeSdk(
        jsCall = { callId ->
            "window.SpaceDeckSdk.connectAndInspect(${JSONObject.quote(callId)}, ${JSONObject.quote(sessionKey)}, ${JSONObject.quote(spaceRef)}, ${quoteNullable(token)}, ${quoteNullable(spaceJwt)});"
        },
        timeoutMs = 25_000L
    )

    suspend fun submitJob(
        jobId: String,
        sessionKey: String,
        spaceRef: String,
        token: String?,
        spaceJwt: String? = null,
        endpoint: String,
        rawArgsJson: String
    ): Result<JSONObject> = invokeSdk(
        jsCall = { callId ->
            "window.SpaceDeckSdk.submitJob(${JSONObject.quote(callId)}, ${JSONObject.quote(jobId)}, ${JSONObject.quote(sessionKey)}, ${JSONObject.quote(spaceRef)}, ${quoteNullable(token)}, ${quoteNullable(spaceJwt)}, ${JSONObject.quote(endpoint)}, ${JSONObject.quote(rawArgsJson)});"
        },
        timeoutMs = 30_000L
    )

    suspend fun cancelJob(jobId: String): Result<JSONObject> = invokeSdk(
        jsCall = { callId ->
            "window.SpaceDeckSdk.cancelJob(${JSONObject.quote(callId)}, ${JSONObject.quote(jobId)});"
        },
        timeoutMs = 10_000L
    )

    fun closeSession(sessionKey: String) {
        val wv = webView ?: return
        mainHandler.post {
            wv.evaluateJavascript("window.SpaceDeckSdk && window.SpaceDeckSdk.closeSession(${JSONObject.quote(sessionKey)});", null)
        }
    }

    fun clearAllSessions() {
        activeStreams.values.forEach { runCatching { it.cancel() } }
        activeStreams.clear()
        val wv = webView ?: return
        mainHandler.post {
            wv.evaluateJavascript("window.SpaceDeckSdk && window.SpaceDeckSdk.clearAllSessions();", null)
        }
    }

    private suspend fun invokeSdk(
        jsCall: (String) -> String,
        timeoutMs: Long
    ): Result<JSONObject> {
        if (!awaitReady()) {
            return Result.failure(IllegalStateException("JS SDK runtime not initialized"))
        }
        val callId = "call_${callSeq.incrementAndGet()}"
        val deferred = CompletableDeferred<JSONObject>()
        pendingCalls[callId] = deferred

        val wv = webView
        if (wv == null) {
            pendingCalls.remove(callId)
            return Result.failure(IllegalStateException("SDK WebView unavailable"))
        }

        mainHandler.post {
            wv.evaluateJavascript(jsCall(callId), null)
        }

        return try {
            val result = withTimeout(timeoutMs) { deferred.await() }
            if (result.optBoolean("ok", false)) {
                Result.success(result)
            } else {
                Result.failure(IllegalStateException(TokenRedactor.sanitize(result.optString("error", "SDK error"))))
            }
        } catch (e: Exception) {
            pendingCalls.remove(callId)
            Result.failure(IllegalStateException(TokenRedactor.sanitize(e.message ?: "SDK call timed out")))
        }
    }

    private fun quoteNullable(value: String?): String =
        if (value == null) "null" else JSONObject.quote(value)

    private fun postJs(js: String) {
        val wv = webView ?: return
        mainHandler.post { wv.evaluateJavascript(js, null) }
    }

    private inner class JsBridgeReceiver {
        @JavascriptInterface
        fun onSdkReady(payloadJson: String) {
            runCatching {
                val obj = JSONObject(payloadJson)
                val v = obj.optJSONObject("versions")
                if (v != null) {
                    sdkVersionsLabel = "@huggingface/hub@${v.optString("huggingfaceHub", "2.17.11")} + @gradio/client@${v.optString("gradioClient", "2.8.0")}"
                }
            }
            readyDeferred.complete(true)
        }

        @JavascriptInterface
        fun onSdkResult(payloadJson: String) {
            runCatching {
                val obj = JSONObject(payloadJson)
                val callId = obj.optString("callId")
                pendingCalls.remove(callId)?.complete(obj)
            }
        }

        @JavascriptInterface
        fun onJobEvent(payloadJson: String) {
            runCatching {
                val obj = JSONObject(payloadJson)
                val jobId = obj.optString("jobId")
                val type = obj.optString("type")
                val payload = obj.optJSONObject("payload") ?: JSONObject()
                val event = SdkJobEvent(jobId = jobId, type = type, payload = payload)
                jobEventChannels[jobId]?.trySend(event)
                _jobEvents.tryEmit(event)
            }
        }

        @JavascriptInterface
        fun nativeFetchAsync(reqId: String, reqJson: String) {
            scope.launch {
                val responsePayload = executeNativeFetch(reqJson)
                postJs("window.__resolveNativeFetch(${JSONObject.quote(reqId)}, ${JSONObject.quote(responsePayload)});")
            }
        }

        @JavascriptInterface
        fun nativeStreamFetchAsync(streamId: String, reqJson: String) {
            scope.launch {
                executeNativeStreamFetch(streamId, reqJson)
            }
        }

        @JavascriptInterface
        fun nativeStreamCancel(streamId: String) {
            activeStreams.remove(streamId)?.cancel()
        }
    }

    private fun buildNativeRequest(reqJson: String): Result<Request> = runCatching {
        val reqObj = JSONObject(reqJson)
        val url = reqObj.getString("url")
        if (!AllowedHosts.isAllowedNetworkTarget(url)) {
            throw SecurityException("Blocked fetch to untrusted host")
        }
        val method = reqObj.optString("method", "GET").uppercase()
        val headersObj = reqObj.optJSONObject("headers")
        val bodyText = if (reqObj.has("bodyText") && !reqObj.isNull("bodyText")) reqObj.getString("bodyText") else null
        val bodyBase64 = if (reqObj.has("bodyBase64") && !reqObj.isNull("bodyBase64")) reqObj.getString("bodyBase64") else null

        val builder = Request.Builder().url(url)
        var contentType: String? = null
        if (headersObj != null) {
            val keys = headersObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = headersObj.optString(k)
                // Never forward Authorization to non-HF hosts
                if (k.equals("Authorization", ignoreCase = true) && !AllowedHosts.isTrustedForCredentials(url)) {
                    continue
                }
                if (k.equals("Content-Type", ignoreCase = true)) {
                    contentType = v
                }
                builder.header(k, v)
            }
        }

        if (method == "POST" || method == "PUT" || method == "PATCH" || method == "DELETE") {
            val mediaType = (contentType ?: "application/json").toMediaTypeOrNull()
            val reqBody = when {
                bodyBase64 != null -> Base64.decode(bodyBase64, Base64.DEFAULT).toRequestBody(mediaType)
                bodyText != null -> bodyText.toRequestBody(mediaType)
                else -> ByteArray(0).toRequestBody(mediaType)
            }
            builder.method(method, reqBody)
        } else {
            builder.method(method, null)
        }
        builder.build()
    }

    /**
     * Non-streaming REST & multipart upload bridge.
     * Explicitly guards against buffering SSE (text/event-stream) responses.
     */
    private fun executeNativeFetch(reqJson: String): String {
        return try {
            val request = buildNativeRequest(reqJson).getOrThrow()
            okHttpClient.newCall(request).execute().use { resp ->
                val ct = resp.header("Content-Type").orEmpty()
                if (ct.contains("text/event-stream", ignoreCase = true)) {
                    return JSONObject()
                        .put("error", "SSE response received on buffered REST bridge; use nativeStreamFetchAsync")
                        .toString()
                }
                val respHeaders = JSONObject()
                resp.headers.names().forEach { name ->
                    respHeaders.put(name, resp.header(name))
                }
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val isText = ct.contains("json", true) || ct.contains("text", true) || ct.contains("javascript", true)
                JSONObject().apply {
                    put("status", resp.code)
                    put("statusText", resp.message)
                    put("headers", respHeaders)
                    if (isText) {
                        put("bodyText", String(bytes, Charsets.UTF_8))
                    } else {
                        put("bodyBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    }
                }.toString()
            }
        } catch (e: Exception) {
            JSONObject().put("error", TokenRedactor.sanitize(e.message ?: "Native fetch failed")).toString()
        }
    }

    /**
     * Incremental streaming OkHttp bridge for Server-Sent Events (SSE).
     * Releases response headers immediately and streams chunks to JS as soon as bytes arrive
     * from the socket—never buffering the full response body.
     */
    private fun executeNativeStreamFetch(streamId: String, reqJson: String) {
        val request = buildNativeRequest(reqJson).getOrElse { err ->
            val msg = TokenRedactor.sanitize(err.message ?: "Invalid stream request")
            postJs("window.__onNativeStreamError(${JSONObject.quote(streamId)}, ${JSONObject.quote(msg)});")
            return
        }

        val call = sseHttpClient.newCall(request)
        activeStreams[streamId] = call
        try {
            call.execute().use { resp ->
                val respHeaders = JSONObject()
                resp.headers.names().forEach { name ->
                    respHeaders.put(name, resp.header(name))
                }
                val metaJson = JSONObject().apply {
                    put("status", resp.code)
                    put("statusText", resp.message)
                    put("headers", respHeaders)
                }.toString()
                postJs("window.__onNativeStreamOpen(${JSONObject.quote(streamId)}, ${JSONObject.quote(metaJson)});")

                val inputStream = resp.body?.byteStream()
                if (inputStream != null) {
                    val buffer = ByteArray(4096)
                    while (true) {
                        val read = inputStream.read(buffer)
                        if (read == -1) break
                        if (read > 0) {
                            val b64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                            postJs("window.__onNativeStreamChunk(${JSONObject.quote(streamId)}, ${JSONObject.quote(b64)});")
                        }
                    }
                }
                postJs("window.__onNativeStreamClose(${JSONObject.quote(streamId)});")
            }
        } catch (e: Exception) {
            val msg = TokenRedactor.sanitize(e.message ?: "Native SSE stream failed")
            postJs("window.__onNativeStreamError(${JSONObject.quote(streamId)}, ${JSONObject.quote(msg)});")
        } finally {
            activeStreams.remove(streamId)
        }
    }
}
