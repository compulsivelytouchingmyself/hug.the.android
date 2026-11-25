package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.ActiveJobState
import com.example.model.HardwareInfo
import com.example.model.RepoFileEntry
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianContainer
import com.example.ui.theme.ObsidianContainerHigh
import com.example.ui.theme.ObsidianContainerLow
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.SyntaxComment
import com.example.ui.theme.SyntaxDecorator
import com.example.ui.theme.SyntaxFunction
import com.example.ui.theme.SyntaxGradio
import com.example.ui.theme.SyntaxKeyword
import com.example.ui.theme.SyntaxNumber
import com.example.ui.theme.SyntaxString
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ZeroGpuViolet
import com.example.ui.theme.ZeroGpuVioletContainer
import java.util.Locale

private val PYTHON_KEYWORDS = setOf(
    "def", "class", "import", "from", "as", "return", "yield", "if", "elif", "else",
    "for", "while", "in", "not", "and", "or", "with", "try", "except", "finally",
    "raise", "async", "await", "lambda", "pass", "break", "continue", "True", "False", "None"
)

fun highlightPythonCode(source: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = source.lines().take(500)
        lines.forEachIndexed { idx, line ->
            // Line number gutter
            withStyle(SpanStyle(color = TextMuted)) {
                append(String.format(Locale.US, "%4d  ", idx + 1))
            }
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("#") -> {
                    withStyle(SpanStyle(color = SyntaxComment)) { append(line) }
                }
                trimmed.startsWith("@") -> {
                    withStyle(SpanStyle(color = SyntaxDecorator, fontWeight = FontWeight.SemiBold)) { append(line) }
                }
                else -> appendTokenizedLine(line)
            }
            if (idx < lines.lastIndex) append("\n")
        }
        if (source.lines().size > 500) {
            withStyle(SpanStyle(color = TextMuted)) {
                append("\n… (${source.lines().size - 500} additional lines truncated for smooth OLED scroll)")
            }
        }
    }
}

private fun AnnotatedString.Builder.appendTokenizedLine(line: String) {
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '#' -> {
                withStyle(SpanStyle(color = SyntaxComment)) { append(line.substring(i)) }
                return
            }
            c == '"' || c == '\'' -> {
                val quote = c
                var j = i + 1
                while (j < line.length && (line[j] != quote || line[j - 1] == '\\')) j++
                val end = (j + 1).coerceAtMost(line.length)
                withStyle(SpanStyle(color = SyntaxString)) { append(line.substring(i, end)) }
                i = end
            }
            c.isDigit() -> {
                var j = i + 1
                while (j < line.length && (line[j].isDigit() || line[j] == '.')) j++
                withStyle(SpanStyle(color = SyntaxNumber)) { append(line.substring(i, j)) }
                i = j
            }
            c.isLetter() || c == '_' -> {
                var j = i + 1
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_' || line[j] == '.')) j++
                val token = line.substring(i, j)
                val style = when {
                    token in PYTHON_KEYWORDS -> SpanStyle(color = SyntaxKeyword, fontWeight = FontWeight.SemiBold)
                    token.startsWith("gr.") -> SpanStyle(color = SyntaxGradio, fontWeight = FontWeight.Medium)
                    token.startsWith("spaces.") -> SpanStyle(color = SyntaxDecorator, fontWeight = FontWeight.SemiBold)
                    j < line.length && line[j] == '(' -> SpanStyle(color = SyntaxFunction)
                    else -> SpanStyle(color = TextPrimary)
                }
                withStyle(style) { append(token) }
                i = j
            }
            else -> {
                withStyle(SpanStyle(color = TextPrimary)) { append(c) }
                i++
            }
        }
    }
}

@Composable
fun SourceAndGpuInspector(
    hardware: HardwareInfo,
    activeJob: ActiveJobState,
    repoFiles: List<RepoFileEntry>,
    selectedFile: String,
    sourceContent: String,
    isLoadingFile: Boolean,
    onSelectFile: (String) -> Unit,
    onCopied: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val relevantFiles = remember(repoFiles) {
        repoFiles.filter { it.type != "directory" }
            .sortedWith(
                compareBy<RepoFileEntry>(
                    { !it.path.endsWith(".py") },
                    { it.path != "README.md" && it.path != "requirements.txt" },
                    { it.path }
                )
            )
            .take(40)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ZeroGPU & Hardware Allocation Card
        Surface(
            color = if (hardware.isZeroGpu) ZeroGpuVioletContainer.copy(alpha = 0.55f) else ObsidianContainerLow,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, if (hardware.isZeroGpu) ZeroGpuViolet.copy(alpha = 0.6f) else HairlineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = if (hardware.isZeroGpu) Icons.Default.Bolt else Icons.Default.Memory,
                        contentDescription = null,
                        tint = if (hardware.isZeroGpu) ZeroGpuViolet else TelemetryCyan
                    )
                    Text(
                        text = if (hardware.isZeroGpu) "ZeroGPU & Decorator Telemetry" else "Space Hardware Profile",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MetricPill("Current", hardware.current)
                    MetricPill("Requested", hardware.requested)
                    if (activeJob.elapsedMs > 0L) {
                        MetricPill("Actual Run", String.format(Locale.US, "%.1fs", activeJob.elapsedMs / 1000.0))
                    }
                }

                if (hardware.decorators.isNotEmpty()) {
                    Text(
                        text = "Detected @spaces.GPU Decorators (${hardware.decorators.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = SignalAmber
                    )
                    hardware.decorators.forEach { dec ->
                        Surface(
                            color = ObsidianContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "def ${dec.functionName}()",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SyntaxFunction
                                    )
                                    Text(
                                        text = "Line ${dec.lineNumber}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                }
                                Text(
                                    text = dec.rawDecorator,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                    color = SyntaxDecorator
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val durLabel = when {
                                        dec.isDynamicDuration -> "Dynamic (${dec.dynamicDurationExpression ?: "callable"})"
                                        dec.declaredDurationSeconds != null -> "Requested: ${dec.declaredDurationSeconds}s"
                                        else -> "Requested: unlimited / default"
                                    }
                                    Text(durLabel, style = MaterialTheme.typography.labelSmall, color = TelemetryCyan)
                                    if (!dec.allocationSize.isNullOrBlank()) {
                                        Text("Size: ${dec.allocationSize}", style = MaterialTheme.typography.labelSmall, color = ZeroGpuViolet)
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        text = "Note: Requested @spaces.GPU duration is an upper allocation ceiling, distinct from actual execution time and your Hugging Face account's remaining ZeroGPU quota.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                } else if (hardware.isZeroGpu) {
                    Text(
                        text = "ZeroGPU hardware detected. Inspect additional Python files below if @spaces.GPU decorators are defined in a submodule.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                if (!activeJob.quotaMessage.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "Quota signal: ${activeJob.quotaMessage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }

        // Repository file selector
        if (relevantFiles.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(relevantFiles, key = { it.path }) { entry ->
                    val selected = entry.path == selectedFile
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectFile(entry.path) },
                        label = {
                            Text(
                                text = entry.path,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(14.dp))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SignalAmber,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }
        }

        // Syntax-Highlighted Code Viewer
        Surface(
            color = ObsidianContainer,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, HairlineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "$selectedFile (Read-Only Static Inspection)",
                        style = MaterialTheme.typography.labelMedium,
                        color = TelemetryCyan
                    )
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(sourceContent))
                            onCopied("Copied $selectedFile")
                        },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.cd_copy), tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
                if (isLoadingFile) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = SignalAmber)
                    }
                } else {
                    val highlighted = remember(sourceContent) {
                        if (selectedFile.endsWith(".py")) highlightPythonCode(sourceContent)
                        else AnnotatedString(sourceContent.take(25_000))
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            text = highlighted,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.5.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
