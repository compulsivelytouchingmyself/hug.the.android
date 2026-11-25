package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.JobRecordEntity
import com.example.model.AccountInfo
import com.example.model.SpaceAuthMode
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VaultScreen(
    accountInfo: AccountInfo?,
    hasDefaultToken: Boolean,
    isAnonymousMode: Boolean,
    isVerifyingToken: Boolean,
    tokenError: String?,
    perSpaceAuthModes: Map<String, SpaceAuthMode>,
    jobHistory: List<JobRecordEntity>,
    sdkVersionsLabel: String,
    onSaveAndVerifyToken: (String) -> Unit,
    onClearToken: () -> Unit,
    onSetAnonymousMode: (Boolean) -> Unit,
    onResetSpaceAuth: (String) -> Unit,
    onOpenSpace: (String) -> Unit,
    onDeleteJob: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var tokenInput by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(OledBlack),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Vault & Jobs", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Android Keystore AES-256-GCM encrypted Hugging Face credentials and persistent remote job history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        // Encrypted Hugging Face Token Vault
        item {
            Surface(
                color = ObsidianContainerLow,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = SignalAmber)
                        Text("Hugging Face Access Token", style = MaterialTheme.typography.titleMedium)
                    }

                    // Verified Account Badge
                    if (accountInfo != null) {
                        Surface(
                            color = ObsidianContainer,
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, StatusEmerald.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (accountInfo.avatarUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = accountInfo.avatarUrl,
                                        contentDescription = accountInfo.username,
                                        modifier = Modifier.size(44.dp).clip(CircleShape)
                                    )
                                } else {
                                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = StatusEmerald, modifier = Modifier.size(36.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("@${accountInfo.username}", style = MaterialTheme.typography.titleSmall, color = StatusEmerald)
                                        if (accountInfo.isPro) {
                                            Surface(color = ZeroGpuVioletContainer, shape = RoundedCornerShape(4.dp)) {
                                                Text(
                                                    "PRO",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = ZeroGpuViolet,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                    if (accountInfo.fullName.isNotBlank()) {
                                        Text(accountInfo.fullName, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                    }
                                    if (accountInfo.orgs.isNotEmpty()) {
                                        Text(
                                            "Orgs: ${accountInfo.orgs.joinToString(", ")}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Icon(Icons.Default.CheckCircle, contentDescription = "Verified", tint = StatusEmerald)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        singleLine = true,
                        label = {
                            Text(if (hasDefaultToken) "Replace stored token (hf_...)" else "Enter Hugging Face token (hf_...)")
                        },
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = SignalAmber) },
                        trailingIcon = {
                            IconButton(
                                onClick = { showToken = !showToken },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = stringResource(R.string.cd_toggle_visibility)
                                )
                            }
                        },
                        colors = oledTextFieldColors(),
                        modifier = Modifier.fillMaxWidth().testTag("hf_token_input")
                    )

                    if (!tokenError.isNullOrBlank()) {
                        Text(tokenError, style = MaterialTheme.typography.bodySmall, color = StatusCrimson)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                onSaveAndVerifyToken(tokenInput)
                                tokenInput = ""
                            },
                            enabled = tokenInput.isNotBlank() && !isVerifyingToken,
                            colors = ButtonDefaults.buttonColors(containerColor = SignalAmber, contentColor = Color.Black),
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("verify_token_button")
                        ) {
                            if (isVerifyingToken) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                            } else {
                                Text("Save & Verify (whoAmI)")
                            }
                        }
                        if (hasDefaultToken) {
                            OutlinedButton(
                                onClick = onClearToken,
                                modifier = Modifier.minimumInteractiveComponentSize().testTag("clear_token_button")
                            ) {
                                Text("Clear", color = StatusCrimson)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Anonymous Mode", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Send unauthenticated requests even while a token is stored.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                        Switch(
                            checked = isAnonymousMode,
                            onCheckedChange = onSetAnonymousMode,
                            colors = SwitchDefaults.colors(checkedTrackColor = SignalAmber, checkedThumbColor = Color.Black)
                        )
                    }

                    Text(
                        text = "Tokens are encrypted with Android Keystore AES-256-GCM, restricted strictly to huggingface.co and *.hf.space, never logged, and excluded from profile exports.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
        }

        // Per-Space Auth Overrides
        if (perSpaceAuthModes.isNotEmpty()) {
            item {
                Surface(
                    color = ObsidianContainerLow,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, HairlineBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("PER-SPACE AUTH OVERRIDES", style = MaterialTheme.typography.labelSmall, color = TelemetryCyan)
                        perSpaceAuthModes.forEach { (spaceId, mode) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(spaceId, style = MaterialTheme.typography.bodyMedium)
                                    Text(mode.name.replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = SignalAmber)
                                }
                                OutlinedButton(onClick = { onResetSpaceAuth(spaceId) }) {
                                    Text("Reset", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bundled Runtime Info
        item {
            Surface(
                color = ObsidianContainerLow,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("BUNDLED SDK ENGINE", style = MaterialTheme.typography.labelSmall, color = TelemetryCyan)
                    Text(sdkVersionsLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Priority 1: Bundled @gradio/client & @huggingface/hub • Priority 2: Native Gradio 3/4/5 REST/SSE • Priority 3: Native OkHttp CORS transport",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }

        // Persistent Job History
        item {
            Text("PERSISTENT JOB HISTORY (${jobHistory.size})", style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }

        if (jobHistory.isEmpty()) {
            item {
                Surface(
                    color = ObsidianContainerLow,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, HairlineBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No remote jobs recorded yet. Job metadata persists across restarts and interrupted runs are marked DISCONNECTED rather than auto-resubmitted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        items(jobHistory, key = { it.jobId }) { job ->
            JobHistoryCard(
                job = job,
                onOpenSpace = { onOpenSpace(job.spaceId) },
                onDelete = { onDeleteJob(job.jobId) }
            )
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun JobHistoryCard(
    job: JobRecordEntity,
    onOpenSpace: () -> Unit,
    onDelete: () -> Unit
) {
    val badgeColor = when (job.status) {
        "COMPLETED" -> StatusEmerald
        "FAILED" -> StatusCrimson
        "DISCONNECTED", "CANCELLED" -> StatusWarning
        else -> TelemetryCyan
    }
    val timeStr = remember(job.startedAtMs) {
        SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(job.startedAtMs))
    }

    Surface(
        color = ObsidianContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSpace)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(job.spaceId, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${job.endpointLabel} • $timeStr", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
                Surface(color = badgeColor.copy(alpha = 0.16f), shape = RoundedCornerShape(6.dp)) {
                    Text(
                        job.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete), tint = TextMuted, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                text = job.errorMessage?.take(140) ?: job.summaryText,
                style = MaterialTheme.typography.bodySmall,
                color = if (job.errorMessage != null) StatusCrimson else TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Elapsed: ${String.format(Locale.US, "%.1fs", job.elapsedMs / 1000.0)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TelemetryCyan
                )
                job.declaredZeroGpuSeconds?.let {
                    Text("GPU req: ${it}s", style = MaterialTheme.typography.labelSmall, color = ZeroGpuViolet)
                }
                if (job.transport.isNotBlank()) {
                    Text(job.transport, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
            }
        }
    }
}
