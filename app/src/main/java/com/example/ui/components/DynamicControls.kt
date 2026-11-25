package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.MediaAssetEntity
import com.example.model.ComponentField
import com.example.model.ComponentKind
import com.example.model.FieldValue
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianContainer
import com.example.ui.theme.ObsidianContainerHigh
import com.example.ui.theme.ObsidianContainerLow
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun DynamicFieldCard(
    field: ComponentField,
    value: FieldValue,
    isLocked: Boolean,
    isRecordingAudio: Boolean,
    mediaLibrary: List<MediaAssetEntity>,
    onValueChange: (FieldValue) -> Unit,
    onCreateCameraTarget: () -> Pair<Uri, File>,
    onAttachUri: (Uri, String) -> Unit,
    onToggleAudioRecord: () -> Unit,
    onOpenLibraryPicker: (ComponentKind) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("field_card_${field.parameterName}"),
        color = ObsidianContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (isLocked) SignalAmber.copy(alpha = 0.45f) else HairlineBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = field.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (field.required) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "*",
                                style = MaterialTheme.typography.titleMedium,
                                color = SignalAmber
                            )
                        }
                    }
                    if (field.description.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = field.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isLocked) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked parameter",
                            tint = SignalAmber,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Surface(
                        color = ObsidianContainerHigh,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = field.gradioType.lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TelemetryCyan,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            when (field.kind) {
                ComponentKind.TEXTBOX, ComponentKind.MARKDOWN, ComponentKind.HTML -> TextboxControl(
                    field = field,
                    value = (value as? FieldValue.Text)?.value.orEmpty(),
                    enabled = !isLocked,
                    isMono = false,
                    onChange = { onValueChange(FieldValue.Text(it)) }
                )
                ComponentKind.CODE -> TextboxControl(
                    field = field.copy(lines = field.lines.coerceAtLeast(4)),
                    value = (value as? FieldValue.Text)?.value.orEmpty(),
                    enabled = !isLocked,
                    isMono = true,
                    onChange = { onValueChange(FieldValue.Text(it)) }
                )
                ComponentKind.SLIDER -> SliderControl(
                    field = field,
                    value = (value as? FieldValue.NumberVal)?.value ?: field.min ?: 0.0,
                    enabled = !isLocked,
                    onChange = { onValueChange(FieldValue.NumberVal(it)) }
                )
                ComponentKind.NUMBER -> NumberControl(
                    field = field,
                    value = (value as? FieldValue.NumberVal)?.value ?: 0.0,
                    enabled = !isLocked,
                    onChange = { onValueChange(FieldValue.NumberVal(it)) }
                )
                ComponentKind.CHECKBOX -> CheckboxControl(
                    label = field.label,
                    checked = (value as? FieldValue.BoolVal)?.value ?: false,
                    enabled = !isLocked,
                    onChange = { onValueChange(FieldValue.BoolVal(it)) }
                )
                ComponentKind.CHECKBOX_GROUP -> MultiSelectControl(
                    choices = field.choices,
                    selected = (value as? FieldValue.MultiSelect)?.values.orEmpty(),
                    enabled = !isLocked,
                    onChange = { onValueChange(FieldValue.MultiSelect(it)) }
                )
                ComponentKind.RADIO -> RadioControl(
                    choices = field.choices,
                    selected = (value as? FieldValue.Text)?.value.orEmpty(),
                    enabled = !isLocked,
                    onChange = { onValueChange(FieldValue.Text(it)) }
                )
                ComponentKind.DROPDOWN -> {
                    if (field.multiSelect) {
                        MultiSelectControl(
                            choices = field.choices,
                            selected = (value as? FieldValue.MultiSelect)?.values.orEmpty(),
                            enabled = !isLocked,
                            onChange = { onValueChange(FieldValue.MultiSelect(it)) }
                        )
                    } else {
                        DropdownControl(
                            choices = field.choices,
                            selected = (value as? FieldValue.Text)?.value.orEmpty(),
                            enabled = !isLocked,
                            onChange = { onValueChange(FieldValue.Text(it)) }
                        )
                    }
                }
                ComponentKind.IMAGE -> ImageInputControl(
                    ref = value as? FieldValue.MediaRef,
                    enabled = !isLocked,
                    onCreateCameraTarget = onCreateCameraTarget,
                    onAttachUri = { uri -> onAttachUri(uri, "image/jpeg") },
                    onDirectFile = { file ->
                        onValueChange(
                            FieldValue.MediaRef(
                                localPath = file.absolutePath,
                                fileName = file.name,
                                mimeType = "image/jpeg"
                            )
                        )
                    },
                    onOpenLibrary = { onOpenLibraryPicker(ComponentKind.IMAGE) },
                    onClear = { onValueChange(FieldValue.Empty) }
                )
                ComponentKind.AUDIO -> AudioInputControl(
                    ref = value as? FieldValue.MediaRef,
                    enabled = !isLocked,
                    isRecording = isRecordingAudio,
                    onAttachUri = { uri -> onAttachUri(uri, "audio/mpeg") },
                    onToggleRecord = onToggleAudioRecord,
                    onOpenLibrary = { onOpenLibraryPicker(ComponentKind.AUDIO) },
                    onClear = { onValueChange(FieldValue.Empty) }
                )
                ComponentKind.VIDEO -> VideoInputControl(
                    ref = value as? FieldValue.MediaRef,
                    enabled = !isLocked,
                    onAttachUri = { uri -> onAttachUri(uri, "video/mp4") },
                    onOpenLibrary = { onOpenLibraryPicker(ComponentKind.VIDEO) },
                    onClear = { onValueChange(FieldValue.Empty) }
                )
                ComponentKind.FILE -> FileInputControl(
                    ref = value as? FieldValue.MediaRef,
                    enabled = !isLocked,
                    onAttachUri = { uri -> onAttachUri(uri, "application/octet-stream") },
                    onOpenLibrary = { onOpenLibraryPicker(ComponentKind.FILE) },
                    onClear = { onValueChange(FieldValue.Empty) }
                )
                ComponentKind.MULTIMODAL -> MultimodalInputControl(
                    field = field,
                    value = (value as? FieldValue.Multimodal) ?: FieldValue.Multimodal(),
                    enabled = !isLocked,
                    onValueChange = onValueChange,
                    onOpenLibrary = { onOpenLibraryPicker(ComponentKind.FILE) }
                )
                ComponentKind.COLOR_PICKER -> ColorPickerControl(
                    hex = (value as? FieldValue.Text)?.value.orEmpty().ifBlank { "#FFB020" },
                    enabled = !isLocked,
                    onChange = { onValueChange(FieldValue.Text(it)) }
                )
                else -> StructuredFallbackControl(
                    field = field,
                    rawJson = when (value) {
                        is FieldValue.StructuredJson -> value.rawJson
                        is FieldValue.Text -> value.value
                        else -> field.defaultValueJson.orEmpty()
                    },
                    enabled = !isLocked,
                    onChange = { onValueChange(FieldValue.StructuredJson(it)) }
                )
            }
        }
    }
}

@Composable
private fun TextboxControl(
    field: ComponentField,
    value: String,
    enabled: Boolean,
    isMono: Boolean,
    onChange: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val isMultiline = field.lines > 1 || field.parameterName.contains("prompt", true) || field.label.contains("prompt", true)

    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("input_text_${field.parameterName}"),
            placeholder = {
                Text(
                    text = field.placeholder.ifBlank { "Enter ${field.label.lowercase()}…" },
                    color = TextMuted
                )
            },
            minLines = if (isMultiline) field.lines.coerceIn(3, 6) else 1,
            maxLines = if (isMultiline) field.maxLines.coerceIn(6, 12) else 3,
            textStyle = if (isMono) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily)
            } else {
                MaterialTheme.typography.bodyLarge
            },
            colors = oledTextFieldColors(),
            trailingIcon = {
                if (enabled && value.isNotEmpty()) {
                    IconButton(
                        onClick = { onChange("") },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear_input), tint = TextSecondary)
                    }
                }
            }
        )
        if (enabled && isMultiline) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${value.length} chars",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
                AssistChip(
                    onClick = {
                        clipboard.getText()?.text?.let { pasted ->
                            onChange(if (value.isBlank()) pasted else "$value\n$pasted")
                        }
                    },
                    label = { Text("Paste", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = ObsidianContainer)
                )
            }
        }
    }
}

@Composable
private fun SliderControl(
    field: ComponentField,
    value: Double,
    enabled: Boolean,
    onChange: (Double) -> Unit
) {
    val min = (field.min ?: 0.0).toFloat()
    val max = (field.max ?: 100.0).coerceAtLeast((field.min ?: 0.0) + 1.0).toFloat()
    val step = field.step?.takeIf { it > 0 }?.toFloat()
    val clamped = value.toFloat().coerceIn(min, max)
    var textState by remember(value) { mutableStateOf(formatNumeric(value)) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Slider(
                value = clamped,
                onValueChange = { raw ->
                    val snapped = if (step != null) {
                        val stepsCount = ((raw - min) / step).roundToInt()
                        (min + stepsCount * step).coerceIn(min, max)
                    } else raw
                    onChange(snapped.toDouble())
                },
                enabled = enabled,
                valueRange = min..max,
                modifier = Modifier
                    .weight(1f)
                    .testTag("input_slider_${field.parameterName}"),
                colors = SliderDefaults.colors(
                    thumbColor = SignalAmber,
                    activeTrackColor = SignalAmber,
                    inactiveTrackColor = ObsidianContainerHigh
                )
            )
            OutlinedTextField(
                value = textState,
                onValueChange = { str ->
                    textState = str
                    str.toDoubleOrNull()?.let { d ->
                        onChange(d.coerceIn(min.toDouble(), max.toDouble()))
                    }
                },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.labelMedium.copy(color = SignalAmber),
                colors = oledTextFieldColors(),
                modifier = Modifier.width(86.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatNumeric(min.toDouble()), style = MaterialTheme.typography.labelSmall, color = TextMuted)
            if (step != null) {
                Text("step ${formatNumeric(step.toDouble())}", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
            Text(formatNumeric(max.toDouble()), style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
    }
}

@Composable
private fun NumberControl(
    field: ComponentField,
    value: Double,
    enabled: Boolean,
    onChange: (Double) -> Unit
) {
    val step = field.step?.takeIf { it > 0 } ?: 1.0
    var textState by remember(value) { mutableStateOf(formatNumeric(value)) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = {
                val next = value - step
                onChange(field.min?.let { next.coerceAtLeast(it) } ?: next)
            },
            enabled = enabled,
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = textState,
            onValueChange = { s ->
                textState = s
                s.toDoubleOrNull()?.let { onChange(it) }
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = oledTextFieldColors(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = JetBrainsMonoFontFamily),
            modifier = Modifier
                .weight(1f)
                .testTag("input_number_${field.parameterName}")
        )
        OutlinedButton(
            onClick = {
                val next = value + step
                onChange(field.max?.let { next.coerceAtMost(it) } ?: next)
            },
            enabled = enabled,
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun CheckboxControl(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = if (checked) "Enabled" else "Disabled",
            style = MaterialTheme.typography.bodyLarge,
            color = if (checked) SignalAmber else TextSecondary
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = SignalAmber
            )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RadioControl(
    choices: List<String>,
    selected: String,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    if (choices.isEmpty()) {
        OutlinedTextField(
            value = selected,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            colors = oledTextFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        choices.forEach { choice ->
            val isSelected = choice == selected
            FilterChip(
                selected = isSelected,
                onClick = { if (enabled) onChange(choice) },
                enabled = enabled,
                label = { Text(choice) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = SignalAmber,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.minimumInteractiveComponentSize()
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiSelectControl(
    choices: List<String>,
    selected: List<String>,
    enabled: Boolean,
    onChange: (List<String>) -> Unit
) {
    if (choices.isEmpty()) {
        var text by remember(selected) { mutableStateOf(selected.joinToString(", ")) }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onChange(it.split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() })
            },
            enabled = enabled,
            placeholder = { Text("Comma-separated values") },
            colors = oledTextFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        choices.forEach { choice ->
            val isSelected = choice in selected
            FilterChip(
                selected = isSelected,
                onClick = {
                    if (!enabled) return@FilterChip
                    val next = if (isSelected) selected - choice else selected + choice
                    onChange(next)
                },
                enabled = enabled,
                label = { Text(choice) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = TelemetryCyan,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondary
                ),
                modifier = Modifier.minimumInteractiveComponentSize()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownControl(
    choices: List<String>,
    selected: String,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    if (choices.isEmpty()) {
        OutlinedTextField(
            value = selected,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            colors = oledTextFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected.ifBlank { choices.first() },
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = oledTextFieldColors(),
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            containerColor = ObsidianContainerHigh
        ) {
            choices.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, color = MaterialTheme.colorScheme.onSurface) },
                    onClick = {
                        onChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ImageInputControl(
    ref: FieldValue.MediaRef?,
    enabled: Boolean,
    onCreateCameraTarget: () -> Pair<Uri, File>,
    onAttachUri: (Uri) -> Unit,
    onDirectFile: (File) -> Unit,
    onOpenLibrary: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onAttachUri(it) }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            pendingCameraFile?.let { onDirectFile(it) }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val (uri, file) = onCreateCameraTarget()
            pendingCameraFile = file
            takePicture.launch(uri)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ref != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(ObsidianContainer)
            ) {
                AsyncImage(
                    model = ref.localPath?.let { File(it) } ?: ref.remoteUrl ?: ref.uriString,
                    contentDescription = ref.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
                if (enabled) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear_input), tint = Color.White)
                    }
                }
            }
            Text(ref.fileName, style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        if (enabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = stringResource(R.string.cd_pick_media), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Gallery", maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        val hasCam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        if (hasCam) {
                            val (uri, file) = onCreateCameraTarget()
                            pendingCameraFile = file
                            takePicture.launch(uri)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = stringResource(R.string.cd_capture_photo), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Camera", maxLines = 1)
                }
            }
            OutlinedButton(
                onClick = onOpenLibrary,
                modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Outlined.CollectionsBookmark, contentDescription = stringResource(R.string.cd_pick_library), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reuse from Shared Media Library")
            }
        }
    }
}

@Composable
private fun AudioInputControl(
    ref: FieldValue.MediaRef?,
    enabled: Boolean,
    isRecording: Boolean,
    onAttachUri: (Uri) -> Unit,
    onToggleRecord: () -> Unit,
    onOpenLibrary: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onAttachUri(it) }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onToggleRecord()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ref != null) {
            Surface(
                color = ObsidianContainer,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, TelemetryCyan.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = null, tint = TelemetryCyan)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ref.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(ref.mimeType, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                    if (enabled) {
                        IconButton(onClick = onClear, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear_input), tint = TextSecondary)
                        }
                    }
                }
            }
        }

        if (enabled) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { docPicker.launch(arrayOf("audio/*")) },
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = stringResource(R.string.cd_pick_file), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Audio File", maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        if (isRecording) {
                            onToggleRecord()
                        } else {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (granted) onToggleRecord() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    border = BorderStroke(1.dp, if (isRecording) StatusCrimson else HairlineBorder),
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = stringResource(R.string.cd_record_audio),
                        tint = if (isRecording) StatusCrimson else SignalAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isRecording) "Stop Rec" else "Record Mic", color = if (isRecording) StatusCrimson else MaterialTheme.colorScheme.onSurface)
                }
            }
            OutlinedButton(
                onClick = onOpenLibrary,
                modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Outlined.CollectionsBookmark, contentDescription = stringResource(R.string.cd_pick_library), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reuse Audio from Shared Library")
            }
        }
    }
}

@Composable
private fun VideoInputControl(
    ref: FieldValue.MediaRef?,
    enabled: Boolean,
    onAttachUri: (Uri) -> Unit,
    onOpenLibrary: () -> Unit,
    onClear: () -> Unit
) {
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onAttachUri(it) }
    }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onAttachUri(it) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ref != null) {
            Surface(
                color = ObsidianContainer,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, SignalAmber.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = SignalAmber)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ref.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(ref.mimeType, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                    if (enabled) {
                        IconButton(onClick = onClear, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear_input), tint = TextSecondary)
                        }
                    }
                }
            }
        }
        if (enabled) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pick Video")
                }
                OutlinedButton(
                    onClick = { docPicker.launch(arrayOf("video/*")) },
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Browse")
                }
            }
            OutlinedButton(
                onClick = onOpenLibrary,
                modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reuse Video from Shared Library")
            }
        }
    }
}

@Composable
private fun FileInputControl(
    ref: FieldValue.MediaRef?,
    enabled: Boolean,
    onAttachUri: (Uri) -> Unit,
    onOpenLibrary: () -> Unit,
    onClear: () -> Unit
) {
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onAttachUri(it) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ref != null) {
            Surface(color = ObsidianContainer, shape = MaterialTheme.shapes.small) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = null, tint = TelemetryCyan)
                    Spacer(Modifier.width(8.dp))
                    Text(ref.fileName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (enabled) {
                        IconButton(onClick = onClear, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear_input))
                        }
                    }
                }
            }
        }
        if (enabled) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { docPicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Select File")
                }
                OutlinedButton(
                    onClick = onOpenLibrary,
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Library")
                }
            }
        }
    }
}

@Composable
private fun MultimodalInputControl(
    field: ComponentField,
    value: FieldValue.Multimodal,
    enabled: Boolean,
    onValueChange: (FieldValue) -> Unit,
    onOpenLibrary: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value.text,
            onValueChange = { onValueChange(value.copy(text = it)) },
            enabled = enabled,
            minLines = 2,
            maxLines = 6,
            placeholder = { Text(field.placeholder.ifBlank { "Enter prompt & attach media…" }, color = TextMuted) },
            colors = oledTextFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        if (value.files.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                value.files.forEachIndexed { idx, fileRef ->
                    Surface(color = ObsidianContainer, shape = RoundedCornerShape(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, tint = TelemetryCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(fileRef.fileName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (enabled) {
                                IconButton(
                                    onClick = {
                                        val next = value.files.toMutableList().apply { removeAt(idx) }
                                        onValueChange(value.copy(files = next))
                                    }
                                ) {
                                    Icon(Icons.Default.Clear, contentDescription = "Remove attachment", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (enabled) {
            OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()) {
                Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Attach from Shared Media Library")
            }
        }
    }
}

@Composable
private fun ColorPickerControl(
    hex: String,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    val swatches = listOf("#FFB020", "#22D3EE", "#A78BFA", "#10B981", "#F43F5E", "#FFFFFF", "#000000")
    val parsedColor = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(SignalAmber)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(parsedColor)
                    .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp))
            )
            OutlinedTextField(
                value = hex,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                colors = oledTextFieldColors(),
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            swatches.forEach { sw ->
                val c = Color(android.graphics.Color.parseColor(sw))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(2.dp, if (hex.equals(sw, true)) SignalAmber else HairlineBorder, CircleShape)
                        .clickable(enabled = enabled) { onChange(sw) }
                )
            }
        }
    }
}

@Composable
private fun StructuredFallbackControl(
    field: ComponentField,
    rawJson: String,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Structured fallback (${field.gradioType} / ${field.apiDataType})",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
        OutlinedTextField(
            value = rawJson,
            onValueChange = onChange,
            enabled = enabled,
            minLines = 3,
            maxLines = 8,
            placeholder = { Text("JSON or raw value", color = TextMuted) },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
            colors = oledTextFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun oledTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = SignalAmber,
    unfocusedBorderColor = HairlineBorder,
    disabledBorderColor = HairlineBorder.copy(alpha = 0.5f),
    focusedContainerColor = ObsidianContainer,
    unfocusedContainerColor = ObsidianContainer,
    disabledContainerColor = ObsidianContainerLow,
    cursorColor = SignalAmber
)

private fun formatNumeric(value: Double): String {
    return if (value % 1.0 == 0.0) value.toLong().toString()
    else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
}
