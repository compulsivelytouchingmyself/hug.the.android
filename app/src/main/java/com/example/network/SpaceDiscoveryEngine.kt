package com.example.network

import com.example.data.local.CachedManifestEntity
import com.example.data.local.ManifestDao
import com.example.model.ComponentDependency
import com.example.model.ComponentField
import com.example.model.ComponentKind
import com.example.model.DiscoveryProvenance
import com.example.model.EndpointManifest
import com.example.model.HardwareInfo
import com.example.model.ManifestJsonCodec
import com.example.model.RepoFileEntry
import com.example.model.RuntimeStage
import com.example.model.SpaceIdentity
import com.example.model.SpaceManifest
import com.example.model.ZeroGpuDecorator
import com.example.security.TokenRedactor
import org.json.JSONArray
import org.json.JSONObject

data class DiscoveryResult(
    val manifest: SpaceManifest,
    val repoFiles: List<RepoFileEntry>,
    val entryPointSource: String,
    val diagnostics: List<String>
)

/**
 * Three-Source Discovery Engine:
 *   1. Live API schema via bundled @gradio/client (Client.connect + view_api) with native /info fallback
 *   2. Runtime configuration via /config (/gradio_api/config)
 *   3. Repository source via @huggingface/hub (listFiles + downloadFile) with native Hub fallback
 *
 * Precedence: Live API > Runtime /config > Repository Source.
 * Supports provisional manifests while a sleeping Space wakes up.
 */
class SpaceDiscoveryEngine(
    private val hubClient: HfHubClient,
    private val gradioNativeClient: GradioNativeClient,
    private val hybridSdkBridge: HybridSdkBridge,
    private val manifestDao: ManifestDao
) {

    suspend fun discover(
        rawSpaceInput: String,
        token: String?,
        forceRefresh: Boolean = false
    ): Result<DiscoveryResult> = runCatching {
        val diagnostics = mutableListOf<String>()
        val parsedId = SpaceIdentifierParser.parse(rawSpaceInput)
            ?: throw IllegalArgumentException("Unrecognized Space identifier. Enter author/repo, huggingface.co/spaces/..., or *.hf.space URL.")

        // Step 0: Fetch HF Hub metadata to resolve canonical host, revision, hardware, and runtime stage
        val hubMeta = hubClient.getSpaceMetadata(parsedId.spaceId, token).getOrNull()
        val resolvedSpaceId = hubMeta?.spaceId ?: parsedId.spaceId
        val hostUrl = hubMeta?.hostUrl ?: parsedId.defaultHostUrl
        val revisionSha = hubMeta?.revisionSha.orEmpty()
        val runtimeStage = hubMeta?.runtimeStage ?: RuntimeStage.UNKNOWN

        diagnostics.add("Resolved $resolvedSpaceId -> $hostUrl (stage=${runtimeStage.raw}, rev=${revisionSha.take(8).ifBlank { "unknown" }})")

        // Check local Room manifest cache (unless forceRefresh or revision/stage changed)
        val cached = manifestDao.getManifest(resolvedSpaceId)
        if (!forceRefresh && cached != null) {
            val sameRev = revisionSha.isBlank() || cached.revisionSha == revisionSha
            val sameStage = cached.runtimeStage == runtimeStage.raw
            if (cached.isManualOverride || (sameRev && sameStage && !ManifestJsonCodec.fromJson(cached.manifestJson).provenance.isProvisional)) {
                val manifest = ManifestJsonCodec.fromJson(cached.manifestJson)
                diagnostics.add("Loaded normalized manifest from local Room cache (manualOverride=${cached.isManualOverride}).")
                val files = fetchRepoFiles(resolvedSpaceId, token)
                val entrySource = fetchFileText(resolvedSpaceId, manifest.provenance.entryPointFile, token).orEmpty()
                return@runCatching DiscoveryResult(manifest, files, entrySource, diagnostics)
            }
        }

        // Source 3 (run early & safely in parallel/sequence): Repository files + README metadata + Python static analysis
        val repoFiles = fetchRepoFiles(resolvedSpaceId, token)
        val readmeText = fetchFileText(resolvedSpaceId, "README.md", token).orEmpty()
        val readmeMeta = SpaceIdentifierParser.parseReadmeFrontmatter(readmeText)
        val entryPointFile = resolveEntryPointFile(readmeMeta.appFile, repoFiles)
        val entrySource = fetchFileText(resolvedSpaceId, entryPointFile, token).orEmpty()
        val staticAnalysis = if (entrySource.isNotBlank()) {
            PythonSourceAnalyzer.analyze(entrySource, entryPointFile)
        } else {
            StaticSourceAnalysis(emptyList(), emptyMap(), emptyList(), false)
        }
        val usedRepoSource = entrySource.isNotBlank()
        if (usedRepoSource) {
            diagnostics.add("Inspected $entryPointFile statically: ${staticAnalysis.decorators.size} @spaces.GPU decorators, ${staticAnalysis.declaredComponents.size} components.")
        } else {
            diagnostics.add("Repository source unavailable or empty for $entryPointFile.")
        }

        // Source 1 & 2: Live API schema via bundled @gradio/client + Runtime /config
        var apiInfoJson: JSONObject? = null
        var configJson: JSONObject? = null
        var apiPrefix = ""
        var gradioVersion = readmeMeta.sdkVersion ?: hubMeta?.sdkVersion.orEmpty()
        var sdkTransportUsed = "native_http"
        val warnings = mutableListOf<String>()

        if (runtimeStage != RuntimeStage.SLEEPING && runtimeStage != RuntimeStage.PAUSED && runtimeStage != RuntimeStage.STOPPED) {
            val spaceJwt = if (!token.isNullOrBlank()) {
                hubClient.fetchSpaceJwt(resolvedSpaceId, token).getOrNull()
            } else null

            val authLabel = when {
                !token.isNullOrBlank() && !spaceJwt.isNullOrBlank() -> "Bearer + Space JWT (__sign)"
                !token.isNullOrBlank() -> "Bearer only"
                else -> "Anonymous"
            }
            diagnostics.add("Discovery auth context: $authLabel")

            // Try bundled @gradio/client first using canonical spaceId so HF Space JWT/config binds properly
            val sdkResult = hybridSdkBridge.connectAndInspect(
                sessionKey = resolvedSpaceId,
                spaceRef = resolvedSpaceId,
                token = token,
                spaceJwt = spaceJwt
            )
            if (sdkResult.isSuccess) {
                val dataObj = sdkResult.getOrNull()?.optJSONObject("data")
                apiInfoJson = dataObj?.optJSONObject("apiInfo")
                configJson = dataObj?.optJSONObject("config")
                if (apiInfoJson != null || configJson != null) {
                    sdkTransportUsed = "gradio_js_client"
                    apiPrefix = configJson?.optString("api_prefix", "").orEmpty()
                    gradioVersion = configJson?.optString("version", gradioVersion).orEmpty()
                    val jwtFlag = dataObj?.optBoolean("jwtAttached", false) == true
                    diagnostics.add("Connected via bundled @gradio/client@2.8.0 (view_api + config, jwtAttached=$jwtFlag).")
                }
            } else {
                val errMsg = TokenRedactor.sanitize(sdkResult.exceptionOrNull()?.message.orEmpty())
                diagnostics.add("JS SDK connect fallback to native Gradio client: $errMsg")
            }

            // Fill missing Live API or Runtime /config via version-aware Native Gradio client
            if (apiInfoJson == null || configJson == null) {
                val nativeBundle = gradioNativeClient.fetchRuntimeBundle(hostUrl, token, spaceJwt)
                if (configJson == null) configJson = nativeBundle.configJson
                if (apiInfoJson == null) apiInfoJson = nativeBundle.apiInfoJson
                if (apiPrefix.isBlank()) apiPrefix = nativeBundle.apiPrefix
                if (gradioVersion.isBlank()) gradioVersion = nativeBundle.gradioVersion
                if (nativeBundle.configJson != null || nativeBundle.apiInfoJson != null) {
                    diagnostics.add("Fetched runtime metadata natively (gradio=$gradioVersion, prefix='${apiPrefix.ifBlank { "/" }}').")
                }
            }
        } else {
            warnings.add("Space is currently ${runtimeStage.label}. Showing provisional interface derived from $entryPointFile.")
            diagnostics.add("Space stage is ${runtimeStage.raw}; skipping live API probe and building provisional manifest.")
        }

        val usedLiveApi = apiInfoJson != null
        val usedRuntimeConfig = configJson != null

        // Consolidate all 3 sources into Normalized SpaceManifest
        val (endpoints, dependencies) = consolidateEndpoints(
            apiInfo = apiInfoJson,
            config = configJson,
            staticAnalysis = staticAnalysis
        )

        val isProvisional = !usedLiveApi && !usedRuntimeConfig && endpoints.isNotEmpty()
        if (endpoints.isEmpty()) {
            warnings.add("No callable Gradio endpoints were detected. Use Full Space View or Manual Manifest Override.")
        }

        val allDecorators = staticAnalysis.decorators
        val isZeroGpu = (hubMeta?.isZeroGpu == true) ||
            (readmeMeta.suggestedHardware?.contains("zero", ignoreCase = true) == true) ||
            staticAnalysis.detectedImportsZeroGpu

        val identity = SpaceIdentity(
            spaceId = resolvedSpaceId,
            author = hubMeta?.author ?: parsedId.author,
            repoName = hubMeta?.repoName ?: parsedId.repoName,
            title = readmeMeta.title ?: hubMeta?.title ?: parsedId.repoName,
            emoji = readmeMeta.emoji ?: hubMeta?.emoji.orEmpty(),
            shortDescription = readmeMeta.shortDescription ?: hubMeta?.shortDescription.orEmpty(),
            subdomain = hubMeta?.subdomain ?: parsedId.subdomain,
            hostUrl = hostUrl,
            revisionSha = revisionSha,
            sdk = hubMeta?.sdk ?: readmeMeta.sdk ?: "gradio",
            sdkVersion = gradioVersion,
            runtimeStage = if (usedLiveApi || usedRuntimeConfig) RuntimeStage.RUNNING else runtimeStage,
            privateSpace = hubMeta?.privateSpace ?: false
        )

        val hardware = HardwareInfo(
            current = hubMeta?.hardwareCurrent ?: readmeMeta.suggestedHardware ?: "unknown",
            requested = hubMeta?.hardwareRequested ?: readmeMeta.suggestedHardware ?: "unknown",
            isZeroGpu = isZeroGpu,
            sleepTimeSeconds = hubMeta?.sleepTimeSeconds,
            decorators = allDecorators
        )

        val manifest = SpaceManifest(
            provider = "huggingface_gradio",
            schemaVersion = 1,
            identity = identity,
            hardware = hardware,
            endpoints = endpoints,
            dependencies = dependencies,
            provenance = DiscoveryProvenance(
                provider = "huggingface_gradio",
                usedLiveApi = usedLiveApi,
                usedRuntimeConfig = usedRuntimeConfig,
                usedRepoSource = usedRepoSource,
                entryPointFile = entryPointFile,
                gradioVersion = gradioVersion,
                apiPrefix = apiPrefix,
                isProvisional = isProvisional,
                discoveredAtMs = System.currentTimeMillis(),
                warnings = warnings,
                sdkTransportUsed = sdkTransportUsed
            ),
            isManualOverride = false
        )

        // Persist normalized manifest in Room cache
        manifestDao.upsertManifest(
            CachedManifestEntity(
                spaceId = resolvedSpaceId,
                revisionSha = revisionSha,
                runtimeStage = identity.runtimeStage.raw,
                manifestJson = ManifestJsonCodec.toJson(manifest),
                isManualOverride = false,
                updatedAtMs = System.currentTimeMillis()
            )
        )

        DiscoveryResult(manifest, repoFiles, entrySource, diagnostics)
    }

    suspend fun saveManualOverride(manifest: SpaceManifest) {
        val overridden = manifest.copy(isManualOverride = true)
        manifestDao.upsertManifest(
            CachedManifestEntity(
                spaceId = overridden.identity.spaceId,
                revisionSha = overridden.identity.revisionSha,
                runtimeStage = overridden.identity.runtimeStage.raw,
                manifestJson = ManifestJsonCodec.toJson(overridden),
                isManualOverride = true,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearCachedManifest(spaceId: String) {
        manifestDao.deleteManifest(spaceId)
    }

    private suspend fun fetchRepoFiles(spaceId: String, token: String?): List<RepoFileEntry> {
        val sdkRes = hybridSdkBridge.listFiles(spaceId, token).getOrNull()
        val sdkData = sdkRes?.optJSONArray("data")
        if (sdkData != null && sdkData.length() > 0) {
            return (0 until sdkData.length()).mapNotNull { i ->
                val o = sdkData.optJSONObject(i) ?: return@mapNotNull null
                RepoFileEntry(
                    path = o.optString("path"),
                    type = o.optString("type", "file"),
                    size = o.optLong("size", 0L)
                )
            }.filter { it.path.isNotBlank() }
        }
        return hubClient.listFiles(spaceId, token).getOrDefault(emptyList())
    }

    suspend fun fetchFileText(spaceId: String, filePath: String, token: String?): String? {
        val sdkRes = hybridSdkBridge.downloadFile(spaceId, filePath, token).getOrNull()
        val sdkText = sdkRes?.optString("data")?.takeIf { it.isNotBlank() && it != "null" }
        if (sdkText != null) return sdkText
        return hubClient.downloadFileText(spaceId, filePath, token).getOrNull()
    }

    private fun resolveEntryPointFile(readmeAppFile: String?, repoFiles: List<RepoFileEntry>): String {
        if (!readmeAppFile.isNullOrBlank()) return readmeAppFile
        val paths = repoFiles.map { it.path }
        return listOf("app.py", "run.py", "main.py", "demo.py", "webui.py", "src/app.py")
            .firstOrNull { it in paths }
            ?: paths.firstOrNull { it.endsWith(".py") && !it.contains("/") }
            ?: "app.py"
    }

    private fun consolidateEndpoints(
        apiInfo: JSONObject?,
        config: JSONObject?,
        staticAnalysis: StaticSourceAnalysis
    ): Pair<List<EndpointManifest>, List<ComponentDependency>> {
        val configComponentsById = mutableMapOf<Int, JSONObject>()
        val dependencies = mutableListOf<ComponentDependency>()

        if (config != null) {
            val comps = config.optJSONArray("components") ?: JSONArray()
            for (i in 0 until comps.length()) {
                val c = comps.optJSONObject(i) ?: continue
                configComponentsById[c.optInt("id", i)] = c
            }
            val deps = config.optJSONArray("dependencies") ?: JSONArray()
            for (i in 0 until deps.length()) {
                val d = deps.optJSONObject(i) ?: continue
                val apiName = d.optString("api_name", "").takeIf { it.isNotBlank() && it != "false" && it != "null" }
                val inputs = jsonIntArray(d.optJSONArray("inputs"))
                val outputs = jsonIntArray(d.optJSONArray("outputs"))
                val cancels = jsonIntArray(d.optJSONArray("cancels"))
                dependencies.add(
                    ComponentDependency(
                        id = d.optInt("id", i),
                        apiName = apiName,
                        trigger = d.optString("trigger", "click"),
                        inputIds = inputs,
                        outputIds = outputs,
                        cancels = cancels
                    )
                )
            }
        }

        val endpoints = mutableListOf<EndpointManifest>()
        val decByFn = staticAnalysis.decorators.associateBy { it.functionName.lowercase() }

        // 1. Primary: Live API schema enriched with /config component metadata
        if (apiInfo != null) {
            val named = apiInfo.optJSONObject("named_endpoints")
            if (named != null) {
                val keys = named.keys()
                var idx = 0
                while (keys.hasNext()) {
                    val apiKey = keys.next()
                    val epObj = named.optJSONObject(apiKey) ?: continue
                    val cleanKey = apiKey.trimStart('/')
                    val matchingDep = dependencies.firstOrNull {
                        it.apiName?.trimStart('/')?.equals(cleanKey, ignoreCase = true) == true
                    }
                    val inputFields = parseApiParameters(
                        params = epObj.optJSONArray("parameters") ?: JSONArray(),
                        depComponentIds = matchingDep?.inputIds.orEmpty(),
                        configComponentsById = configComponentsById,
                        idPrefix = "in_${idx}"
                    )
                    val outputFields = parseApiParameters(
                        params = epObj.optJSONArray("returns") ?: JSONArray(),
                        depComponentIds = matchingDep?.outputIds.orEmpty(),
                        configComponentsById = configComponentsById,
                        idPrefix = "out_${idx}"
                    )
                    val matchedDec = matchZeroGpuDecorator(cleanKey, decByFn, staticAnalysis.decorators)
                    endpoints.add(
                        EndpointManifest(
                            id = "named_${cleanKey}_$idx",
                            apiName = if (apiKey.startsWith("/")) apiKey else "/$apiKey",
                            fnIndex = matchingDep?.id,
                            label = cleanKey.ifBlank { "predict" },
                            description = epObj.optString("description", ""),
                            inputs = inputFields,
                            outputs = outputFields,
                            requiresSession = true,
                            supportsCancel = true,
                            zeroGpuDecorator = matchedDec,
                            provenanceSource = if (matchingDep != null) "live_api+config" else "live_api"
                        )
                    )
                    idx++
                }
            }

            // Include unnamed endpoints if no named endpoints exist (or as secondary endpoints)
            val unnamed = apiInfo.optJSONObject("unnamed_endpoints")
            if (unnamed != null && endpoints.isEmpty()) {
                val keys = unnamed.keys()
                while (keys.hasNext()) {
                    val fnKey = keys.next()
                    val fnIndex = fnKey.toIntOrNull()
                    val epObj = unnamed.optJSONObject(fnKey) ?: continue
                    val matchingDep = dependencies.firstOrNull { it.id == fnIndex }
                    val inputFields = parseApiParameters(
                        params = epObj.optJSONArray("parameters") ?: JSONArray(),
                        depComponentIds = matchingDep?.inputIds.orEmpty(),
                        configComponentsById = configComponentsById,
                        idPrefix = "un_in_$fnKey"
                    )
                    val outputFields = parseApiParameters(
                        params = epObj.optJSONArray("returns") ?: JSONArray(),
                        depComponentIds = matchingDep?.outputIds.orEmpty(),
                        configComponentsById = configComponentsById,
                        idPrefix = "un_out_$fnKey"
                    )
                    if (inputFields.isEmpty() && outputFields.isEmpty()) continue
                    endpoints.add(
                        EndpointManifest(
                            id = "unnamed_$fnKey",
                            apiName = null,
                            fnIndex = fnIndex,
                            label = "Endpoint #$fnKey",
                            inputs = inputFields,
                            outputs = outputFields,
                            requiresSession = true,
                            supportsCancel = true,
                            zeroGpuDecorator = staticAnalysis.decorators.firstOrNull(),
                            provenanceSource = "live_api"
                        )
                    )
                }
            }
        }

        // 2. Secondary: Derive endpoints directly from /config dependencies if /info was unavailable
        if (endpoints.isEmpty() && dependencies.isNotEmpty()) {
            dependencies.filter { it.inputIds.isNotEmpty() || it.outputIds.isNotEmpty() }
                .take(12)
                .forEachIndexed { idx, dep ->
                    val inFields = dep.inputIds.mapIndexed { pos, cid ->
                        fieldFromConfigComponent(configComponentsById[cid], "cfg_in_${idx}_$pos", pos)
                    }
                    val outFields = dep.outputIds.mapIndexed { pos, cid ->
                        fieldFromConfigComponent(configComponentsById[cid], "cfg_out_${idx}_$pos", pos)
                    }
                    val label = dep.apiName?.trimStart('/') ?: "fn_${dep.id}"
                    endpoints.add(
                        EndpointManifest(
                            id = "config_${dep.id}_$idx",
                            apiName = dep.apiName?.let { if (it.startsWith("/")) it else "/$it" },
                            fnIndex = dep.id,
                            label = label,
                            inputs = inFields,
                            outputs = outFields,
                            requiresSession = true,
                            supportsCancel = true,
                            zeroGpuDecorator = matchZeroGpuDecorator(label, decByFn, staticAnalysis.decorators),
                            provenanceSource = "runtime_config"
                        )
                    )
                }
        }

        // 3. Tertiary: Provisional endpoints from static Python repo analysis
        if (endpoints.isEmpty() && staticAnalysis.provisionalEndpoints.isNotEmpty()) {
            endpoints.addAll(staticAnalysis.provisionalEndpoints)
        }

        return endpoints to dependencies
    }

    private fun parseApiParameters(
        params: JSONArray,
        depComponentIds: List<Int>,
        configComponentsById: Map<Int, JSONObject>,
        idPrefix: String
    ): List<ComponentField> {
        val result = mutableListOf<ComponentField>()
        for (i in 0 until params.length()) {
            val p = params.optJSONObject(i) ?: continue
            val cid = depComponentIds.getOrNull(i)
            val compObj = cid?.let { configComponentsById[it] }
            val props = compObj?.optJSONObject("props") ?: JSONObject()

            val paramName = p.optString("parameter_name", "").ifBlank {
                p.optString("label", "param_$i").lowercase().replace(Regex("[^a-z0-9]+"), "_")
            }
            val label = p.optString("label", "").ifBlank {
                props.optString("label", paramName)
            }.ifBlank { "Parameter ${i + 1}" }

            val compGradioType = compObj?.optString("type", "").orEmpty()
            val apiCompName = p.optString("component", "")
            val gradioType = compGradioType.ifBlank { apiCompName }.ifBlank { "textbox" }

            val typeObj = p.optJSONObject("type")
            val pyTypeObj = p.optJSONObject("python_type")
            val apiDataType = typeObj?.optString("type", "").orEmpty()
                .ifBlank { pyTypeObj?.optString("type", "").orEmpty() }
                .ifBlank { "string" }

            val kind = PythonSourceAnalyzer.mapGradioTypeToKind(gradioType, apiDataType)
            val description = p.optString("description", "")
                .ifBlank { typeObj?.optString("description", "").orEmpty() }
                .ifBlank { props.optString("info", "") }

            val hasDefault = p.optBoolean("parameter_has_default", false)
            val defaultVal: Any? = when {
                hasDefault && !p.isNull("parameter_default") -> p.opt("parameter_default")
                props.has("value") && !props.isNull("value") -> props.opt("value")
                else -> null
            }

            val choices = extractChoices(props.optJSONArray("choices"))
            val min = props.optDouble("minimum").takeIf { !it.isNaN() }
            val max = props.optDouble("maximum").takeIf { !it.isNaN() }
            val step = props.optDouble("step").takeIf { !it.isNaN() }
            val lines = props.optInt("lines", if (kind == ComponentKind.TEXTBOX && description.length > 40) 3 else 1)
            val maxLines = props.optInt("max_lines", 8).coerceAtLeast(lines)
            val placeholder = props.optString("placeholder", "")
            val multiSelect = props.optBoolean("multiselect", kind == ComponentKind.CHECKBOX_GROUP)
            val fileTypes = jsonStringArray(props.optJSONArray("file_types"))

            result.add(
                ComponentField(
                    id = "${idPrefix}_$i",
                    componentId = cid,
                    parameterName = paramName,
                    label = label,
                    description = description,
                    gradioType = gradioType,
                    apiDataType = apiDataType,
                    kind = kind,
                    defaultValueJson = encodeAnyToJson(defaultVal),
                    required = !hasDefault && kind != ComponentKind.STATE,
                    min = min,
                    max = max,
                    step = step,
                    choices = choices,
                    multiSelect = multiSelect,
                    lines = lines,
                    maxLines = maxLines,
                    placeholder = placeholder,
                    fileTypes = fileTypes,
                    isHiddenState = kind == ComponentKind.STATE,
                    rawSchemaJson = p.toString()
                )
            )
        }
        return result
    }

    private fun fieldFromConfigComponent(compObj: JSONObject?, id: String, index: Int): ComponentField {
        val props = compObj?.optJSONObject("props") ?: JSONObject()
        val gradioType = compObj?.optString("type", "textbox") ?: "textbox"
        val kind = PythonSourceAnalyzer.mapGradioTypeToKind(gradioType, "")
        val label = props.optString("label", gradioType.replaceFirstChar { it.uppercase() }).ifBlank { "Input ${index + 1}" }
        val defaultVal = if (props.has("value") && !props.isNull("value")) props.opt("value") else null
        return ComponentField(
            id = id,
            componentId = compObj?.optInt("id"),
            parameterName = label.lowercase().replace(Regex("[^a-z0-9]+"), "_"),
            label = label,
            description = props.optString("info", ""),
            gradioType = gradioType,
            apiDataType = "any",
            kind = kind,
            defaultValueJson = encodeAnyToJson(defaultVal),
            min = props.optDouble("minimum").takeIf { !it.isNaN() },
            max = props.optDouble("maximum").takeIf { !it.isNaN() },
            step = props.optDouble("step").takeIf { !it.isNaN() },
            choices = extractChoices(props.optJSONArray("choices")),
            multiSelect = props.optBoolean("multiselect", kind == ComponentKind.CHECKBOX_GROUP),
            lines = props.optInt("lines", 1),
            maxLines = props.optInt("max_lines", 6),
            placeholder = props.optString("placeholder", ""),
            fileTypes = jsonStringArray(props.optJSONArray("file_types")),
            isHiddenState = kind == ComponentKind.STATE,
            rawSchemaJson = compObj?.toString().orEmpty()
        )
    }

    private fun extractChoices(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            when (item) {
                is JSONArray -> {
                    // Gradio represents choices as [displayLabel, internalValue]
                    val v = item.optString(1, item.optString(0, ""))
                    if (v.isNotBlank()) out.add(v)
                }
                else -> {
                    val s = item?.toString().orEmpty()
                    if (s.isNotBlank()) out.add(s)
                }
            }
        }
        return out
    }

    private fun matchZeroGpuDecorator(
        endpointName: String,
        decByFn: Map<String, ZeroGpuDecorator>,
        all: List<ZeroGpuDecorator>
    ): ZeroGpuDecorator? {
        val key = endpointName.lowercase()
        decByFn[key]?.let { return it }
        all.firstOrNull { key.contains(it.functionName.lowercase()) || it.functionName.lowercase().contains(key) }?.let { return it }
        return if (all.size == 1) all.first() else null
    }

    private fun encodeAnyToJson(value: Any?): String? {
        if (value == null || value === JSONObject.NULL) return null
        return when (value) {
            is String -> JSONObject.quote(value)
            is Number, is Boolean, is JSONArray, is JSONObject -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
    }

    private fun jsonIntArray(arr: JSONArray?): List<Int> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optInt(it) }
    }

    private fun jsonStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }
}
