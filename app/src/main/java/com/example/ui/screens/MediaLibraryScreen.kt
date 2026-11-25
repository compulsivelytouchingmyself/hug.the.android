package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.MediaAssetEntity
import com.example.ui.components.InlineAudioPlayer
import com.example.ui.components.InlineVideoPlayer
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.ObsidianContainer
import com.example.ui.theme.ObsidianContainerHigh
import com.example.ui.theme.ObsidianContainerLow
import com.example.ui.theme.OledBlack
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.SignalAmberContainer
import com.example.ui.theme.StatusCrimson
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MediaLibraryScreen(
    assets: List<MediaAssetEntity>,
    onDownload: (MediaAssetEntity) -> Unit,
    onShare: (MediaAssetEntity) -> Intent?,
    onSendToSpace: (MediaAssetEntity) -> Unit,
    onDelete: (MediaAssetEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val filters = listOf("all" to "All", "image" to "Images", "audio" to "Audio", "video" to "Video", "file" to "Files")
    var selectedFilter by remember { mutableStateOf("all") }

    val visibleAssets = remember(assets, selectedFilter) {
        if (selectedFilter == "all") assets else assets.filter { it.mediaType == selectedFilter }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(OledBlack),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Shared Media Library", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Generated outputs and imported inputs cached locally so expiring Space URLs never interrupt cross-Space pipelines.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filters) { (key, label) ->
                    FilterChip(
                        selected = selectedFilter == key,
                        onClick = { selectedFilter = key },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SignalAmber,
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }
        }

        if (visibleAssets.isEmpty()) {
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
                        Text("No Cached Media Yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Run any image, audio, or video Space — or share media into SpaceDeck from Android — to cache and reuse it across Spaces.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        items(visibleAssets, key = { it.id }) { asset ->
            MediaAssetCard(
                asset = asset,
                onDownload = { onDownload(asset) },
                onShare = {
                    onShare(asset)?.let { intent ->
                        context.startActivity(Intent.createChooser(intent, "Share media"))
                    }
                },
                onSendToSpace = { onSendToSpace(asset) },
                onDelete = { onDelete(asset) }
            )
        }
    }
}

@Composable
private fun MediaAssetCard(
    asset: MediaAssetEntity,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onSendToSpace: () -> Unit,
    onDelete: () -> Unit
) {
    var expandPlayer by remember { mutableStateOf(false) }
    val timeLabel = remember(asset.createdAtMs) {
        SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(asset.createdAtMs))
    }

    Surface(
        color = ObsidianContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier.fillMaxWidth().testTag("media_asset_${asset.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(asset.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = "${asset.spaceId} • ${asset.sizeBytes / 1024} KB • $timeLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete), tint = StatusCrimson, modifier = Modifier.size(18.dp))
                }
            }

            when (asset.mediaType) {
                "image" -> {
                    AsyncImage(
                        model = File(asset.localFilePath),
                        contentDescription = asset.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clip(MaterialTheme.shapes.small)
                    )
                }
                "audio" -> {
                    InlineAudioPlayer(uri = Uri.fromFile(File(asset.localFilePath)), title = asset.fileName)
                }
                "video" -> {
                    if (expandPlayer) {
                        InlineVideoPlayer(uri = Uri.fromFile(File(asset.localFilePath)))
                    } else {
                        Surface(
                            color = ObsidianContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = SignalAmber)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Cached Video", style = MaterialTheme.typography.bodyMedium)
                                }
                                AssistChip(
                                    onClick = { expandPlayer = true },
                                    label = { Text("Play Video") }
                                )
                            }
                        }
                    }
                }
                else -> {
                    Surface(color = ObsidianContainer, shape = RoundedCornerShape(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, tint = TelemetryCyan)
                            Spacer(Modifier.width(8.dp))
                            Text(asset.mimeType, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onDownload,
                    label = { Text("Save") },
                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = stringResource(R.string.cd_download), modifier = Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = ObsidianContainerHigh)
                )
                AssistChip(
                    onClick = onShare,
                    label = { Text("Share") },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.cd_share), modifier = Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = ObsidianContainerHigh)
                )
                AssistChip(
                    onClick = onSendToSpace,
                    label = { Text("Send to Space") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.cd_send_to_space), tint = SignalAmber, modifier = Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = SignalAmberContainer, labelColor = SignalAmber)
                )
            }
        }
    }
}
