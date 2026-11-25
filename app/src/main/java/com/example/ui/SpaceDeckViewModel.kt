package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MainActivity
import com.example.R
import com.example.SpaceDeckApplication
import com.example.data.ProfileCodec
import com.example.data.local.JobRecordEntity
import com.example.data.local.MediaAssetEntity
import com.example.data.local.SpacePresetEntity
import com.example.data.local.SpaceProfileEntity
import com.example.data.local.SpaceRecordEntity
import com.example.model.AccountInfo
import com.example.model.ActiveJobState
import com.example.model.ComponentKind
import com.example.model.DiagnosticEvent
import com.example.model.EndpointManifest
import com.example.model.FieldCustomization
import com.example.model.FieldValue
import com.example.model.ManifestJsonCodec
import com.example.model.OutputItem
import com.example.model.RepoFileEntry
import com.example.model.RuntimeStage
import com.example.model.SpaceAuthMode
import com.example.model.SpaceManifest
import com.example.model.SpacePreset
import com.example.model.SpaceProfile
import com.example.model.SpaceSummary
import com.example.network.SpaceExecutionEngine
import com.example.network.SpaceIdentifierParser
import com.example.security.TokenRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ExploreTab(val label: String) {
    TRENDING("Trending"),
    MY_SPACES("My Spaces"),
    FAVORITES("Favorites"),
    RECENT("Recent")
}

enum class WorkbenchSubTab(val label: String) {
    CONTROLS("Controls"),
    OUTPUTS("Outputs"),
    SOURCE_GPU("Source & GPU"),
    FULL_SPACE("Full Space")
}

data class ExploreUiState(
    val selectedTab: ExploreTab = ExploreTab.TRENDING,
    val searchQuery: String = "",
    val selectedCategory: String? = null,
    val hardwareFilter: String? = null, // null, "zero", "gpu", "cpu"
    val spaces: List<SpaceSummary> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val quickUrlInput: String = ""
)

data class WorkbenchUiState(
    val isLoading: Boolean = false,
    val loadingMessage: String = "",
    val error: String? = null,
    val manifest: SpaceManifest? = null,
    val selectedEndpointId: String? = null,
    val fieldValues: Map<String, FieldValue> = emptyMap(),
    val profile: SpaceProfile? = null,
    val presets: List<SpacePreset> = emptyList(),
    val subTab: WorkbenchSubTab = WorkbenchSubTab.CONTROLS,
    val repoFiles: List<RepoFileEntry> = emptyList(),
    val selectedSourceFile: String = "app.py",
    val sourceFileContent: String = "",
    val isLoadingSourceFile: Boolean = false,
    val spaceAuthMode: SpaceAuthMode = SpaceAuthMode.DEFAULT_ACCOUNT,
    val isFavorite: Boolean = false,
    val isRecordingAudioFieldId: String? = null,
    val showAdvancedSection: Boolean = false
) {
    val selectedEndpoint: EndpointManifest?
        get() = manifest?.endpoints?.firstOrNull { it.id == selectedEndpointId }
            ?: manifest?.endpoints?.firstOrNull()
}

data class PendingMediaTransfer(
    val asset: MediaAssetEntity
)

class SpaceDeckViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SpaceDeckApplication
    private val container = app.container
    private val db = container.database

    // Navigation trigger for opening Workbench or showing snackbars
    private val _selectedDestination = MutableStateFlow("explore")
    val selectedDestination: StateFlow<String> = _selectedDestination.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _exploreState = MutableStateFlow(ExploreUiState())
    val exploreState: StateFlow<ExploreUiState> = _exploreState.asStateFlow()

    private val _workbenchState = MutableStateFlow(WorkbenchUiState())
    val workbenchState: StateFlow<WorkbenchUiState> = _workbenchState.asStateFlow()

    private val _diagnostics = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val diagnostics: StateFlow<List<DiagnosticEvent>> = _diagnostics.asStateFlow()

    private val _pendingMediaTransfer = MutableStateFlow<PendingMediaTransfer?>(null)
    val pendingMediaTransfer: StateFlow<PendingMediaTransfer?> = _pendingMediaTransfer.asStateFlow()

    private val _isVerifyingToken = MutableStateFlow(false)
    val isVerifyingToken: StateFlow<Boolean> = _isVerifyingToken.asStateFlow()

    private val _tokenError = MutableStateFlow<String?>(null)
    val tokenError: StateFlow<String?> = _tokenError.asStateFlow()

    private val _perSpaceAuthModes = MutableStateFlow<Map<String, SpaceAuthMode>>(emptyMap())
    val perSpaceAuthModes: StateFlow<Map<String, SpaceAuthMode>> = _perSpaceAuthModes.asStateFlow()

    val activeJob: StateFlow<ActiveJobState> = container.executionEngine.activeJob
    val outputs: StateFlow<List<OutputItem>> = container.executionEngine.outputs

    val favorites: StateFlow<List<SpaceRecordEntity>> = db.spaceRecordDao().observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recents: StateFlow<List<SpaceRecordEntity>> = db.spaceRecordDao().observeRecents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mediaLibrary: StateFlow<List<MediaAssetEntity>> = db.mediaAssetDao().observeAllAssets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val jobHistory: StateFlow<List<JobRecordEntity>> = db.jobRecordDao().observeAllJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accountInfo: StateFlow<AccountInfo?> = container.tokenStore.accountInfoFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isAnonymousMode: StateFlow<Boolean> = container.tokenStore.isAnonymousMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hasDefaultToken: StateFlow<Boolean> = container.tokenStore.hasDefaultToken
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val sdkVersionsLabel: String
        get() = container.hybridSdkBridge.sdkVersionsLabel

    val initialTestPresets: List<SpaceSummary> = listOf(
        SpaceSummary(
            spaceId = "victor/LongCat-Video-Avatar-1.6",
            author = "victor",
            title = "LongCat Video Avatar 1.6",
            emoji = "🎬",
            shortDescription = "ZeroGPU multi-modal video avatar synthesis test preset",
            sdk = "gradio",
            hardware = "zero-a10g",
            isZeroGpu = true,
            runtimeStage = RuntimeStage.RUNNING,
            tags = listOf("ZeroGPU", "Video", "Avatar")
        ),
        SpaceSummary(
            spaceId = "mrfakename/voice-isolator",
            author = "mrfakename",
            title = "Voice Isolator",
            emoji = "🎙️",
            shortDescription = "Audio vocal isolation & denoising test preset",
            sdk = "gradio",
            hardware = "zero-a10g",
            isZeroGpu = true,
            runtimeStage = RuntimeStage.RUNNING,
            tags = listOf("ZeroGPU", "Audio", "Speech")
        )
    )

    init {
        logDiagnostic("INFO", "Runtime", "Initialized with bundled ${container.hybridSdkBridge.sdkVersionsLabel}")
        refreshSpaces()
        refreshPerSpaceAuthModes()
    }

    fun navigateTo(route: String) {
        _selectedDestination.value = route
    }

    fun consumeSnackbar() {
        _snackbarMessage.value = null
    }

    fun showMessage(msg: String) {
        _snackbarMessage.value = msg
    }

    fun logDiagnostic(level: String, source: String, message: String) {
        val clean = TokenRedactor.sanitize(message)
        _diagnostics.update { list ->
            (listOf(DiagnosticEvent(level = level, source = source, message = clean)) + list).take(120)
        }
    }

    // ─── Explore & Discovery ──────────────────────────────────────────────────────────

    fun updateQuickUrl(input: String) {
        _exploreState.update { it.copy(quickUrlInput = input) }
    }

    fun selectExploreTab(tab: ExploreTab) {
        _exploreState.update { it.copy(selectedTab = tab) }
        if (tab == ExploreTab.TRENDING || tab == ExploreTab.MY_SPACES) {
            refreshSpaces()
        }
    }

    fun updateSearchQuery(query: String) {
        _exploreState.update { it.copy(searchQuery = query) }
    }

    fun selectCategory(category: String?) {
        _exploreState.update {
            it.copy(selectedCategory = if (it.selectedCategory == category) null else category)
        }
        refreshSpaces()
    }

    fun selectHardwareFilter(filter: String?) {
        _exploreState.update {
            it.copy(hardwareFilter = if (it.hardwareFilter == filter) null else filter)
        }
        refreshSpaces()
    }

    fun refreshSpaces() {
        viewModelScope.launch {
            val state = _exploreState.value
            val token = container.tokenStore.resolveTokenForSpace(null, "https://huggingface.co")
            val authorFilter = if (state.selectedTab == ExploreTab.MY_SPACES) {
                accountInfo.value?.username
            } else null

            if (state.selectedTab == ExploreTab.MY_SPACES && authorFilter.isNullOrBlank()) {
                _exploreState.update {
                    it.copy(
                        spaces = emptyList(),
                        isLoading = false,
                        error = "Sign in with your Hugging Face token in Vault to list My Spaces."
                    )
                }
                return@launch
            }

            _exploreState.update { it.copy(isLoading = true, error = null) }
            val result = container.hubClient.searchSpaces(
                query = state.searchQuery,
                sort = if (state.searchQuery.isNotBlank()) "likes" else "trendingScore",
                author = authorFilter,
                hardwareFilter = state.hardwareFilter,
                categoryTag = state.selectedCategory,
                limit = 30,
                token = token
            )
            result.onSuccess { list ->
                _exploreState.update { it.copy(spaces = list, isLoading = false, error = null) }
            }.onFailure { err ->
                val clean = TokenRedactor.sanitize(err.message ?: "Could not load Spaces")
                _exploreState.update { it.copy(isLoading = false, error = clean) }
                logDiagnostic("ERROR", "Hub", clean)
            }
        }
    }

    // ─── Opening & Inspecting a Space ─────────────────────────────────────────────────

    fun openSpace(rawInput: String, forceRefresh: Boolean = false) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return

        _selectedDestination.value = "workbench"
        container.executionEngine.clearOutputs()

        viewModelScope.launch {
            val parsed = SpaceIdentifierParser.parse(trimmed)
            val spaceKey = parsed?.spaceId ?: trimmed
            val authMode = container.tokenStore.getSpaceAuthMode(spaceKey)
            val token = container.tokenStore.resolveTokenForSpace(spaceKey)

            _workbenchState.update {
                it.copy(
                    isLoading = true,
                    loadingMessage = "Discovering $spaceKey (Live API + /config + Repo Source)…",
                    error = null,
                    spaceAuthMode = authMode,
                    subTab = WorkbenchSubTab.CONTROLS
                )
            }

            val result = container.discoveryEngine.discover(
                rawSpaceInput = trimmed,
                token = token,
                forceRefresh = forceRefresh
            )

            result.onSuccess { discovery ->
                discovery.diagnostics.forEach { logDiagnostic("INFO", "Discovery", it) }
                val manifest = discovery.manifest
                val spaceId = manifest.identity.spaceId

                val existingRecord = db.spaceRecordDao().getRecord(spaceId)
                val isFav = existingRecord?.isFavorite ?: false
                db.spaceRecordDao().upsertRecord(
                    SpaceRecordEntity(
                        spaceId = spaceId,
                        author = manifest.identity.author,
                        title = manifest.identity.title,
                        emoji = manifest.identity.emoji,
                        shortDescription = manifest.identity.shortDescription,
                        hardware = manifest.hardware.current,
                        isZeroGpu = manifest.hardware.isZeroGpu,
                        runtimeStage = manifest.identity.runtimeStage.raw,
                        isFavorite = isFav,
                        lastOpenedAtMs = System.currentTimeMillis()
                    )
                )

                val profileEntity = db.profileDao().getProfile(spaceId)
                val profile = profileEntity?.let {
                    runCatching { ProfileCodec.decodeProfile(it.profileJson, spaceId) }.getOrNull()
                } ?: SpaceProfile(spaceId = spaceId)

                val presets = db.profileDao().observePresets(spaceId).first().map {
                    SpacePreset(it.id, it.spaceId, it.endpointId, it.name, it.valuesJson, it.createdAtMs)
                }

                val preferredEndpoint = manifest.endpoints.firstOrNull { it.id in profile.favoriteEndpoints }
                    ?: manifest.endpoints.firstOrNull()

                val initialValues = buildInitialFieldValues(preferredEndpoint, profile)

                // If there's a pending "Send to Space" media transfer, inject it into the first compatible input!
                val pending = _pendingMediaTransfer.value
                val finalValues = if (pending != null && preferredEndpoint != null) {
                    val injected = injectMediaAssetIntoValues(preferredEndpoint, initialValues, pending.asset)
                    if (injected != null) {
                        _pendingMediaTransfer.value = null
                        showMessage("Loaded ${pending.asset.fileName} into ${manifest.identity.title}")
                        injected
                    } else {
                        initialValues
                    }
                } else {
                    initialValues
                }

                _workbenchState.update {
                    it.copy(
                        isLoading = false,
                        error = null,
                        manifest = manifest,
                        selectedEndpointId = preferredEndpoint?.id,
                        fieldValues = finalValues,
                        profile = profile,
                        presets = presets,
                        repoFiles = discovery.repoFiles,
                        selectedSourceFile = manifest.provenance.entryPointFile,
                        sourceFileContent = discovery.entryPointSource,
                        spaceAuthMode = container.tokenStore.getSpaceAuthMode(spaceId),
                        isFavorite = isFav
                    )
                }
            }.onFailure { err ->
                val clean = TokenRedactor.sanitize(err.message ?: "Failed to inspect Space")
                _workbenchState.update {
                    it.copy(
                        isLoading = false,
                        error = clean
                    )
                }
                logDiagnostic("ERROR", "Discovery", clean)
            }
        }
    }

    fun selectWorkbenchSubTab(tab: WorkbenchSubTab) {
        _workbenchState.update { it.copy(subTab = tab) }
    }

    fun selectEndpoint(endpointId: String) {
        val state = _workbenchState.value
        val ep = state.manifest?.endpoints?.firstOrNull { it.id == endpointId } ?: return
        val values = buildInitialFieldValues(ep, state.profile)
        _workbenchState.update {
            it.copy(
                selectedEndpointId = endpointId,
                fieldValues = values
            )
        }
    }

    fun updateFieldValue(fieldId: String, value: FieldValue) {
        _workbenchState.update { state ->
            val locked = state.profile?.customizations?.get(fieldId)?.locked == true
            if (locked) state else state.copy(fieldValues = state.fieldValues + (fieldId to value))
        }
    }

    fun toggleAdvancedSection() {
        _workbenchState.update { it.copy(showAdvancedSection = !it.showAdvancedSection) }
    }

    fun runCurrentEndpoint() {
        val state = _workbenchState.value
        val manifest = state.manifest ?: return
        val endpoint = state.selectedEndpoint ?: return
        viewModelScope.launch {
            val token = container.tokenStore.resolveTokenForSpace(manifest.identity.spaceId, manifest.identity.hostUrl)
            container.executionEngine.execute(
                manifest = manifest,
                endpoint = endpoint,
                fieldValues = state.fieldValues,
                token = token,
                onDiagnostic = { msg -> logDiagnostic("INFO", "Exec", msg) }
            )
        }
    }

    fun cancelActiveJob() {
        container.executionEngine.cancelActiveJob { msg ->
            logDiagnostic("WARN", "Exec", msg)
        }
    }

    fun toggleCurrentSpaceFavorite() {
        val manifest = _workbenchState.value.manifest ?: return
        val spaceId = manifest.identity.spaceId
        val next = !_workbenchState.value.isFavorite
        viewModelScope.launch {
            db.spaceRecordDao().setFavorite(spaceId, next)
            _workbenchState.update { it.copy(isFavorite = next) }
            showMessage(if (next) "Pinned to Favorites" else "Removed from Favorites")
        }
    }

    fun toggleFavoriteBySummary(summary: SpaceSummary) {
        viewModelScope.launch {
            val existing = db.spaceRecordDao().getRecord(summary.spaceId)
            val nextFav = !(existing?.isFavorite ?: false)
            db.spaceRecordDao().upsertRecord(
                SpaceRecordEntity(
                    spaceId = summary.spaceId,
                    author = summary.author,
                    title = summary.title,
                    emoji = summary.emoji,
                    shortDescription = summary.shortDescription,
                    hardware = summary.hardware,
                    isZeroGpu = summary.isZeroGpu,
                    runtimeStage = summary.runtimeStage.raw,
                    isFavorite = nextFav,
                    lastOpenedAtMs = existing?.lastOpenedAtMs ?: System.currentTimeMillis()
                )
            )
            if (_workbenchState.value.manifest?.identity?.spaceId == summary.spaceId) {
                _workbenchState.update { it.copy(isFavorite = nextFav) }
            }
        }
    }

    // ─── Source Inspector ─────────────────────────────────────────────────────────────

    fun loadSourceFile(filePath: String) {
        val manifest = _workbenchState.value.manifest ?: return
        viewModelScope.launch {
            _workbenchState.update { it.copy(selectedSourceFile = filePath, isLoadingSourceFile = true) }
            val token = container.tokenStore.resolveTokenForSpace(manifest.identity.spaceId)
            val content = container.discoveryEngine.fetchFileText(manifest.identity.spaceId, filePath, token)
            _workbenchState.update {
                it.copy(
                    isLoadingSourceFile = false,
                    sourceFileContent = content ?: "# Unable to load $filePath"
                )
            }
        }
    }

    // ─── Manual Manifest Override ─────────────────────────────────────────────────────

    fun applyManualManifestOverride(rawJson: String) {
        viewModelScope.launch {
            runCatching {
                val parsed = ManifestJsonCodec.fromJson(rawJson).copy(isManualOverride = true)
                container.discoveryEngine.saveManualOverride(parsed)
                val ep = parsed.endpoints.firstOrNull()
                _workbenchState.update {
                    it.copy(
                        manifest = parsed,
                        selectedEndpointId = ep?.id,
                        fieldValues = buildInitialFieldValues(ep, it.profile)
                    )
                }
                logDiagnostic("INFO", "Manifest", "Applied manual manifest override for ${parsed.identity.spaceId}")
                showMessage("Manual manifest override saved")
            }.onFailure { err ->
                showMessage("Invalid manifest JSON: ${err.message}")
            }
        }
    }

    fun resetManualOverride() {
        val spaceId = _workbenchState.value.manifest?.identity?.spaceId ?: return
        viewModelScope.launch {
            container.discoveryEngine.clearCachedManifest(spaceId)
            openSpace(spaceId, forceRefresh = true)
        }
    }

    // ─── Personalization: Space Profiles & Presets ───────────────────────────────────

    fun updateFieldCustomization(fieldId: String, transform: (FieldCustomization) -> FieldCustomization) {
        val state = _workbenchState.value
        val manifest = state.manifest ?: return
        val currentProfile = state.profile ?: SpaceProfile(spaceId = manifest.identity.spaceId)
        val existing = currentProfile.customizations[fieldId] ?: FieldCustomization(fieldId = fieldId)
        val updated = currentProfile.copy(
            customizations = currentProfile.customizations + (fieldId to transform(existing)),
            updatedAtMs = System.currentTimeMillis()
        )
        saveProfile(updated)
    }

    fun reorderField(endpoint: EndpointManifest, fieldId: String, moveUp: Boolean) {
        val state = _workbenchState.value
        val manifest = state.manifest ?: return
        val profile = state.profile ?: SpaceProfile(spaceId = manifest.identity.spaceId)
        val ordered = endpoint.inputs.filterNot { it.isHiddenState }
            .sortedBy { profile.customizations[it.id]?.orderIndex ?: endpoint.inputs.indexOf(it) }
            .toMutableList()

        val idx = ordered.indexOfFirst { it.id == fieldId }
        if (idx < 0) return
        val targetIdx = if (moveUp) idx - 1 else idx + 1
        if (targetIdx !in ordered.indices) return
        val item = ordered.removeAt(idx)
        ordered.add(targetIdx, item)

        val nextMap = profile.customizations.toMutableMap()
        ordered.forEachIndexed { pos, field ->
            val prev = nextMap[field.id] ?: FieldCustomization(fieldId = field.id)
            nextMap[field.id] = prev.copy(orderIndex = pos)
        }
        saveProfile(profile.copy(customizations = nextMap, updatedAtMs = System.currentTimeMillis()))
    }

    fun toggleFavoriteEndpoint(endpointId: String) {
        val state = _workbenchState.value
        val manifest = state.manifest ?: return
        val profile = state.profile ?: SpaceProfile(spaceId = manifest.identity.spaceId)
        val favs = if (endpointId in profile.favoriteEndpoints) {
            profile.favoriteEndpoints - endpointId
        } else {
            profile.favoriteEndpoints + endpointId
        }
        saveProfile(profile.copy(favoriteEndpoints = favs, updatedAtMs = System.currentTimeMillis()))
    }

    fun exportCurrentProfileJson(): String? {
        val profile = _workbenchState.value.profile ?: return null
        return ProfileCodec.encodeProfile(profile)
    }

    fun importProfileJson(rawJson: String) {
        val spaceId = _workbenchState.value.manifest?.identity?.spaceId ?: return
        runCatching {
            val decoded = ProfileCodec.decodeProfile(rawJson, spaceId).copy(spaceId = spaceId)
            saveProfile(decoded)
            showMessage("Imported Space profile (credential-free)")
        }.onFailure {
            showMessage("Invalid profile JSON: ${it.message}")
        }
    }

    fun saveCurrentAsPreset(name: String) {
        val state = _workbenchState.value
        val manifest = state.manifest ?: return
        val endpoint = state.selectedEndpoint ?: return
        val cleanName = name.trim().ifBlank { "Preset ${state.presets.size + 1}" }
        val encoded = ProfileCodec.encodeFieldValues(state.fieldValues)
        val preset = SpacePresetEntity(
            id = UUID.randomUUID().toString(),
            spaceId = manifest.identity.spaceId,
            endpointId = endpoint.id,
            name = cleanName,
            valuesJson = encoded,
            createdAtMs = System.currentTimeMillis()
        )
        viewModelScope.launch {
            db.profileDao().upsertPreset(preset)
            val refreshed = db.profileDao().observePresets(manifest.identity.spaceId).first().map {
                SpacePreset(it.id, it.spaceId, it.endpointId, it.name, it.valuesJson, it.createdAtMs)
            }
            _workbenchState.update { it.copy(presets = refreshed) }
            showMessage("Saved preset '$cleanName'")
        }
    }

    fun applyPreset(preset: SpacePreset) {
        val state = _workbenchState.value
        val decoded = ProfileCodec.decodeFieldValues(preset.valuesJson)
        _workbenchState.update {
            it.copy(
                selectedEndpointId = preset.endpointId,
                fieldValues = it.fieldValues + decoded
            )
        }
        showMessage("Applied preset '${preset.name}'")
    }

    fun deletePreset(presetId: String) {
        val spaceId = _workbenchState.value.manifest?.identity?.spaceId ?: return
        viewModelScope.launch {
            db.profileDao().deletePreset(presetId)
            val refreshed = db.profileDao().observePresets(spaceId).first().map {
                SpacePreset(it.id, it.spaceId, it.endpointId, it.name, it.valuesJson, it.createdAtMs)
            }
            _workbenchState.update { it.copy(presets = refreshed) }
        }
    }

    private fun saveProfile(profile: SpaceProfile) {
        _workbenchState.update { it.copy(profile = profile) }
        viewModelScope.launch {
            db.profileDao().upsertProfile(
                SpaceProfileEntity(
                    spaceId = profile.spaceId,
                    profileJson = ProfileCodec.encodeProfile(profile),
                    updatedAtMs = profile.updatedAtMs
                )
            )
        }
    }

    // ─── Audio Recording & Media Captures ────────────────────────────────────────────

    fun createCameraCaptureTarget(): Pair<Uri, File> = container.mediaLibraryManager.createCameraCaptureUri()

    fun toggleAudioRecordingForField(fieldId: String) {
        val current = _workbenchState.value.isRecordingAudioFieldId
        if (current == fieldId) {
            val file = container.mediaLibraryManager.stopAudioRecording()
            _workbenchState.update { it.copy(isRecordingAudioFieldId = null) }
            if (file != null) {
                updateFieldValue(
                    fieldId,
                    FieldValue.MediaRef(
                        localPath = file.absolutePath,
                        fileName = file.name,
                        mimeType = "audio/mp4"
                    )
                )
                showMessage("Recorded audio attached (${file.length() / 1024} KB)")
            }
        } else {
            container.mediaLibraryManager.startAudioRecording().onSuccess {
                _workbenchState.update { it.copy(isRecordingAudioFieldId = fieldId) }
            }.onFailure { err ->
                showMessage("Microphone error: ${err.message}")
            }
        }
    }

    fun attachUriToField(fieldId: String, uri: Uri, fallbackMime: String) {
        viewModelScope.launch {
            container.mediaLibraryManager.copyUriToLibrary(uri, spaceId = _workbenchState.value.manifest?.identity?.spaceId ?: "input")
                .onSuccess { asset ->
                    updateFieldValue(
                        fieldId,
                        FieldValue.MediaRef(
                            localPath = asset.localFilePath,
                            fileName = asset.fileName,
                            mimeType = asset.mimeType.ifBlank { fallbackMime },
                            mediaAssetId = asset.id
                        )
                    )
                }.onFailure { err ->
                    showMessage("Could not load file: ${err.message}")
                }
        }
    }

    fun attachMediaAssetToField(fieldId: String, asset: MediaAssetEntity) {
        updateFieldValue(
            fieldId,
            FieldValue.MediaRef(
                localPath = asset.localFilePath,
                fileName = asset.fileName,
                mimeType = asset.mimeType,
                mediaAssetId = asset.id
            )
        )
    }

    // ─── Shared Media Library & Cross-Space Reuse ────────────────────────────────────

    fun downloadMediaToDevice(localPath: String, fileName: String, mimeType: String) {
        viewModelScope.launch {
            container.mediaLibraryManager.saveToPublicDownloads(localPath, fileName, mimeType)
                .onSuccess { dest -> showMessage("Saved to $dest") }
                .onFailure { err -> showMessage("Download failed: ${err.message}") }
        }
    }

    fun buildShareIntent(localPath: String, mimeType: String): Intent? =
        container.mediaLibraryManager.buildShareIntent(localPath, mimeType)

    fun deleteMediaAsset(asset: MediaAssetEntity) {
        viewModelScope.launch {
            container.mediaLibraryManager.deleteAsset(asset)
            showMessage("Removed from Shared Media Library")
        }
    }

    fun startSendMediaToSpace(asset: MediaAssetEntity) {
        _pendingMediaTransfer.value = PendingMediaTransfer(asset)
    }

    fun cancelSendMediaToSpace() {
        _pendingMediaTransfer.value = null
    }

    fun startSendOutputToSpace(item: OutputItem) {
        viewModelScope.launch {
            val asset = item.mediaAssetId?.let { db.mediaAssetDao().getById(it) }
            if (asset != null) {
                _pendingMediaTransfer.value = PendingMediaTransfer(asset)
            } else if (!item.localCachedPath.isNullOrBlank()) {
                val f = File(item.localCachedPath)
                if (f.exists()) {
                    val tempEntity = MediaAssetEntity(
                        id = UUID.randomUUID().toString(),
                        spaceId = _workbenchState.value.manifest?.identity?.spaceId ?: "output",
                        jobId = null,
                        mediaType = item.kind.name.lowercase(),
                        mimeType = item.mimeType ?: "application/octet-stream",
                        fileName = item.fileName ?: f.name,
                        localFilePath = f.absolutePath,
                        originalRemoteUrl = item.mediaUrl,
                        sizeBytes = f.length(),
                        createdAtMs = System.currentTimeMillis()
                    )
                    db.mediaAssetDao().upsertAsset(tempEntity)
                    _pendingMediaTransfer.value = PendingMediaTransfer(tempEntity)
                }
            }
        }
    }

    // ─── Authentication & Vault ───────────────────────────────────────────────────────

    fun saveAndVerifyToken(rawToken: String) {
        val cleaned = rawToken.trim()
        if (cleaned.isEmpty()) {
            _tokenError.value = "Enter a valid Hugging Face access token (hf_...)."
            return
        }
        viewModelScope.launch {
            _isVerifyingToken.value = true
            _tokenError.value = null
            container.tokenStore.saveDefaultToken(cleaned)

            // Verify using bundled @huggingface/hub whoAmI() first, falling back to native Hub client
            val sdkWhoAmI = container.hybridSdkBridge.whoAmI(cleaned).getOrNull()?.optJSONObject("data")
            val verifiedInfo: Result<AccountInfo> = if (sdkWhoAmI != null && sdkWhoAmI.has("name")) {
                val orgsArr = sdkWhoAmI.optJSONArray("orgs")
                val orgs = if (orgsArr != null) {
                    (0 until orgsArr.length()).mapNotNull { orgsArr.optJSONObject(it)?.optString("name") }
                } else emptyList()
                Result.success(
                    AccountInfo(
                        username = sdkWhoAmI.optString("name"),
                        fullName = sdkWhoAmI.optString("fullname", ""),
                        avatarUrl = sdkWhoAmI.optString("avatarUrl", ""),
                        isPro = sdkWhoAmI.optBoolean("isPro", false),
                        accountType = sdkWhoAmI.optString("type", "user"),
                        orgs = orgs,
                        verifiedAtMs = System.currentTimeMillis()
                    )
                )
            } else {
                container.hubClient.whoAmI(cleaned)
            }

            verifiedInfo.onSuccess { info ->
                container.tokenStore.saveAccountInfo(info)
                _isVerifyingToken.value = false
                logDiagnostic("INFO", "Auth", "Verified Hugging Face account @${info.username} (PRO=${info.isPro})")
                showMessage("Signed in as @${info.username}")
            }.onFailure { err ->
                _isVerifyingToken.value = false
                val msg = TokenRedactor.sanitize(err.message ?: "Token verification failed")
                _tokenError.value = msg
                logDiagnostic("ERROR", "Auth", msg)
            }
        }
    }

    fun clearDefaultToken() {
        viewModelScope.launch {
            container.tokenStore.clearDefaultToken()
            _tokenError.value = null
            showMessage("Token cleared from Android Keystore vault")
        }
    }

    fun setAnonymousMode(enabled: Boolean) {
        viewModelScope.launch {
            container.tokenStore.setAnonymousMode(enabled)
            showMessage(if (enabled) "Anonymous mode enabled" else "Using saved account token")
        }
    }

    fun setSpaceAuthPreference(spaceId: String, mode: SpaceAuthMode, customToken: String? = null) {
        viewModelScope.launch {
            container.tokenStore.setSpaceAuthPreference(spaceId, mode, customToken)
            refreshPerSpaceAuthModes()
            if (_workbenchState.value.manifest?.identity?.spaceId == spaceId) {
                _workbenchState.update { it.copy(spaceAuthMode = mode) }
            }
            showMessage("Updated auth mode for $spaceId")
        }
    }

    private fun refreshPerSpaceAuthModes() {
        viewModelScope.launch {
            _perSpaceAuthModes.value = container.tokenStore.getAllSpaceAuthModes()
        }
    }

    // ─── Home-Screen Shortcut & Incoming Share Intents ────────────────────────────────

    fun pinCurrentSpaceShortcut() {
        val manifest = _workbenchState.value.manifest ?: return
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(app)) {
            showMessage("Home-screen shortcuts are not supported by this launcher.")
            return
        }
        val launchIntent = Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://huggingface.co/spaces/${manifest.identity.spaceId}")
        }
        val shortcut = ShortcutInfoCompat.Builder(app, "space_${manifest.identity.spaceId}")
            .setShortLabel(manifest.identity.title.take(18))
            .setLongLabel("${manifest.identity.title} (${manifest.identity.spaceId})")
            .setIcon(IconCompat.createWithResource(app, R.mipmap.ic_launcher))
            .setIntent(launchIntent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(app, shortcut, null)
        showMessage("Requested home-screen shortcut for ${manifest.identity.title}")
    }

    fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val url = intent.dataString ?: return
                if (SpaceIdentifierParser.parse(url) != null) {
                    openSpace(url)
                }
            }
            Intent.ACTION_SEND -> {
                val type = intent.type.orEmpty()
                if (type == "text/plain") {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                    val urlMatch = Regex("""https?://[^\s]+""").find(sharedText)?.value ?: sharedText
                    if (SpaceIdentifierParser.parse(urlMatch) != null) {
                        openSpace(urlMatch)
                    } else {
                        _exploreState.update { it.copy(quickUrlInput = sharedText) }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
                    viewModelScope.launch {
                        container.mediaLibraryManager.copyUriToLibrary(uri, spaceId = "shared_intent")
                            .onSuccess { asset ->
                                startSendMediaToSpace(asset)
                                showMessage("Imported ${asset.fileName}. Choose a Space to send it to!")
                            }
                    }
                }
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────────

    private fun buildInitialFieldValues(
        endpoint: EndpointManifest?,
        profile: SpaceProfile?
    ): Map<String, FieldValue> {
        if (endpoint == null) return emptyMap()
        val map = mutableMapOf<String, FieldValue>()
        endpoint.inputs.forEach { field ->
            val customDefault = profile?.customizations?.get(field.id)?.customDefaultJson
            val effectiveField = if (customDefault != null) field.copy(defaultValueJson = customDefault) else field
            map[field.id] = SpaceExecutionEngine.defaultValueForField(effectiveField)
        }
        return map
    }

    private fun injectMediaAssetIntoValues(
        endpoint: EndpointManifest,
        values: Map<String, FieldValue>,
        asset: MediaAssetEntity
    ): Map<String, FieldValue>? {
        val targetField = endpoint.inputs.firstOrNull { field ->
            when (asset.mediaType) {
                "image" -> field.kind == ComponentKind.IMAGE || field.kind == ComponentKind.FILE || field.kind == ComponentKind.MULTIMODAL
                "audio" -> field.kind == ComponentKind.AUDIO || field.kind == ComponentKind.FILE
                "video" -> field.kind == ComponentKind.VIDEO || field.kind == ComponentKind.FILE
                else -> field.kind == ComponentKind.FILE
            }
        } ?: return null

        val ref = FieldValue.MediaRef(
            localPath = asset.localFilePath,
            fileName = asset.fileName,
            mimeType = asset.mimeType,
            mediaAssetId = asset.id
        )
        val newValue = if (targetField.kind == ComponentKind.MULTIMODAL) {
            val prev = values[targetField.id] as? FieldValue.Multimodal ?: FieldValue.Multimodal()
            prev.copy(files = prev.files + ref)
        } else ref

        return values + (targetField.id to newValue)
    }

    fun deleteJobRecord(jobId: String) {
        viewModelScope.launch {
            db.jobRecordDao().deleteJob(jobId)
        }
    }

    fun clearDiagnostics() {
        _diagnostics.value = emptyList()
    }
}
