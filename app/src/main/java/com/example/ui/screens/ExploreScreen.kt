package com.example.ui.screens

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.SpaceRecordEntity
import com.example.model.RuntimeStage
import com.example.model.SpaceSummary
import com.example.ui.ExploreTab
import com.example.ui.ExploreUiState
import com.example.ui.components.oledTextFieldColors
import com.example.ui.theme.HairlineBorder
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

@Composable
fun ExploreScreen(
    state: ExploreUiState,
    testPresets: List<SpaceSummary>,
    favorites: List<SpaceRecordEntity>,
    recents: List<SpaceRecordEntity>,
    onQuickUrlChange: (String) -> Unit,
    onOpenSpace: (String) -> Unit,
    onSelectTab: (ExploreTab) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSelectHardwareFilter: (String?) -> Unit,
    onToggleFavorite: (SpaceSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    val favoriteIds = remember(favorites) { favorites.map { it.spaceId }.toSet() }
    val categories = listOf("Video", "Audio", "Image", "LLM", "Speech", "3D")
    val hwFilters = listOf("zero" to "ZeroGPU", "gpu" to "GPU", "cpu" to "CPU")

    val displayedSpaces: List<SpaceSummary> = when (state.selectedTab) {
        ExploreTab.FAVORITES -> favorites.map { it.toSummary() }
        ExploreTab.RECENT -> recents.map { it.toSummary() }
        else -> state.spaces
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Brand Header + Quick Open URL/ID Deck
        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, HairlineBorder),
                color = ObsidianContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    ObsidianContainerLow,
                                    SignalAmber.copy(alpha = 0.08f),
                                    TelemetryCyan.copy(alpha = 0.06f)
                                )
                            )
                        )
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "SpaceDeck",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Universal Hugging Face Spaces Remote",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Surface(
                                color = ZeroGpuVioletContainer,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, ZeroGpuViolet.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "GRADIO",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ZeroGpuViolet,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = state.quickUrlInput,
                                onValueChange = onQuickUrlChange,
                                singleLine = true,
                                placeholder = {
                                    Text(
                                        "author/repo or *.hf.space URL",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextMuted
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Link, contentDescription = null, tint = SignalAmber)
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { onOpenSpace(state.quickUrlInput) }),
                                colors = oledTextFieldColors(),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("quick_space_url_input")
                            )
                            Button(
                                onClick = { onOpenSpace(state.quickUrlInput) },
                                enabled = state.quickUrlInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SignalAmber,
                                    contentColor = Color.Black
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("quick_open_space_button")
                            ) {
                                Icon(Icons.Default.Launch, contentDescription = "Open Space", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        // Featured Initial Test Presets
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "TEST PRESETS",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    testPresets.forEach { preset ->
                        Surface(
                            color = ObsidianContainerLow,
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, SignalAmber.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onOpenSpace(preset.spaceId) }
                                .testTag("preset_${preset.spaceId}")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(preset.emoji, style = MaterialTheme.typography.titleMedium)
                                    Surface(color = ZeroGpuVioletContainer, shape = RoundedCornerShape(6.dp)) {
                                        Text(
                                            text = "ZeroGPU",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = ZeroGpuViolet,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = preset.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = preset.spaceId,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SignalAmber,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // Tabs: Trending / My Spaces / Favorites / Recent
        item {
            TabRow(
                selectedTabIndex = ExploreTab.entries.indexOf(state.selectedTab),
                containerColor = ObsidianContainerLow,
                contentColor = SignalAmber,
                indicator = { positions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(positions[ExploreTab.entries.indexOf(state.selectedTab)]),
                        color = SignalAmber
                    )
                }
            ) {
                ExploreTab.entries.forEach { tab ->
                    Tab(
                        selected = state.selectedTab == tab,
                        onClick = { onSelectTab(tab) },
                        text = {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.testTag("explore_tab_${tab.name.lowercase()}")
                    )
                }
            }
        }

        // Search & Filter Controls (for Trending / My Spaces)
        if (state.selectedTab == ExploreTab.TRENDING || state.selectedTab == ExploreTab.MY_SPACES) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        placeholder = { Text("Search Hugging Face Gradio Spaces…", color = TextMuted) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                        trailingIcon = {
                            IconButton(
                                onClick = onSearchSubmit,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_refresh), tint = SignalAmber)
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                        colors = oledTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("explore_search_input")
                    )

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(hwFilters) { (key, label) ->
                            val selected = state.hardwareFilter == key
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectHardwareFilter(key) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                leadingIcon = if (key == "zero") {
                                    { Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ZeroGpuViolet,
                                    selectedLabelColor = Color.Black,
                                    selectedLeadingIconColor = Color.Black
                                )
                            )
                        }
                        items(categories) { cat ->
                            val selected = state.selectedCategory == cat
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectCategory(cat) },
                                label = { Text(cat, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TelemetryCyan,
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }
                }
            }
        }

        // Loading / Error / Empty states
        if (state.isLoading) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = SignalAmber)
                }
            }
        } else if (state.error != null) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else if (displayedSpaces.isEmpty()) {
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
                        Text(
                            text = when (state.selectedTab) {
                                ExploreTab.FAVORITES -> "No favorite Spaces yet"
                                ExploreTab.RECENT -> "No recent Spaces yet"
                                else -> "No matching Gradio Spaces found"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Tap a Test Preset above or paste any Hugging Face Space URL to inspect its endpoints.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        // Space cards
        items(displayedSpaces, key = { it.spaceId }) { space ->
            SpaceCard(
                space = space,
                isFavorite = space.spaceId in favoriteIds,
                onClick = { onOpenSpace(space.spaceId) },
                onToggleFavorite = { onToggleFavorite(space) }
            )
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SpaceCard(
    space: SpaceSummary,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Surface(
        color = ObsidianContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("space_card_${space.spaceId}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (space.emoji.isNotBlank()) {
                            Text(space.emoji, style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            text = space.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = space.spaceId,
                        style = MaterialTheme.typography.labelSmall,
                        color = TelemetryCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = stringResource(R.string.cd_favorite),
                        tint = if (isFavorite) SignalAmber else TextMuted
                    )
                }
            }

            if (space.shortDescription.isNotBlank()) {
                Text(
                    text = space.shortDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RuntimeStagePill(space.runtimeStage)
                    Surface(
                        color = if (space.isZeroGpu) ZeroGpuVioletContainer else ObsidianContainerHigh,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (space.isZeroGpu) "ZeroGPU" else space.hardware,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (space.isZeroGpu) ZeroGpuViolet else TextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (space.likes > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = SignalAmber, modifier = Modifier.size(14.dp))
                        Text(space.likes.toString(), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
fun RuntimeStagePill(stage: RuntimeStage) {
    val (bg, fg) = when (stage) {
        RuntimeStage.RUNNING, RuntimeStage.RUNNING_BUILDING -> StatusEmerald.copy(alpha = 0.16f) to StatusEmerald
        RuntimeStage.BUILDING, RuntimeStage.APP_STARTING -> TelemetryCyan.copy(alpha = 0.16f) to TelemetryCyan
        RuntimeStage.SLEEPING, RuntimeStage.PAUSED -> StatusWarning.copy(alpha = 0.18f) to StatusWarning
        RuntimeStage.RUNTIME_ERROR, RuntimeStage.BUILD_ERROR, RuntimeStage.CONFIG_ERROR -> StatusCrimson.copy(alpha = 0.18f) to StatusCrimson
        else -> ObsidianContainerHigh to TextMuted
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = stage.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun SpaceRecordEntity.toSummary() = SpaceSummary(
    spaceId = spaceId,
    author = author,
    title = title,
    emoji = emoji,
    shortDescription = shortDescription,
    hardware = hardware,
    isZeroGpu = isZeroGpu,
    runtimeStage = RuntimeStage.fromRaw(runtimeStage),
    isFavorite = isFavorite
)
