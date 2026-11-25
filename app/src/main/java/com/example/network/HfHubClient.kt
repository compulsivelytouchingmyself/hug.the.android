package com.example.network

import com.example.model.AccountInfo
import com.example.model.RepoFileEntry
import com.example.model.RuntimeStage
import com.example.model.SpaceSummary
import com.example.security.AllowedHosts
import com.example.security.TokenRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class HubSpaceMetadata(
    val spaceId: String,
    val author: String,
    val repoName: String,
    val title: String,
    val emoji: String,
    val shortDescription: String,
    val subdomain: String,
    val hostUrl: String,
    val revisionSha: String,
    val sdk: String,
    val sdkVersion: String,
    val runtimeStage: RuntimeStage,
    val hardwareCurrent: String,
    val hardwareRequested: String,
    val isZeroGpu: Boolean,
    val sleepTimeSeconds: Int?,
    val privateSpace: Boolean,
    val likes: Int,
    val tags: List<String>
)

class HfHubClient(
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    private fun buildAuthorizedRequest(url: String, token: String?): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "SpaceDeck-Android/1.0")
            .header("Accept", "application/json")
        if (!token.isNullOrBlank() && AllowedHosts.isTrustedForCredentials(url)) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder.build()
    }

    suspend fun whoAmI(token: String): Result<AccountInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://huggingface.co/api/whoami-v2"
            val req = buildAuthorizedRequest(url, token)
            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = when (resp.code) {
                        401 -> "Invalid or expired Hugging Face token (HTTP 401)."
                        403 -> "Token lacks required permissions (HTTP 403)."
                        else -> "Verification failed (HTTP ${resp.code}): ${TokenRedactor.sanitize(body.take(180))}"
                    }
                    throw IllegalStateException(msg)
                }
                val json = JSONObject(body)
                val orgsArr = json.optJSONArray("orgs") ?: JSONArray()
                val orgNames = (0 until orgsArr.length()).mapNotNull { i ->
                    orgsArr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
                }
                AccountInfo(
                    username = json.optString("name", "unknown"),
                    fullName = json.optString("fullname", ""),
                    avatarUrl = json.optString("avatarUrl", ""),
                    isPro = json.optBoolean("isPro", false),
                    accountType = json.optString("type", "user"),
                    orgs = orgNames,
                    verifiedAtMs = System.currentTimeMillis()
                )
            }
        }
    }

    /**
     * Exchanges the user's HF access token or OAuth token for a Space-scoped signed JWT
     * required by ZeroGPU and private Spaces (?__sign=<jwt>).
     */
    suspend fun fetchSpaceJwt(spaceId: String, token: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanSpaceId = spaceId.trim().trim('/')
            require(cleanSpaceId.contains("/")) { "Invalid Space ID for JWT exchange." }
            val url = "https://huggingface.co/api/spaces/$cleanSpaceId/jwt"
            val req = buildAuthorizedRequest(url, token)
            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Space JWT exchange failed (HTTP ${resp.code}): ${TokenRedactor.sanitize(body.take(140))}")
                }
                val jwt = JSONObject(body).optString("token", "").trim()
                if (jwt.isBlank()) {
                    throw IllegalStateException("Space JWT response did not contain a token.")
                }
                jwt
            }
        }
    }

    suspend fun fetchZeroGpuQuota(token: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://huggingface.co/api/spaces/zero-gpu/quota"
            val req = buildAuthorizedRequest(url, token)
            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("ZeroGPU quota lookup failed (HTTP ${resp.code})")
                }
                body
            }
        }
    }

    suspend fun searchSpaces(
        query: String = "",
        sort: String = "trendingScore",
        author: String? = null,
        hardwareFilter: String? = null,
        categoryTag: String? = null,
        limit: Int = 30,
        token: String? = null
    ): Result<List<SpaceSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val params = mutableListOf(
                "limit=$limit",
                "full=true",
                "sdk=gradio",
                "sort=${URLEncoder.encode(sort, "UTF-8")}",
                "direction=-1"
            )
            if (query.isNotBlank()) {
                params.add("search=${URLEncoder.encode(query.trim(), "UTF-8")}")
            }
            if (!author.isNullOrBlank()) {
                params.add("author=${URLEncoder.encode(author.trim(), "UTF-8")}")
            }
            if (!categoryTag.isNullOrBlank()) {
                params.add("search=${URLEncoder.encode(categoryTag.trim(), "UTF-8")}")
            }
            val url = "https://huggingface.co/api/spaces?${params.joinToString("&")}"
            val req = buildAuthorizedRequest(url, token)
            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Space search failed (HTTP ${resp.code}): ${TokenRedactor.sanitize(body.take(160))}")
                }
                val arr = JSONArray(body)
                val list = mutableListOf<SpaceSummary>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val summary = parseSpaceSummary(o)
                    if (hardwareFilter != null) {
                        val matchesHw = when (hardwareFilter) {
                            "zero" -> summary.isZeroGpu
                            "gpu" -> summary.hardware.contains("gpu", ignoreCase = true) ||
                                summary.hardware.contains("a10", ignoreCase = true) ||
                                summary.hardware.contains("a100", ignoreCase = true) ||
                                summary.hardware.contains("h100", ignoreCase = true) ||
                                summary.hardware.contains("l4", ignoreCase = true) ||
                                summary.hardware.contains("t4", ignoreCase = true) ||
                                summary.isZeroGpu
                            "cpu" -> summary.hardware.startsWith("cpu", ignoreCase = true)
                            else -> true
                        }
                        if (!matchesHw) continue
                    }
                    list.add(summary)
                }
                list
            }
        }
    }

    suspend fun getSpaceMetadata(spaceId: String, token: String? = null): Result<HubSpaceMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://huggingface.co/api/spaces/$spaceId"
            val req = buildAuthorizedRequest(url, token)
            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val hint = when (resp.code) {
                        401, 403 -> "This Space is private or gated. Add a Hugging Face token in Vault to access it."
                        404 -> "Space '$spaceId' was not found on Hugging Face."
                        else -> "Failed to fetch Space metadata (HTTP ${resp.code})."
                    }
                    throw IllegalStateException(hint)
                }
                val o = JSONObject(body)
                val id = o.optString("id", spaceId)
                val parts = id.split("/")
                val author = o.optString("author", parts.firstOrNull().orEmpty())
                val repoName = parts.getOrNull(1) ?: id
                val cardData = o.optJSONObject("cardData") ?: JSONObject()
                val runtime = o.optJSONObject("runtime") ?: JSONObject()
                val hwObj = runtime.optJSONObject("hardware") ?: JSONObject()
                val hwCurrent = hwObj.optString("current", cardData.optString("suggested_hardware", "cpu-basic"))
                val hwRequested = hwObj.optString("requested", hwCurrent)
                val subdomain = o.optString("subdomain", SpaceIdentifierParser.computeSubdomain(author, repoName))
                val hostRaw = o.optString("host", "").ifBlank { "https://$subdomain.hf.space" }
                val hostUrl = if (hostRaw.startsWith("http")) hostRaw else "https://$hostRaw"
                val tagsArr = o.optJSONArray("tags") ?: JSONArray()
                val tags = (0 until tagsArr.length()).map { tagsArr.optString(it) }.filter { it.isNotBlank() }
                val isZero = hwCurrent.contains("zero", ignoreCase = true) ||
                    hwRequested.contains("zero", ignoreCase = true) ||
                    tags.any { it.equals("zero", ignoreCase = true) || it.contains("zerogpu", ignoreCase = true) }

                val sleepTime = if (runtime.has("gcTimeout") && !runtime.isNull("gcTimeout")) {
                    runtime.optInt("gcTimeout")
                } else null

                HubSpaceMetadata(
                    spaceId = id,
                    author = author,
                    repoName = repoName,
                    title = cardData.optString("title", repoName).ifBlank { repoName },
                    emoji = cardData.optString("emoji", ""),
                    shortDescription = cardData.optString("short_description", ""),
                    subdomain = subdomain,
                    hostUrl = hostUrl.trimEnd('/'),
                    revisionSha = o.optString("sha", ""),
                    sdk = o.optString("sdk", cardData.optString("sdk", "gradio")),
                    sdkVersion = cardData.optString("sdk_version", ""),
                    runtimeStage = RuntimeStage.fromRaw(runtime.optString("stage")),
                    hardwareCurrent = hwCurrent.ifBlank { "unknown" },
                    hardwareRequested = hwRequested.ifBlank { "unknown" },
                    isZeroGpu = isZero,
                    sleepTimeSeconds = sleepTime,
                    privateSpace = o.optBoolean("private", false),
                    likes = o.optInt("likes", 0),
                    tags = tags
                )
            }
        }
    }

    suspend fun listFiles(spaceId: String, token: String? = null): Result<List<RepoFileEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://huggingface.co/api/spaces/$spaceId/tree/main?recursive=true"
            val req = buildAuthorizedRequest(url, token)
            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Could not list Space repository files (HTTP ${resp.code}).")
                }
                val arr = JSONArray(body)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    RepoFileEntry(
                        path = o.optString("path"),
                        type = o.optString("type", "file"),
                        size = o.optLong("size", 0L)
                    )
                }.filter { it.path.isNotBlank() }
            }
        }
    }

    suspend fun downloadFileText(spaceId: String, filePath: String, token: String? = null): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedPath = filePath.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val url = "https://huggingface.co/spaces/$spaceId/raw/main/$encodedPath"
            val req = buildAuthorizedRequest(url, token)
            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Could not download $filePath (HTTP ${resp.code}).")
                }
                body
            }
        }
    }

    private fun parseSpaceSummary(o: JSONObject): SpaceSummary {
        val id = o.optString("id", "")
        val parts = id.split("/")
        val author = o.optString("author", parts.firstOrNull().orEmpty())
        val repoName = parts.getOrNull(1) ?: id
        val cardData = o.optJSONObject("cardData") ?: JSONObject()
        val runtime = o.optJSONObject("runtime") ?: JSONObject()
        val hwObj = runtime.optJSONObject("hardware") ?: JSONObject()
        val hwCurrent = hwObj.optString("current", "").ifBlank {
            hwObj.optString("requested", cardData.optString("suggested_hardware", "cpu-basic"))
        }
        val tagsArr = o.optJSONArray("tags") ?: JSONArray()
        val tags = (0 until tagsArr.length()).map { tagsArr.optString(it) }.filter { it.isNotBlank() }
        val isZero = hwCurrent.contains("zero", ignoreCase = true) ||
            tags.any { it.contains("zero", ignoreCase = true) }

        return SpaceSummary(
            spaceId = id,
            author = author,
            title = cardData.optString("title", repoName).ifBlank { repoName },
            emoji = cardData.optString("emoji", ""),
            shortDescription = cardData.optString("short_description", ""),
            likes = o.optInt("likes", 0),
            sdk = o.optString("sdk", "gradio"),
            hardware = hwCurrent.ifBlank { "cpu-basic" },
            isZeroGpu = isZero,
            runtimeStage = RuntimeStage.fromRaw(runtime.optString("stage")),
            tags = tags,
            subdomain = o.optString("subdomain", SpaceIdentifierParser.computeSubdomain(author, repoName)),
            lastModified = o.optString("lastModified", "")
        )
    }
}
