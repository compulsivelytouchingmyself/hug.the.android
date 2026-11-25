package com.example.model

import org.json.JSONArray
import org.json.JSONObject

data class SpaceIdentifier(
    val spaceId: String,
    val author: String,
    val repoName: String,
    val subdomain: String,
    val directHostUrl: String? = null
) {
    val defaultHostUrl: String
        get() = directHostUrl ?: "https://$subdomain.hf.space"

    val hubUrl: String
        get() = "https://huggingface.co/spaces/$spaceId"
}

enum class RuntimeStage(val raw: String, val label: String) {
    RUNNING("RUNNING", "Running"),
    RUNNING_BUILDING("RUNNING_BUILDING", "Running (Updating)"),
    BUILDING("BUILDING", "Building"),
    APP_STARTING("APP_STARTING", "Starting"),
    SLEEPING("SLEEPING", "Sleeping"),
    PAUSED("PAUSED", "Paused"),
    STOPPED("STOPPED", "Stopped"),
    RUNTIME_ERROR("RUNTIME_ERROR", "Runtime Error"),
    BUILD_ERROR("BUILD_ERROR", "Build Error"),
    CONFIG_ERROR("CONFIG_ERROR", "Config Error"),
    NO_APP_FILE("NO_APP_FILE", "No App File"),
    UNKNOWN("UNKNOWN", "Unknown");

    val isLive: Boolean get() = this == RUNNING || this == RUNNING_BUILDING
    val isWaking: Boolean get() = this == BUILDING || this == APP_STARTING || this == SLEEPING

    companion object {
        fun fromRaw(value: String?): RuntimeStage {
            val upper = value?.trim()?.uppercase() ?: return UNKNOWN
            return entries.firstOrNull { it.raw == upper } ?: UNKNOWN
        }
    }
}

data class SpaceSummary(
    val spaceId: String,
    val author: String,
    val title: String,
    val emoji: String = "",
    val shortDescription: String = "",
    val likes: Int = 0,
    val sdk: String = "gradio",
    val hardware: String = "cpu-basic",
    val isZeroGpu: Boolean = false,
    val runtimeStage: RuntimeStage = RuntimeStage.UNKNOWN,
    val tags: List<String> = emptyList(),
    val subdomain: String = "",
    val lastModified: String = "",
    val isFavorite: Boolean = false
)

data class ZeroGpuDecorator(
    val functionName: String,
    val lineNumber: Int,
    val declaredDurationSeconds: Int? = null,
    val isDynamicDuration: Boolean = false,
    val dynamicDurationExpression: String? = null,
    val allocationSize: String? = null,
    val rawDecorator: String = "@spaces.GPU"
)

data class HardwareInfo(
    val current: String = "unknown",
    val requested: String = "unknown",
    val isZeroGpu: Boolean = false,
    val sleepTimeSeconds: Int? = null,
    val decorators: List<ZeroGpuDecorator> = emptyList()
)

enum class ComponentKind {
    TEXTBOX,
    SLIDER,
    NUMBER,
    CHECKBOX,
    CHECKBOX_GROUP,
    RADIO,
    DROPDOWN,
    IMAGE,
    AUDIO,
    VIDEO,
    FILE,
    GALLERY,
    CHATBOT,
    DATAFRAME,
    JSON,
    CODE,
    MARKDOWN,
    HTML,
    COLOR_PICKER,
    MULTIMODAL,
    STATE,
    UNKNOWN
}

data class ComponentField(
    val id: String,
    val componentId: Int? = null,
    val parameterName: String,
    val label: String,
    val description: String = "",
    val gradioType: String = "textbox",
    val apiDataType: String = "string",
    val kind: ComponentKind = ComponentKind.TEXTBOX,
    val defaultValueJson: String? = null,
    val required: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
    val lines: Int = 1,
    val maxLines: Int = 6,
    val placeholder: String = "",
    val fileTypes: List<String> = emptyList(),
    val isHiddenState: Boolean = false,
    val rawSchemaJson: String = ""
)

data class EndpointManifest(
    val id: String,
    val apiName: String?,
    val fnIndex: Int?,
    val label: String,
    val description: String = "",
    val inputs: List<ComponentField> = emptyList(),
    val outputs: List<ComponentField> = emptyList(),
    val requiresSession: Boolean = true,
    val supportsCancel: Boolean = true,
    val zeroGpuDecorator: ZeroGpuDecorator? = null,
    val provenanceSource: String = "live_api"
) {
    val callableTarget: String
        get() = when {
            !apiName.isNullOrBlank() -> if (apiName.startsWith("/")) apiName else "/$apiName"
            fnIndex != null -> fnIndex.toString()
            else -> "/predict"
        }
}

data class ComponentDependency(
    val id: Int,
    val apiName: String?,
    val trigger: String,
    val inputIds: List<Int>,
    val outputIds: List<Int>,
    val cancels: List<Int> = emptyList()
)

data class DiscoveryProvenance(
    val provider: String = "huggingface_gradio",
    val usedLiveApi: Boolean = false,
    val usedRuntimeConfig: Boolean = false,
    val usedRepoSource: Boolean = false,
    val entryPointFile: String = "app.py",
    val gradioVersion: String = "",
    val apiPrefix: String = "",
    val isProvisional: Boolean = false,
    val discoveredAtMs: Long = System.currentTimeMillis(),
    val warnings: List<String> = emptyList(),
    val sdkTransportUsed: String = "native_http"
)

data class SpaceIdentity(
    val spaceId: String,
    val author: String,
    val repoName: String,
    val title: String,
    val emoji: String = "",
    val shortDescription: String = "",
    val subdomain: String,
    val hostUrl: String,
    val revisionSha: String = "",
    val sdk: String = "gradio",
    val sdkVersion: String = "",
    val runtimeStage: RuntimeStage = RuntimeStage.UNKNOWN,
    val privateSpace: Boolean = false
)

data class SpaceManifest(
    val provider: String = "huggingface_gradio",
    val schemaVersion: Int = 1,
    val identity: SpaceIdentity,
    val hardware: HardwareInfo,
    val endpoints: List<EndpointManifest>,
    val dependencies: List<ComponentDependency> = emptyList(),
    val provenance: DiscoveryProvenance,
    val isManualOverride: Boolean = false
)

sealed class FieldValue {
    data class Text(val value: String) : FieldValue()
    data class NumberVal(val value: Double) : FieldValue()
    data class BoolVal(val value: Boolean) : FieldValue()
    data class MultiSelect(val values: List<String>) : FieldValue()
    data class MediaRef(
        val localPath: String? = null,
        val uriString: String? = null,
        val remoteUrl: String? = null,
        val fileName: String = "input",
        val mimeType: String = "application/octet-stream",
        val mediaAssetId: String? = null
    ) : FieldValue()
    data class Multimodal(
        val text: String = "",
        val files: List<MediaRef> = emptyList()
    ) : FieldValue()
    data class StructuredJson(val rawJson: String) : FieldValue()
    data object Empty : FieldValue()
}

data class OutputItem(
    val index: Int,
    val label: String,
    val kind: ComponentKind,
    val textValue: String? = null,
    val mediaUrl: String? = null,
    val localCachedPath: String? = null,
    val mimeType: String? = null,
    val fileName: String? = null,
    val galleryItems: List<OutputItem> = emptyList(),
    val tableHeaders: List<String> = emptyList(),
    val tableRows: List<List<String>> = emptyList(),
    val chatMessages: List<Pair<String, String>> = emptyList(),
    val rawJson: String = "",
    val mediaAssetId: String? = null
)

enum class JobStatus {
    IDLE,
    UPLOADING,
    QUEUED,
    RUNNING,
    STREAMING,
    COMPLETED,
    CANCELLED,
    FAILED,
    DISCONNECTED
}

data class ActiveJobState(
    val jobId: String = "",
    val spaceId: String = "",
    val endpointLabel: String = "",
    val status: JobStatus = JobStatus.IDLE,
    val transport: String = "",
    val queuePosition: Int? = null,
    val queueSize: Int? = null,
    val etaSeconds: Double? = null,
    val progressFraction: Float? = null,
    val progressMessage: String = "",
    val startedAtMs: Long = 0L,
    val elapsedMs: Long = 0L,
    val declaredZeroGpuSeconds: Int? = null,
    val quotaMessage: String? = null,
    val errorMessage: String? = null,
    val isIntermediate: Boolean = false
)

enum class SpaceAuthMode {
    DEFAULT_ACCOUNT,
    ANONYMOUS,
    CUSTOM_TOKEN
}

data class AccountInfo(
    val username: String,
    val fullName: String = "",
    val avatarUrl: String = "",
    val isPro: Boolean = false,
    val accountType: String = "user",
    val orgs: List<String> = emptyList(),
    val verifiedAtMs: Long = System.currentTimeMillis()
)

data class RepoFileEntry(
    val path: String,
    val type: String,
    val size: Long = 0L
)

data class FieldCustomization(
    val fieldId: String,
    val orderIndex: Int = 0,
    val hidden: Boolean = false,
    val locked: Boolean = false,
    val advanced: Boolean = false,
    val customDefaultJson: String? = null
)

data class SpaceProfile(
    val spaceId: String,
    val favoriteEndpoints: List<String> = emptyList(),
    val customizations: Map<String, FieldCustomization> = emptyMap(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class SpacePreset(
    val id: String,
    val spaceId: String,
    val endpointId: String,
    val name: String,
    val valuesJson: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class DiagnosticEvent(
    val timestampMs: Long = System.currentTimeMillis(),
    val level: String = "INFO",
    val source: String = "SpaceDeck",
    val message: String
)

object ManifestJsonCodec {
    fun toJson(manifest: SpaceManifest): String {
        val root = JSONObject()
        root.put("provider", manifest.provider)
        root.put("schemaVersion", manifest.schemaVersion)
        root.put("isManualOverride", manifest.isManualOverride)

        val idObj = JSONObject().apply {
            put("spaceId", manifest.identity.spaceId)
            put("author", manifest.identity.author)
            put("repoName", manifest.identity.repoName)
            put("title", manifest.identity.title)
            put("emoji", manifest.identity.emoji)
            put("shortDescription", manifest.identity.shortDescription)
            put("subdomain", manifest.identity.subdomain)
            put("hostUrl", manifest.identity.hostUrl)
            put("revisionSha", manifest.identity.revisionSha)
            put("sdk", manifest.identity.sdk)
            put("sdkVersion", manifest.identity.sdkVersion)
            put("runtimeStage", manifest.identity.runtimeStage.raw)
            put("privateSpace", manifest.identity.privateSpace)
        }
        root.put("identity", idObj)

        val hwObj = JSONObject().apply {
            put("current", manifest.hardware.current)
            put("requested", manifest.hardware.requested)
            put("isZeroGpu", manifest.hardware.isZeroGpu)
            manifest.hardware.sleepTimeSeconds?.let { put("sleepTimeSeconds", it) }
            val decs = JSONArray()
            manifest.hardware.decorators.forEach { d ->
                decs.put(JSONObject().apply {
                    put("functionName", d.functionName)
                    put("lineNumber", d.lineNumber)
                    d.declaredDurationSeconds?.let { put("declaredDurationSeconds", it) }
                    put("isDynamicDuration", d.isDynamicDuration)
                    d.dynamicDurationExpression?.let { put("dynamicDurationExpression", it) }
                    d.allocationSize?.let { put("allocationSize", it) }
                    put("rawDecorator", d.rawDecorator)
                })
            }
            put("decorators", decs)
        }
        root.put("hardware", hwObj)

        val eps = JSONArray()
        manifest.endpoints.forEach { ep ->
            eps.put(JSONObject().apply {
                put("id", ep.id)
                ep.apiName?.let { put("apiName", it) }
                ep.fnIndex?.let { put("fnIndex", it) }
                put("label", ep.label)
                put("description", ep.description)
                put("requiresSession", ep.requiresSession)
                put("supportsCancel", ep.supportsCancel)
                put("provenanceSource", ep.provenanceSource)
                put("inputs", encodeFields(ep.inputs))
                put("outputs", encodeFields(ep.outputs))
                ep.zeroGpuDecorator?.let { d ->
                    put("zeroGpuDecorator", JSONObject().apply {
                        put("functionName", d.functionName)
                        put("lineNumber", d.lineNumber)
                        d.declaredDurationSeconds?.let { put("declaredDurationSeconds", it) }
                        put("isDynamicDuration", d.isDynamicDuration)
                        d.dynamicDurationExpression?.let { put("dynamicDurationExpression", it) }
                        d.allocationSize?.let { put("allocationSize", it) }
                        put("rawDecorator", d.rawDecorator)
                    })
                }
            })
        }
        root.put("endpoints", eps)

        val deps = JSONArray()
        manifest.dependencies.forEach { dep ->
            deps.put(JSONObject().apply {
                put("id", dep.id)
                dep.apiName?.let { put("apiName", it) }
                put("trigger", dep.trigger)
                put("inputIds", JSONArray(dep.inputIds))
                put("outputIds", JSONArray(dep.outputIds))
                put("cancels", JSONArray(dep.cancels))
            })
        }
        root.put("dependencies", deps)

        val provObj = JSONObject().apply {
            put("provider", manifest.provenance.provider)
            put("usedLiveApi", manifest.provenance.usedLiveApi)
            put("usedRuntimeConfig", manifest.provenance.usedRuntimeConfig)
            put("usedRepoSource", manifest.provenance.usedRepoSource)
            put("entryPointFile", manifest.provenance.entryPointFile)
            put("gradioVersion", manifest.provenance.gradioVersion)
            put("apiPrefix", manifest.provenance.apiPrefix)
            put("isProvisional", manifest.provenance.isProvisional)
            put("discoveredAtMs", manifest.provenance.discoveredAtMs)
            put("warnings", JSONArray(manifest.provenance.warnings))
            put("sdkTransportUsed", manifest.provenance.sdkTransportUsed)
        }
        root.put("provenance", provObj)

        return root.toString(2)
    }

    private fun encodeFields(fields: List<ComponentField>): JSONArray {
        val arr = JSONArray()
        fields.forEach { f ->
            arr.put(JSONObject().apply {
                put("id", f.id)
                f.componentId?.let { put("componentId", it) }
                put("parameterName", f.parameterName)
                put("label", f.label)
                put("description", f.description)
                put("gradioType", f.gradioType)
                put("apiDataType", f.apiDataType)
                put("kind", f.kind.name)
                f.defaultValueJson?.let { put("defaultValueJson", it) }
                put("required", f.required)
                f.min?.let { put("min", it) }
                f.max?.let { put("max", it) }
                f.step?.let { put("step", it) }
                put("choices", JSONArray(f.choices))
                put("multiSelect", f.multiSelect)
                put("lines", f.lines)
                put("maxLines", f.maxLines)
                put("placeholder", f.placeholder)
                put("fileTypes", JSONArray(f.fileTypes))
                put("isHiddenState", f.isHiddenState)
                put("rawSchemaJson", f.rawSchemaJson)
            })
        }
        return arr
    }

    fun fromJson(raw: String): SpaceManifest {
        val root = JSONObject(raw)
        val idObj = root.getJSONObject("identity")
        val identity = SpaceIdentity(
            spaceId = idObj.getString("spaceId"),
            author = idObj.optString("author", ""),
            repoName = idObj.optString("repoName", ""),
            title = idObj.optString("title", idObj.getString("spaceId")),
            emoji = idObj.optString("emoji", ""),
            shortDescription = idObj.optString("shortDescription", ""),
            subdomain = idObj.optString("subdomain", ""),
            hostUrl = idObj.optString("hostUrl", ""),
            revisionSha = idObj.optString("revisionSha", ""),
            sdk = idObj.optString("sdk", "gradio"),
            sdkVersion = idObj.optString("sdkVersion", ""),
            runtimeStage = RuntimeStage.fromRaw(idObj.optString("runtimeStage")),
            privateSpace = idObj.optBoolean("privateSpace", false)
        )

        val hwObj = root.optJSONObject("hardware") ?: JSONObject()
        val decsArr = hwObj.optJSONArray("decorators") ?: JSONArray()
        val decorators = (0 until decsArr.length()).map { i ->
            decodeDecorator(decsArr.getJSONObject(i))
        }
        val hardware = HardwareInfo(
            current = hwObj.optString("current", "unknown"),
            requested = hwObj.optString("requested", "unknown"),
            isZeroGpu = hwObj.optBoolean("isZeroGpu", false),
            sleepTimeSeconds = if (hwObj.has("sleepTimeSeconds") && !hwObj.isNull("sleepTimeSeconds")) hwObj.getInt("sleepTimeSeconds") else null,
            decorators = decorators
        )

        val epsArr = root.optJSONArray("endpoints") ?: JSONArray()
        val endpoints = (0 until epsArr.length()).map { i ->
            val o = epsArr.getJSONObject(i)
            EndpointManifest(
                id = o.getString("id"),
                apiName = if (o.has("apiName") && !o.isNull("apiName")) o.getString("apiName") else null,
                fnIndex = if (o.has("fnIndex") && !o.isNull("fnIndex")) o.getInt("fnIndex") else null,
                label = o.optString("label", o.getString("id")),
                description = o.optString("description", ""),
                inputs = decodeFields(o.optJSONArray("inputs") ?: JSONArray()),
                outputs = decodeFields(o.optJSONArray("outputs") ?: JSONArray()),
                requiresSession = o.optBoolean("requiresSession", true),
                supportsCancel = o.optBoolean("supportsCancel", true),
                zeroGpuDecorator = o.optJSONObject("zeroGpuDecorator")?.let { decodeDecorator(it) },
                provenanceSource = o.optString("provenanceSource", "live_api")
            )
        }

        val depsArr = root.optJSONArray("dependencies") ?: JSONArray()
        val dependencies = (0 until depsArr.length()).map { i ->
            val o = depsArr.getJSONObject(i)
            ComponentDependency(
                id = o.getInt("id"),
                apiName = if (o.has("apiName") && !o.isNull("apiName")) o.getString("apiName") else null,
                trigger = o.optString("trigger", "click"),
                inputIds = decodeIntList(o.optJSONArray("inputIds")),
                outputIds = decodeIntList(o.optJSONArray("outputIds")),
                cancels = decodeIntList(o.optJSONArray("cancels"))
            )
        }

        val provObj = root.optJSONObject("provenance") ?: JSONObject()
        val provenance = DiscoveryProvenance(
            provider = provObj.optString("provider", "huggingface_gradio"),
            usedLiveApi = provObj.optBoolean("usedLiveApi", false),
            usedRuntimeConfig = provObj.optBoolean("usedRuntimeConfig", false),
            usedRepoSource = provObj.optBoolean("usedRepoSource", false),
            entryPointFile = provObj.optString("entryPointFile", "app.py"),
            gradioVersion = provObj.optString("gradioVersion", ""),
            apiPrefix = provObj.optString("apiPrefix", ""),
            isProvisional = provObj.optBoolean("isProvisional", false),
            discoveredAtMs = provObj.optLong("discoveredAtMs", System.currentTimeMillis()),
            warnings = decodeStringList(provObj.optJSONArray("warnings")),
            sdkTransportUsed = provObj.optString("sdkTransportUsed", "native_http")
        )

        return SpaceManifest(
            provider = root.optString("provider", "huggingface_gradio"),
            schemaVersion = root.optInt("schemaVersion", 1),
            identity = identity,
            hardware = hardware,
            endpoints = endpoints,
            dependencies = dependencies,
            provenance = provenance,
            isManualOverride = root.optBoolean("isManualOverride", false)
        )
    }

    private fun decodeDecorator(o: JSONObject): ZeroGpuDecorator = ZeroGpuDecorator(
        functionName = o.optString("functionName", "predict"),
        lineNumber = o.optInt("lineNumber", 0),
        declaredDurationSeconds = if (o.has("declaredDurationSeconds") && !o.isNull("declaredDurationSeconds")) o.getInt("declaredDurationSeconds") else null,
        isDynamicDuration = o.optBoolean("isDynamicDuration", false),
        dynamicDurationExpression = if (o.has("dynamicDurationExpression") && !o.isNull("dynamicDurationExpression")) o.getString("dynamicDurationExpression") else null,
        allocationSize = if (o.has("allocationSize") && !o.isNull("allocationSize")) o.getString("allocationSize") else null,
        rawDecorator = o.optString("rawDecorator", "@spaces.GPU")
    )

    private fun decodeFields(arr: JSONArray): List<ComponentField> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        ComponentField(
            id = o.getString("id"),
            componentId = if (o.has("componentId") && !o.isNull("componentId")) o.getInt("componentId") else null,
            parameterName = o.optString("parameterName", "param_$i"),
            label = o.optString("label", "Input $i"),
            description = o.optString("description", ""),
            gradioType = o.optString("gradioType", "textbox"),
            apiDataType = o.optString("apiDataType", "string"),
            kind = runCatching { ComponentKind.valueOf(o.optString("kind", "UNKNOWN")) }.getOrDefault(ComponentKind.UNKNOWN),
            defaultValueJson = if (o.has("defaultValueJson") && !o.isNull("defaultValueJson")) o.getString("defaultValueJson") else null,
            required = o.optBoolean("required", false),
            min = if (o.has("min") && !o.isNull("min")) o.getDouble("min") else null,
            max = if (o.has("max") && !o.isNull("max")) o.getDouble("max") else null,
            step = if (o.has("step") && !o.isNull("step")) o.getDouble("step") else null,
            choices = decodeStringList(o.optJSONArray("choices")),
            multiSelect = o.optBoolean("multiSelect", false),
            lines = o.optInt("lines", 1),
            maxLines = o.optInt("maxLines", 6),
            placeholder = o.optString("placeholder", ""),
            fileTypes = decodeStringList(o.optJSONArray("fileTypes")),
            isHiddenState = o.optBoolean("isHiddenState", false),
            rawSchemaJson = o.optString("rawSchemaJson", "")
        )
    }

    private fun decodeStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it, "") }
    }

    private fun decodeIntList(arr: JSONArray?): List<Int> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optInt(it, 0) }
    }
}
