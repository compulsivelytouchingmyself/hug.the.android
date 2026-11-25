package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.MediaAssetEntity
import com.example.model.ActiveJobState
import com.example.model.ComponentField
import com.example.model.ComponentKind
import com.example.model.DiagnosticEvent
import com.example.model.EndpointManifest
import com.example.model.FieldCustomization
import com.example.model.FieldValue
import com.example.model.JobStatus
import com.example.model.ManifestJsonCodec
import com.example.model.OutputItem
import com.example.model.SpaceAuthMode
import com.example.model.SpacePreset
import com.example.model.SpaceProfile
import com.example.model.SpaceSummary
import com.example.ui.WorkbenchSubTab
import com.example.ui.WorkbenchUiState
import com.example.ui.components.DynamicFieldCard
import com.example.ui.components.FullSpaceWebView
import com.example.ui.components.OutputItemCard
import com.example.ui.components.SourceAndGpuInspector
import com.example.ui.components.oledTextFieldColors
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianContainer
import com.example.ui.theme.ObsidianContainerHigh
import com.example.ui.theme.ObsidianContainerLow
import com.example.ui.theme.OledBlack
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.StatusWarning
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ZeroGpuViolet
import com.example.ui.theme.ZeroGpuVioletContainer
import java.io.File
import java.util.Locale

@Composable
fun WorkbenchScreen(
    state: WorkbenchUiState,
    activeJob: ActiveJobState,
    outputs: List<OutputItem>,
    mediaLibrary: List<MediaAssetEntity>,
    diagnostics: List<DiagnosticEvent>,
    testPresets: List<SpaceSummary>,
    onOpenSpace: (String, Boolean) -> Unit,
    onSelectSubTab: (WorkbenchSubTab) -> Unit,
    onSelectEndpoint: (String) -> Unit,
    onUpdateFieldValue: (String, FieldValue) -> Unit,
    onToggleAdvanced: () -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPinShortcut: () -> Unit,
    onLoadSourceFile: (String) -> Unit,
    onCreateCameraTarget: () -> Pair<Uri, File>,
    onAttachUri: (String, Uri, String) -> Unit,
    onAttachMediaAsset: (String, MediaAssetEntity) -> Unit,
    onToggleAudioRecord: (String) -> Unit,
    onDownloadOutput: (OutputItem) -> Unit,
    onShareOutput: (OutputItem) -> Intent?,
    onSendOutputToSpace: (OutputItem) -> Unit,
    onUpdateFieldCustomization: (String, (FieldCustomization) -> FieldCustomization) -> Unit,
    onReorderField: (EndpointManifest, String, Boolean) -> Unit,
    onToggleFavoriteEndpoint: (String) -> Unit,
    onExportProfileJson: () -> String?,
    onImportProfileJson: (String) -> Unit,
    onSavePreset: (String) -> Unit,
    onApplyPreset: (SpacePreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onApplyManualManifest: (String) -> Unit,
    onResetManualOverride: () -> Unit,
    onSetSpaceAuth: (String, SpaceAuthMode, String?) -> Unit,
    onOpenVault: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var showCustomizeDialog by remember { mutableStateOf(false) }
    var showPresetsDialog by remember { mutableStateOf(false) }
    var showManifestDialog by remember { mutableStateOf(false) }
    var showSpaceAuthDialog by remember { mutableStateOf(false) }
    var libraryPickerTarget by remember { mutableStateOf<Pair<String, ComponentKind>?>(null) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }

    val manifest = state.manifest

    if (state.isLoading) {
        Box(
            modifier = modifier.fillMaxSize().background(OledBlack),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                CircularProgressIndicator(color = SignalAmber)
                Text(
                    text = state.loadingMessage.ifBlank { "Inspecting Space…" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Probing Live API schema, /config runtime, and repository source…",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
        return
    }

    if (manifest == null) {
        Box(
            modifier = modifier.fillMaxSize().background(OledBlack).padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("No Space Loaded", style = MaterialTheme.typography.headlineSmall)
                if (state.error != null) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                } else {
                    Text(
                        text = "Pick a Space from Explore or launch a test preset below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                testPresets.forEach { preset ->
                    OutlinedButton(
                        onClick = { onOpenSpace(preset.spaceId, false) },
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                    ) {
                        Text("${preset.emoji}  ${preset.spaceId}")
                    }
                }
            }
        }
        return
    }

    val selectedEndpoint = state.selectedEndpoint
    val isRunning = activeJob.status in setOf(JobStatus.UPLOADING, JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.STREAMING)

    Column(modifier = modifier.fillMaxSize().background(OledBlack)) {
        // Space Header Bar
        Surface(
            color = ObsidianContainerLow,
            border = BorderStroke(1.dp, HairlineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${manifest.identity.emoji} ${manifest.identity.title}".trim(),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = manifest.identity.spaceId,
                            style = MaterialTheme.typography.labelSmall,
                            color = TelemetryCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onToggleFavorite, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(
                                imageVector = if (state.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = stringResource(R.string.cd_favorite),
                                tint = if (state.isFavorite) SignalAmber else TextSecondary
                            )
                        }
                        IconButton(
                            onClick = { onOpenSpace(manifest.identity.spaceId, true) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_refresh), tint = TextSecondary)
                        }
                    }
                }

                // Telemetry & Provenance strip
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { RuntimeStagePill(manifest.identity.runtimeStage) }
                    item {
                        Surface(
                            color = if (manifest.hardware.isZeroGpu) ZeroGpuVioletContainer else ObsidianContainerHigh,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (manifest.hardware.isZeroGpu) "ZeroGPU" else manifest.hardware.current,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (manifest.hardware.isZeroGpu) ZeroGpuViolet else TextSecondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (manifest.provenance.usedLiveApi) {
                        item { ProvenanceChip("Live API", StatusEmerald) }
                    }
                    if (manifest.provenance.usedRuntimeConfig) {
                        item { ProvenanceChip("/config", TelemetryCyan) }
                    }
                    if (manifest.provenance.usedRepoSource) {
                        item { ProvenanceChip(manifest.provenance.entryPointFile, SignalAmber) }
                    }
                    if (manifest.provenance.isProvisional) {
                        item { ProvenanceChip("PROVISIONAL", StatusWarning) }
                    }
                    if (manifest.isManualOverride) {
                        item { ProvenanceChip("OVERRIDE", ZeroGpuViolet) }
                    }
                }

                // Quick Workbench tools strip
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        AssistChip(
                            onClick = { showCustomizeDialog = true },
                            label = { Text("Customize", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.cd_customize_profile), modifier = Modifier.size(14.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = ObsidianContainer)
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { showPresetsDialog = true },
                            label = { Text("Presets (${state.presets.size})", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Default.Save, contentDescription = stringResource(R.string.cd_presets), modifier = Modifier.size(14.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = ObsidianContainer)
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { showManifestDialog = true },
                            label = { Text("Manifest", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = ObsidianContainer)
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { showSpaceAuthDialog = true },
                            label = { Text("Auth: ${state.spaceAuthMode.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = ObsidianContainer)
                        )
                    }
                    item {
                        AssistChip(
                            onClick = onPinShortcut,
                            label = { Text("Shortcut", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Default.AddToHomeScreen, contentDescription = stringResource(R.string.cd_space_shortcut), modifier = Modifier.size(14.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = ObsidianContainer)
                        )
                    }
                }
            }
        }

        // Sub-Tabs: Controls | Outputs | Source & GPU | Full Space
        TabRow(
            selectedTabIndex = WorkbenchSubTab.entries.indexOf(state.subTab),
            containerColor = ObsidianContainerLow,
            contentColor = SignalAmber,
            indicator = { positions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(positions[WorkbenchSubTab.entries.indexOf(state.subTab)]),
                    color = SignalAmber
                )
            }
        ) {
            WorkbenchSubTab.entries.forEach { tab ->
                val title = if (tab == WorkbenchSubTab.OUTPUTS && outputs.isNotEmpty()) {
                    "Outputs (${outputs.size})"
                } else tab.label
                Tab(
                    selected = state.subTab == tab,
                    onClick = { onSelectSubTab(tab) },
                    text = { Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.testTag("workbench_tab_${tab.name.lowercase()}")
                )
            }
        }

        // Main Scrollable Body
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Live Job Status Card (always visible on Controls/Outputs when active or completed/failed)
            if (activeJob.status != JobStatus.IDLE && state.subTab != WorkbenchSubTab.FULL_SPACE) {
                item {
                    LiveJobStatusCard(
                        job = activeJob,
                        onOpenVault = onOpenVault,
                        onCopyError = { text ->
                            clipboard.setText(AnnotatedString(text))
                            onShowMessage("Error details copied to clipboard")
                        }
                    )
                }
            }

            // Warnings banner (e.g., sleeping Space provisional notice)
            if (manifest.provenance.warnings.isNotEmpty() && state.subTab == WorkbenchSubTab.CONTROLS) {
                item {
                    Surface(
                        color = StatusWarning.copy(alpha = 0.12f),
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, StatusWarning.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            manifest.provenance.warnings.forEach { warn ->
                                Text(warn, style = MaterialTheme.typography.bodySmall, color = StatusWarning)
                            }
                        }
                    }
                }
            }

            when (state.subTab) {
                WorkbenchSubTab.CONTROLS -> {
                    // Endpoint selector
                    if (manifest.endpoints.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("CALLABLE ENDPOINTS (${manifest.endpoints.size})", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(manifest.endpoints, key = { it.id }) { ep ->
                                        val isSel = ep.id == selectedEndpoint?.id
                                        val isFavEp = ep.id in (state.profile?.favoriteEndpoints.orEmpty())
                                        FilterChip(
                                            selected = isSel,
                                            onClick = { onSelectEndpoint(ep.id) },
                                            label = {
                                                val gpuTag = ep.zeroGpuDecorator?.declaredDurationSeconds?.let { " • ${it}s GPU" } ?: ""
                                                Text("${ep.label}$gpuTag", style = MaterialTheme.typography.labelSmall)
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = if (isFavEp) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                                    contentDescription = "Favorite endpoint",
                                                    modifier = Modifier.size(16.dp).clickable { onToggleFavoriteEndpoint(ep.id) }
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = SignalAmber,
                                                selectedLabelColor = Color.Black,
                                                selectedLeadingIconColor = Color.Black
                                            ),
                                            modifier = Modifier.testTag("endpoint_chip_${ep.label}")
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (selectedEndpoint == null) {
                        item {
                            Surface(
                                color = ObsidianContainerLow,
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, HairlineBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("No Callable Endpoints Discovered", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "This Space may still be waking up, uses a non-standard Gradio wrapper, or hides its API. Switch to Full Space View or inspect Source & GPU.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                    OutlinedButton(onClick = { onSelectSubTab(WorkbenchSubTab.FULL_SPACE) }) {
                                        Text("Open Full Space View")
                                    }
                                }
                            }
                        }
                    } else {
                        val profile = state.profile
                        val visibleInputs = selectedEndpoint.inputs
                            .filterNot { it.isHiddenState }
                            .filterNot { profile?.customizations?.get(it.id)?.hidden == true }
                            .sortedBy { profile?.customizations?.get(it.id)?.orderIndex ?: selectedEndpoint.inputs.indexOf(it) }

                        val primaryInputs = visibleInputs.filterNot { profile?.customizations?.get(it.id)?.advanced == true }
                        val advancedInputs = visibleInputs.filter { profile?.customizations?.get(it.id)?.advanced == true }

                        items(primaryInputs, key = { it.id }) { field ->
                            val isLocked = profile?.customizations?.get(field.id)?.locked == true
                            DynamicFieldCard(
                                field = field,
                                value = state.fieldValues[field.id] ?: FieldValue.Empty,
                                isLocked = isLocked,
                                isRecordingAudio = state.isRecordingAudioFieldId == field.id,
                                mediaLibrary = mediaLibrary,
                                onValueChange = { v -> onUpdateFieldValue(field.id, v) },
                                onCreateCameraTarget = onCreateCameraTarget,
                                onAttachUri = { uri, mime -> onAttachUri(field.id, uri, mime) },
                                onToggleAudioRecord = { onToggleAudioRecord(field.id) },
                                onOpenLibraryPicker = { kind -> libraryPickerTarget = field.id to kind }
                            )
                        }

                        if (advancedInputs.isNotEmpty()) {
                            item {
                                Surface(
                                    color = ObsidianContainerLow,
                                    shape = MaterialTheme.shapes.medium,
                                    border = BorderStroke(1.dp, HairlineBorder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onToggleAdvanced() }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Advanced Settings (${advancedInputs.size})",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = TelemetryCyan
                                        )
                                        Icon(
                                            imageVector = if (state.showAdvancedSection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = "Toggle advanced settings",
                                            tint = TelemetryCyan
                                        )
                                    }
                                }
                            }
                            if (state.showAdvancedSection) {
                                items(advancedInputs, key = { "adv_${it.id}" }) { field ->
                                    val isLocked = profile?.customizations?.get(field.id)?.locked == true
                                    DynamicFieldCard(
                                        field = field,
                                        value = state.fieldValues[field.id] ?: FieldValue.Empty,
                                        isLocked = isLocked,
                                        isRecordingAudio = state.isRecordingAudioFieldId == field.id,
                                        mediaLibrary = mediaLibrary,
                                        onValueChange = { v -> onUpdateFieldValue(field.id, v) },
                                        onCreateCameraTarget = onCreateCameraTarget,
                                        onAttachUri = { uri, mime -> onAttachUri(field.id, uri, mime) },
                                        onToggleAudioRecord = { onToggleAudioRecord(field.id) },
                                        onOpenLibraryPicker = { kind -> libraryPickerTarget = field.id to kind }
                                    )
                                }
                            }
                        }

                        // Compact output preview right beneath controls once results arrive
                        if (outputs.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("LATEST OUTPUTS (${outputs.size})", style = MaterialTheme.typography.labelSmall, color = SignalAmber)
                                    TextButton(onClick = { onSelectSubTab(WorkbenchSubTab.OUTPUTS) }) {
                                        Text("Open Outputs Tab")
                                    }
                                }
                            }
                            items(outputs, key = { "ctrl_out_${it.index}" }) { item ->
                                OutputItemCard(
                                    item = item,
                                    onDownload = onDownloadOutput,
                                    onShare = { out ->
                                        onShareOutput(out)?.let { intent ->
                                            context.startActivity(Intent.createChooser(intent, "Share output"))
                                        }
                                    },
                                    onSendToSpace = onSendOutputToSpace,
                                    onCopyText = onShowMessage
                                )
                            }
                        }
                    }
                }

                WorkbenchSubTab.OUTPUTS -> {
                    if (outputs.isEmpty()) {
                        item {
                            Surface(
                                color = ObsidianContainerLow,
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, HairlineBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("No Outputs Yet", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Tap Run / Generate in the sticky deck bar below. Generated images, audio, and video are cached automatically in the Shared Media Library.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    } else {
                        items(outputs, key = { "tab_out_${it.index}" }) { item ->
                            OutputItemCard(
                                item = item,
                                onDownload = onDownloadOutput,
                                onShare = { out ->
                                    onShareOutput(out)?.let { intent ->
                                        context.startActivity(Intent.createChooser(intent, "Share output"))
                                    }
                                },
                                onSendToSpace = onSendOutputToSpace,
                                onCopyText = onShowMessage
                            )
                        }
                    }
                }

                WorkbenchSubTab.SOURCE_GPU -> {
                    item {
                        SourceAndGpuInspector(
                            hardware = manifest.hardware,
                            activeJob = activeJob,
                            repoFiles = state.repoFiles,
                            selectedFile = state.selectedSourceFile,
                            sourceContent = state.sourceFileContent,
                            isLoadingFile = state.isLoadingSourceFile,
                            onSelectFile = onLoadSourceFile,
                            onCopied = onShowMessage
                        )
                    }
                }

                WorkbenchSubTab.FULL_SPACE -> {
                    item {
                        FullSpaceWebView(
                            hostUrl = manifest.identity.hostUrl,
                            hubUrl = "https://huggingface.co/spaces/${manifest.identity.spaceId}"
                        )
                    }
                }
            }

            // Expandable Sanitized Technical Diagnostics (Selectable + One-Tap Copy)
            item {
                val diagHeaderLine = "Host: ${manifest.identity.hostUrl} | Gradio: ${manifest.provenance.gradioVersion.ifBlank { "auto" }} | Transport: ${manifest.provenance.sdkTransportUsed}"
                Surface(
                    color = ObsidianContainerLow,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, HairlineBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { diagnosticsExpanded = !diagnosticsExpanded }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Technical Diagnostics (${diagnostics.size}) • Sanitized",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    val fullLog = buildString {
                                        appendLine(diagHeaderLine)
                                        if (!activeJob.errorMessage.isNullOrBlank()) {
                                            appendLine("LAST ERROR: ${activeJob.errorMessage}")
                                        }
                                        diagnostics.take(40).forEach { ev ->
                                            appendLine("[${ev.level}/${ev.source}] ${ev.message}")
                                        }
                                    }
                                    clipboard.setText(AnnotatedString(fullLog))
                                    onShowMessage("Copied ${diagnostics.take(40).size} diagnostic lines")
                                },
                                modifier = Modifier.minimumInteractiveComponentSize().testTag("copy_diagnostics_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy technical diagnostics",
                                    tint = TelemetryCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Icon(
                                imageVector = if (diagnosticsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle diagnostics",
                                tint = TextSecondary
                            )
                        }
                        AnimatedVisibility(visible = diagnosticsExpanded) {
                            SelectionContainer {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(ObsidianContainer)
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = diagHeaderLine,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TelemetryCyan
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = HairlineBorder)
                                    diagnostics.take(40).forEach { ev ->
                                        Text(
                                            text = "[${ev.level}/${ev.source}] ${ev.message}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (ev.level == "ERROR") StatusCrimson else TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Sticky OLED Run / Generate Action Bar
        if (state.subTab != WorkbenchSubTab.FULL_SPACE && selectedEndpoint != null) {
            Surface(
                color = ObsidianContainerLow,
                border = BorderStroke(1.dp, HairlineBorder),
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedEndpoint.callableTarget,
                            style = MaterialTheme.typography.labelMedium,
                            color = SignalAmber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val sub = if (isRunning) {
                            "${activeJob.progressMessage} (${String.format(Locale.US, "%.1fs", activeJob.elapsedMs / 1000.0)})"
                        } else {
                            "${selectedEndpoint.inputs.count { !it.isHiddenState }} inputs → ${selectedEndpoint.outputs.size} outputs"
                        }
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (isRunning) {
                        Button(
                            onClick = onCancel,
                            colors = ButtonDefaults.buttonColors(containerColor = StatusCrimson, contentColor = Color.White),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("cancel_job_button")
                        ) {
                            Icon(Icons.Default.Cancel, contentDescription = "Cancel job", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Cancel")
                        }
                    } else {
                        Button(
                            onClick = onRun,
                            colors = ButtonDefaults.buttonColors(containerColor = SignalAmber, contentColor = Color.Black),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("run_space_button")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Run Space", modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Run / Generate", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // ─── Dialog: Shared Media Library Picker for Input Field ──────────────────────────
    libraryPickerTarget?.let { (fieldId, kind) ->
        val filtered = mediaLibrary.filter { asset ->
            when (kind) {
                ComponentKind.IMAGE -> asset.mediaType == "image"
                ComponentKind.AUDIO -> asset.mediaType == "audio"
                ComponentKind.VIDEO -> asset.mediaType == "video"
                else -> true
            }
        }
        AlertDialog(
            onDismissRequest = { libraryPickerTarget = null },
            containerColor = ObsidianContainerHigh,
            title = { Text("Pick from Shared Media Library") },
            text = {
                if (filtered.isEmpty()) {
                    Text("No compatible cached ${kind.name.lowercase()} files in Shared Media Library yet.", color = TextSecondary)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(280.dp)) {
                        items(filtered, key = { it.id }) { asset ->
                            Surface(
                                color = ObsidianContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    onAttachMediaAsset(fieldId, asset)
                                    libraryPickerTarget = null
                                }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(asset.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${asset.spaceId} • ${asset.sizeBytes / 1024} KB", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { libraryPickerTarget = null }) { Text("Close") } }
        )
    }

    // ─── Dialog: Customize Controls / Space Profile (Credential-Free Export/Import) ──
    if (showCustomizeDialog && selectedEndpoint != null) {
        var importText by remember { mutableStateOf("") }
        var showImportBox by remember { mutableStateOf(false) }
        val profile = state.profile ?: SpaceProfile(manifest.identity.spaceId)
        val orderedFields = selectedEndpoint.inputs
            .filterNot { it.isHiddenState }
            .sortedBy { profile.customizations[it.id]?.orderIndex ?: selectedEndpoint.inputs.indexOf(it) }

        AlertDialog(
            onDismissRequest = { showCustomizeDialog = false },
            containerColor = ObsidianContainerHigh,
            title = { Text("Personalize Controls") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(360.dp)) {
                    items(orderedFields, key = { it.id }) { field ->
                        val cust = profile.customizations[field.id] ?: FieldCustomization(field.id)
                        Surface(color = ObsidianContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(field.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { onReorderField(selectedEndpoint, field.id, true) }) {
                                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move up", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { onReorderField(selectedEndpoint, field.id, false) }) {
                                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move down", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { onUpdateFieldCustomization(field.id) { it.copy(hidden = !it.hidden) } }) {
                                        Icon(
                                            if (cust.hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle hide",
                                            tint = if (cust.hidden) StatusCrimson else TextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(onClick = { onUpdateFieldCustomization(field.id) { it.copy(locked = !it.locked) } }) {
                                        Icon(
                                            if (cust.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                                            contentDescription = "Toggle lock",
                                            tint = if (cust.locked) SignalAmber else TextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    FilterChip(
                                        selected = cust.advanced,
                                        onClick = { onUpdateFieldCustomization(field.id) { it.copy(advanced = !it.advanced) } },
                                        label = { Text("Adv", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                    }
                    item {
                        HorizontalDivider(color = HairlineBorder, modifier = Modifier.padding(vertical = 4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onExportProfileJson()?.let { json ->
                                        clipboard.setText(AnnotatedString(json))
                                        onShowMessage("Copied credential-free profile JSON")
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Export JSON", style = MaterialTheme.typography.labelSmall) }
                            OutlinedButton(
                                onClick = { showImportBox = !showImportBox },
                                modifier = Modifier.weight(1f)
                            ) { Text("Import JSON", style = MaterialTheme.typography.labelSmall) }
                        }
                        if (showImportBox) {
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = importText,
                                onValueChange = { importText = it },
                                placeholder = { Text("Paste exported Space profile JSON") },
                                minLines = 3,
                                colors = oledTextFieldColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            Button(onClick = {
                                onImportProfileJson(importText)
                                showImportBox = false
                            }) { Text("Apply Profile") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCustomizeDialog = false }) { Text("Done") } }
        )
    }

    // ─── Dialog: Generation Presets ───────────────────────────────────────────────────
    if (showPresetsDialog) {
        var presetName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPresetsDialog = false },
            containerColor = ObsidianContainerHigh,
            title = { Text("Generation Presets") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = presetName,
                            onValueChange = { presetName = it },
                            singleLine = true,
                            placeholder = { Text("Preset name") },
                            colors = oledTextFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                onSavePreset(presetName)
                                presetName = ""
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SignalAmber, contentColor = Color.Black)
                        ) { Text("Save") }
                    }
                    if (state.presets.isEmpty()) {
                        Text("No saved presets for this Space yet.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.height(200.dp)) {
                            items(state.presets, key = { it.id }) { preset ->
                                Surface(color = ObsidianContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = preset.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f).clickable {
                                                onApplyPreset(preset)
                                                showPresetsDialog = false
                                            }
                                        )
                                        IconButton(onClick = { onDeletePreset(preset.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete), tint = StatusCrimson, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPresetsDialog = false }) { Text("Close") } }
        )
    }

    // ─── Dialog: Normalized Manifest JSON & Manual Override ──────────────────────────
    if (showManifestDialog) {
        var editableJson by remember(manifest) { mutableStateOf(ManifestJsonCodec.toJson(manifest)) }
        AlertDialog(
            onDismissRequest = { showManifestDialog = false },
            containerColor = ObsidianContainerHigh,
            title = { Text("Normalized Space Manifest") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Inspect or manually override the discovered JSON manifest for unusual Spaces.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = editableJson,
                        onValueChange = { editableJson = it },
                        textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        colors = oledTextFieldColors(),
                        modifier = Modifier.fillMaxWidth().height(280.dp)
                    )
                }
            },
            dismissButton = {
                if (manifest.isManualOverride) {
                    TextButton(onClick = {
                        onResetManualOverride()
                        showManifestDialog = false
                    }) { Text("Reset Auto", color = StatusCrimson) }
                } else {
                    TextButton(onClick = { showManifestDialog = false }) { Text("Cancel") }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onApplyManualManifest(editableJson)
                        showManifestDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SignalAmber, contentColor = Color.Black)
                ) { Text("Save Override") }
            }
        )
    }

    // ─── Dialog: Per-Space Authentication Preference ─────────────────────────────────
    if (showSpaceAuthDialog) {
        var selectedMode by remember(state.spaceAuthMode) { mutableStateOf(state.spaceAuthMode) }
        var customToken by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSpaceAuthDialog = false },
            containerColor = ObsidianContainerHigh,
            title = { Text("Space Auth Preference") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpaceAuthMode.entries.forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { selectedMode = mode }
                        ) {
                            RadioButton(selected = selectedMode == mode, onClick = { selectedMode = mode })
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when (mode) {
                                    SpaceAuthMode.DEFAULT_ACCOUNT -> "Default Vault Account Token"
                                    SpaceAuthMode.ANONYMOUS -> "Anonymous (No Token)"
                                    SpaceAuthMode.CUSTOM_TOKEN -> "Custom Space-Only Token"
                                }
                            )
                        }
                    }
                    if (selectedMode == SpaceAuthMode.CUSTOM_TOKEN) {
                        OutlinedTextField(
                            value = customToken,
                            onValueChange = { customToken = it },
                            singleLine = true,
                            placeholder = { Text("hf_...") },
                            colors = oledTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showSpaceAuthDialog = false }) { Text("Cancel") } },
            confirmButton = {
                Button(
                    onClick = {
                        onSetSpaceAuth(manifest.identity.spaceId, selectedMode, customToken.takeIf { it.isNotBlank() })
                        showSpaceAuthDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SignalAmber, contentColor = Color.Black)
                ) { Text("Save") }
            }
        )
    }
}

@Composable
private fun LiveJobStatusCard(
    job: ActiveJobState,
    onOpenVault: () -> Unit,
    onCopyError: (String) -> Unit
) {
    val accent = when (job.status) {
        JobStatus.COMPLETED -> StatusEmerald
        JobStatus.FAILED -> StatusCrimson
        JobStatus.CANCELLED, JobStatus.DISCONNECTED -> StatusWarning
        JobStatus.STREAMING -> TelemetryCyan
        else -> SignalAmber
    }
    Surface(
        color = ObsidianContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().testTag("live_job_status_card")
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                    Text(
                        text = "${job.status.name} • ${job.endpointLabel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent
                    )
                }
                val elapsedText = String.format(Locale.US, "%.1fs", job.elapsedMs / 1000.0)
                val declaredSuffix = job.declaredZeroGpuSeconds?.let { " / ${it}s GPU req" } ?: ""
                Text("$elapsedText$declaredSuffix", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }

            Text(job.progressMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)

            if (job.queuePosition != null && job.status == JobStatus.QUEUED) {
                val etaStr = job.etaSeconds?.let { " • ETA ${String.format(Locale.US, "%.0fs", it)}" } ?: ""
                Text(
                    text = "Queue position #${job.queuePosition}${job.queueSize?.let { " of $it" } ?: ""}$etaStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = TelemetryCyan
                )
            }

            if (job.status in setOf(JobStatus.UPLOADING, JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.STREAMING)) {
                val prog = job.progressFraction
                if (prog != null && prog in 0f..1f) {
                    LinearProgressIndicator(
                        progress = { prog },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = accent,
                        trackColor = ObsidianContainerHigh
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = accent,
                        trackColor = ObsidianContainerHigh
                    )
                }
            }

            if (!job.errorMessage.isNullOrBlank()) {
                SelectionContainer {
                    Text(
                        text = job.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusCrimson
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { onCopyError(job.errorMessage) },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("copy_error_button")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp), tint = TelemetryCyan)
                        Spacer(Modifier.width(6.dp))
                        Text("Copy Error", color = TelemetryCyan, style = MaterialTheme.typography.labelMedium)
                    }
                    val errLower = job.errorMessage.lowercase()
                    if (errLower.contains("quota") ||
                        errLower.contains("authenticate") ||
                        errLower.contains("higher usage") ||
                        errLower.contains("oauth") ||
                        errLower.contains("vault") ||
                        errLower.contains("401") ||
                        errLower.contains("403")
                    ) {
                        OutlinedButton(
                            onClick = onOpenVault,
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("error_open_vault_button")
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(15.dp), tint = SignalAmber)
                            Spacer(Modifier.width(6.dp))
                            Text("Vault Auth", color = SignalAmber, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProvenanceChip(label: String, color: Color) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
