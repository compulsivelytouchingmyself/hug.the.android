package com.example.ui.components

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.R
import com.example.model.ComponentKind
import com.example.model.OutputItem
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianContainer
import com.example.ui.theme.ObsidianContainerHigh
import com.example.ui.theme.ObsidianContainerLow
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.SignalAmberContainer
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

@Composable
fun OutputItemCard(
    item: OutputItem,
    onDownload: (OutputItem) -> Unit,
    onShare: (OutputItem) -> Unit,
    onSendToSpace: (OutputItem) -> Unit,
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var zoomImageModel by remember { mutableStateOf<Any?>(null) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("output_card_${item.index}"),
        color = ObsidianContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, HairlineBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(color = ObsidianContainerHigh, shape = RoundedCornerShape(6.dp)) {
                    Text(
                        text = item.kind.name.lowercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = SignalAmber,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            when (item.kind) {
                ComponentKind.IMAGE -> {
                    val model: Any? = item.localCachedPath?.let { File(it) } ?: item.mediaUrl
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(Color.Black)
                            .clickable { zoomImageModel = model }
                    ) {
                        AsyncImage(
                            model = model,
                            contentDescription = item.label,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 140.dp, max = 380.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = "Zoom image",
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                                .padding(6.dp)
                                .size(18.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    MediaActionRow(
                        canShareOrDownload = item.localCachedPath != null,
                        onDownload = { onDownload(item) },
                        onShare = { onShare(item) },
                        onSendToSpace = { onSendToSpace(item) }
                    )
                }

                ComponentKind.GALLERY -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item.galleryItems.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                pair.forEach { gItem ->
                                    val model: Any? = gItem.localCachedPath?.let { File(it) } ?: gItem.mediaUrl
                                    Column(modifier = Modifier.weight(1f)) {
                                        AsyncImage(
                                            model = model,
                                            contentDescription = gItem.label,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1f)
                                                .clip(MaterialTheme.shapes.small)
                                                .clickable { zoomImageModel = model }
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            if (gItem.localCachedPath != null) {
                                                IconButton(onClick = { onDownload(gItem) }) {
                                                    Icon(Icons.Default.Download, contentDescription = stringResource(R.string.cd_download), modifier = Modifier.size(18.dp))
                                                }
                                                IconButton(onClick = { onSendToSpace(gItem) }) {
                                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.cd_send_to_space), tint = SignalAmber, modifier = Modifier.size(18.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                ComponentKind.AUDIO -> {
                    val uri = item.localCachedPath?.let { Uri.fromFile(File(it)) } ?: item.mediaUrl?.let { Uri.parse(it) }
                    if (uri != null) {
                        InlineAudioPlayer(uri = uri, title = item.fileName ?: item.label)
                    }
                    Spacer(Modifier.height(10.dp))
                    MediaActionRow(
                        canShareOrDownload = item.localCachedPath != null,
                        onDownload = { onDownload(item) },
                        onShare = { onShare(item) },
                        onSendToSpace = { onSendToSpace(item) }
                    )
                }

                ComponentKind.VIDEO -> {
                    val uri = item.localCachedPath?.let { Uri.fromFile(File(it)) } ?: item.mediaUrl?.let { Uri.parse(it) }
                    if (uri != null) {
                        InlineVideoPlayer(uri = uri)
                    }
                    Spacer(Modifier.height(10.dp))
                    MediaActionRow(
                        canShareOrDownload = item.localCachedPath != null,
                        onDownload = { onDownload(item) },
                        onShare = { onShare(item) },
                        onSendToSpace = { onSendToSpace(item) }
                    )
                }

                ComponentKind.CHATBOT -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.chatMessages.forEach { (role, text) ->
                            val isUser = role.equals("user", true)
                            Surface(
                                color = if (isUser) SignalAmberContainer else ObsidianContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = role.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isUser) SignalAmber else TelemetryCyan
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(text = text, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }

                ComponentKind.DATAFRAME -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .clip(MaterialTheme.shapes.small)
                            .background(ObsidianContainer)
                            .padding(8.dp)
                    ) {
                        if (item.tableHeaders.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                item.tableHeaders.forEach { h ->
                                    Text(
                                        text = h,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = SignalAmber,
                                        modifier = Modifier.width(120.dp)
                                    )
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = HairlineBorder)
                        }
                        item.tableRows.take(50).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                                row.forEach { cell ->
                                    Text(
                                        text = cell,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.width(120.dp),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                ComponentKind.FILE -> {
                    Surface(color = ObsidianContainer, shape = MaterialTheme.shapes.small) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, tint = TelemetryCyan)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.fileName ?: "Output file", style = MaterialTheme.typography.bodyMedium)
                                Text(item.mimeType ?: "application/octet-stream", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    MediaActionRow(
                        canShareOrDownload = item.localCachedPath != null,
                        onDownload = { onDownload(item) },
                        onShare = { onShare(item) },
                        onSendToSpace = { onSendToSpace(item) }
                    )
                }

                else -> {
                    val content = item.textValue ?: item.rawJson
                    val isCode = item.kind == ComponentKind.JSON || item.kind == ComponentKind.CODE
                    Surface(
                        color = ObsidianContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = content,
                            style = if (isCode) {
                                MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily)
                            } else {
                                MaterialTheme.typography.bodyLarge
                            },
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        AssistChip(
                            onClick = {
                                clipboard.setText(AnnotatedString(content))
                                onCopyText("Copied output to clipboard")
                            },
                            label = { Text("Copy") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.cd_copy), modifier = Modifier.size(16.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = ObsidianContainerHigh)
                        )
                    }
                }
            }
        }
    }

    if (zoomImageModel != null) {
        Dialog(
            onDismissRequest = { zoomImageModel = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.96f))
                    .clickable { zoomImageModel = null }
            ) {
                AsyncImage(
                    model = zoomImageModel,
                    contentDescription = item.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(16.dp)
                )
                IconButton(
                    onClick = { zoomImageModel = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(20.dp)
                        .background(ObsidianContainerHigh, CircleShape)
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close preview", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun MediaActionRow(
    canShareOrDownload: Boolean,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onSendToSpace: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AssistChip(
            onClick = onDownload,
            enabled = canShareOrDownload,
            label = { Text("Save") },
            leadingIcon = { Icon(Icons.Default.Download, contentDescription = stringResource(R.string.cd_download), modifier = Modifier.size(16.dp)) },
            colors = AssistChipDefaults.assistChipColors(containerColor = ObsidianContainerHigh)
        )
        AssistChip(
            onClick = onShare,
            enabled = canShareOrDownload,
            label = { Text("Share") },
            leadingIcon = { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.cd_share), modifier = Modifier.size(16.dp)) },
            colors = AssistChipDefaults.assistChipColors(containerColor = ObsidianContainerHigh)
        )
        AssistChip(
            onClick = onSendToSpace,
            enabled = canShareOrDownload,
            label = { Text("Send to Space") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.cd_send_to_space), tint = SignalAmber, modifier = Modifier.size(16.dp)) },
            colors = AssistChipDefaults.assistChipColors(containerColor = SignalAmberContainer, labelColor = SignalAmber)
        )
    }
}

@Composable
fun InlineAudioPlayer(uri: Uri, title: String) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    player.seekTo(0)
                    player.pause()
                }
                durationMs = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(1L)
            delay(250)
        }
    }

    Surface(
        color = ObsidianContainer,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, TelemetryCyan.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { if (player.isPlaying) player.pause() else player.play() },
                modifier = Modifier
                    .background(SignalAmber, CircleShape)
                    .size(44.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.cd_play_pause),
                    tint = Color.Black
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = TelemetryCyan,
                    trackColor = ObsidianContainerHigh
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${formatMillis(positionMs)} / ${formatMillis(durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun InlineVideoPlayer(uri: Uri) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(MaterialTheme.shapes.small)
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun formatMillis(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60
    val secs = totalSec % 60
    return String.format(Locale.US, "%d:%02d", mins, secs)
}
