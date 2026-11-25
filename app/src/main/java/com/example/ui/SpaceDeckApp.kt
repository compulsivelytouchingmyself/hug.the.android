package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.model.SpaceAuthMode
import com.example.ui.components.oledTextFieldColors
import com.example.ui.screens.ExploreScreen
import com.example.ui.screens.MediaLibraryScreen
import com.example.ui.screens.VaultScreen
import com.example.ui.screens.WorkbenchScreen
import com.example.ui.theme.ObsidianContainer
import com.example.ui.theme.ObsidianContainerHigh
import com.example.ui.theme.ObsidianContainerLow
import com.example.ui.theme.OledBlack
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.SignalAmberContainer
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

private data class NavDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val TopLevelDestinations = listOf(
    NavDestination("explore", R.string.nav_explore, Icons.Filled.Explore, Icons.Outlined.Explore),
    NavDestination("workbench", R.string.nav_workbench, Icons.Filled.Tune, Icons.Outlined.Tune),
    NavDestination("media", R.string.nav_media, Icons.Filled.Collections, Icons.Outlined.Collections),
    NavDestination("vault", R.string.nav_vault, Icons.Filled.Security, Icons.Outlined.Security)
)

@Composable
fun SpaceDeckApp(viewModel: SpaceDeckViewModel) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val selectedDest by viewModel.selectedDestination.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val exploreState by viewModel.exploreState.collectAsStateWithLifecycle()
    val workbenchState by viewModel.workbenchState.collectAsStateWithLifecycle()
    val activeJob by viewModel.activeJob.collectAsStateWithLifecycle()
    val outputs by viewModel.outputs.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val recents by viewModel.recents.collectAsStateWithLifecycle()
    val mediaLibrary by viewModel.mediaLibrary.collectAsStateWithLifecycle()
    val jobHistory by viewModel.jobHistory.collectAsStateWithLifecycle()
    val accountInfo by viewModel.accountInfo.collectAsStateWithLifecycle()
    val isAnonymousMode by viewModel.isAnonymousMode.collectAsStateWithLifecycle()
    val hasDefaultToken by viewModel.hasDefaultToken.collectAsStateWithLifecycle()
    val isVerifyingToken by viewModel.isVerifyingToken.collectAsStateWithLifecycle()
    val tokenError by viewModel.tokenError.collectAsStateWithLifecycle()
    val perSpaceAuthModes by viewModel.perSpaceAuthModes.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val pendingTransfer by viewModel.pendingMediaTransfer.collectAsStateWithLifecycle()

    // Request POST_NOTIFICATIONS gracefully on Android 13+ for background job alerts
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSnackbar()
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "explore"

    LaunchedEffect(selectedDest) {
        if (selectedDest != currentRoute) {
            navController.navigate(selectedDest) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        val useRail = maxWidth >= 600.dp

        val navigateAction: (String) -> Unit = { route ->
            viewModel.navigateTo(route)
            navController.navigate(route) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }

        val contentHost: @Composable (Modifier) -> Unit = { contentMod ->
            Box(
                modifier = contentMod.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                NavHost(
                    navController = navController,
                    startDestination = "explore",
                    modifier = Modifier.fillMaxSize().widthIn(max = 840.dp)
                ) {
                    composable("explore") {
                        ExploreScreen(
                            state = exploreState,
                            testPresets = viewModel.initialTestPresets,
                            favorites = favorites,
                            recents = recents,
                            onQuickUrlChange = viewModel::updateQuickUrl,
                            onOpenSpace = { id -> viewModel.openSpace(id) },
                            onSelectTab = viewModel::selectExploreTab,
                            onSearchQueryChange = viewModel::updateSearchQuery,
                            onSearchSubmit = viewModel::refreshSpaces,
                            onSelectCategory = viewModel::selectCategory,
                            onSelectHardwareFilter = viewModel::selectHardwareFilter,
                            onToggleFavorite = viewModel::toggleFavoriteBySummary
                        )
                    }
                    composable("workbench") {
                        WorkbenchScreen(
                            state = workbenchState,
                            activeJob = activeJob,
                            outputs = outputs,
                            mediaLibrary = mediaLibrary,
                            diagnostics = diagnostics,
                            testPresets = viewModel.initialTestPresets,
                            onOpenSpace = viewModel::openSpace,
                            onSelectSubTab = viewModel::selectWorkbenchSubTab,
                            onSelectEndpoint = viewModel::selectEndpoint,
                            onUpdateFieldValue = viewModel::updateFieldValue,
                            onToggleAdvanced = viewModel::toggleAdvancedSection,
                            onRun = viewModel::runCurrentEndpoint,
                            onCancel = viewModel::cancelActiveJob,
                            onToggleFavorite = viewModel::toggleCurrentSpaceFavorite,
                            onPinShortcut = viewModel::pinCurrentSpaceShortcut,
                            onLoadSourceFile = viewModel::loadSourceFile,
                            onCreateCameraTarget = viewModel::createCameraCaptureTarget,
                            onAttachUri = viewModel::attachUriToField,
                            onAttachMediaAsset = viewModel::attachMediaAssetToField,
                            onToggleAudioRecord = viewModel::toggleAudioRecordingForField,
                            onDownloadOutput = { out ->
                                out.localCachedPath?.let { path ->
                                    viewModel.downloadMediaToDevice(path, out.fileName ?: "output", out.mimeType ?: "application/octet-stream")
                                }
                            },
                            onShareOutput = { out ->
                                out.localCachedPath?.let { path ->
                                    viewModel.buildShareIntent(path, out.mimeType ?: "*/*")
                                }
                            },
                            onSendOutputToSpace = viewModel::startSendOutputToSpace,
                            onUpdateFieldCustomization = viewModel::updateFieldCustomization,
                            onReorderField = viewModel::reorderField,
                            onToggleFavoriteEndpoint = viewModel::toggleFavoriteEndpoint,
                            onExportProfileJson = viewModel::exportCurrentProfileJson,
                            onImportProfileJson = viewModel::importProfileJson,
                            onSavePreset = viewModel::saveCurrentAsPreset,
                            onApplyPreset = viewModel::applyPreset,
                            onDeletePreset = viewModel::deletePreset,
                            onApplyManualManifest = viewModel::applyManualManifestOverride,
                            onResetManualOverride = viewModel::resetManualOverride,
                            onSetSpaceAuth = viewModel::setSpaceAuthPreference,
                            onShowMessage = viewModel::showMessage
                        )
                    }
                    composable("media") {
                        MediaLibraryScreen(
                            assets = mediaLibrary,
                            onDownload = { asset ->
                                viewModel.downloadMediaToDevice(asset.localFilePath, asset.fileName, asset.mimeType)
                            },
                            onShare = { asset ->
                                viewModel.buildShareIntent(asset.localFilePath, asset.mimeType)
                            },
                            onSendToSpace = viewModel::startSendMediaToSpace,
                            onDelete = viewModel::deleteMediaAsset
                        )
                    }
                    composable("vault") {
                        VaultScreen(
                            accountInfo = accountInfo,
                            hasDefaultToken = hasDefaultToken,
                            isAnonymousMode = isAnonymousMode,
                            isVerifyingToken = isVerifyingToken,
                            tokenError = tokenError,
                            perSpaceAuthModes = perSpaceAuthModes,
                            jobHistory = jobHistory,
                            sdkVersionsLabel = viewModel.sdkVersionsLabel,
                            onSaveAndVerifyToken = viewModel::saveAndVerifyToken,
                            onClearToken = viewModel::clearDefaultToken,
                            onSetAnonymousMode = viewModel::setAnonymousMode,
                            onResetSpaceAuth = { id -> viewModel.setSpaceAuthPreference(id, SpaceAuthMode.DEFAULT_ACCOUNT, null) },
                            onOpenSpace = { id -> viewModel.openSpace(id) },
                            onDeleteJob = viewModel::deleteJobRecord
                        )
                    }
                }
            }
        }

        if (useRail) {
            Scaffold(
                containerColor = OledBlack,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                contentWindowInsets = WindowInsets.safeDrawing
            ) { innerPadding ->
                Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    NavigationRail(
                        containerColor = ObsidianContainerLow,
                        contentColor = SignalAmber
                    ) {
                        Spacer(Modifier.height(12.dp))
                        TopLevelDestinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateAction(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes), style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = SignalAmber,
                                    selectedTextColor = SignalAmber,
                                    indicatorColor = SignalAmberContainer,
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextMuted
                                ),
                                modifier = Modifier.testTag("nav_${dest.route}")
                            )
                        }
                    }
                    contentHost(Modifier.weight(1f))
                }
            }
        } else {
            Scaffold(
                containerColor = OledBlack,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    NavigationBar(
                        containerColor = ObsidianContainerLow,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        TopLevelDestinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateAction(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes), style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = SignalAmber,
                                    selectedTextColor = SignalAmber,
                                    indicatorColor = SignalAmberContainer,
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextMuted
                                ),
                                modifier = Modifier.testTag("nav_${dest.route}")
                            )
                        }
                    }
                }
            ) { innerPadding ->
                contentHost(Modifier.padding(innerPadding))
            }
        }
    }

    // ─── Cross-Space "Send Media to Space" Modal ──────────────────────────────────────
    pendingTransfer?.let { transfer ->
        var customTarget by remember { mutableStateOf("") }
        val candidates = remember(favorites, recents) {
            (viewModel.initialTestPresets.map { it.spaceId to it.title } +
                favorites.map { it.spaceId to it.title } +
                recents.map { it.spaceId to it.title })
                .distinctBy { it.first }
        }
        AlertDialog(
            onDismissRequest = viewModel::cancelSendMediaToSpace,
            containerColor = ObsidianContainerHigh,
            title = { Text("Send Media to Space") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Routing ${transfer.asset.fileName} (${transfer.asset.mediaType}) into a compatible input slot:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = customTarget,
                        onValueChange = { customTarget = it },
                        singleLine = true,
                        placeholder = { Text("Or enter author/repo…") },
                        colors = oledTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.height(200.dp)) {
                        items(candidates, key = { it.first }) { (spaceId, title) ->
                            Surface(
                                color = ObsidianContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.openSpace(spaceId)
                                }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(title, style = MaterialTheme.typography.bodyMedium)
                                    Text(spaceId, style = MaterialTheme.typography.labelSmall, color = TelemetryCyan)
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelSendMediaToSpace) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = { if (customTarget.isNotBlank()) viewModel.openSpace(customTarget) },
                    enabled = customTarget.isNotBlank()
                ) { Text("Open Target", color = SignalAmber) }
            }
        )
    }
}
