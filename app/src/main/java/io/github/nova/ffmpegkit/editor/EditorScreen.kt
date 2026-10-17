package io.github.nova.ffmpegkit.editor

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.nova.ffmpegkit.StreamInfo
import io.github.nova.ffmpegkit.TimeRange
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private enum class Mode { TRIM, SPLIT }

/** Pick a video → trim or split it → save the result to the gallery. */
@Composable
fun EditorScreen(viewModel: EditorViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val running = state.task is Task.Running

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.select(it, Picker.FILES) }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.select(it, Picker.PHOTOS) }
    }

    // Only Android 9 and older need a permission, to write the public Movies directory.
    var pendingWork by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingWork?.invoke() else toast(context, "Saving to the gallery needs storage permission")
        pendingWork = null
    }
    fun withWritePermission(work: () -> Unit) {
        if (viewModel.needsWritePermission) {
            pendingWork = work
            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            work()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Section("1. Pick a video") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { filePicker.launch(arrayOf("video/*")) }, enabled = !running) {
                        Text("Files")
                    }
                    FilledTonalButton(
                        onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                        enabled = !running,
                    ) {
                        Text("Photos")
                    }
                }
                when {
                    state.loadingSource -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.source != null -> SourceDetails(state.source!!)
                    else -> Text("Nothing picked yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        state.source?.let { source ->
            item(key = source.uri.toString()) {
                EditSection(
                    source = source,
                    enabled = !running,
                    onTrim = { range -> withWritePermission { viewModel.trim(range) } },
                    onSplit = { duration -> withWritePermission { viewModel.split(duration) } },
                    onInvalid = { toast(context, it) },
                )
            }
        }

        if (state.task != Task.Idle) {
            item {
                ResultSection(state.task, onCancel = viewModel::cancel)
            }
        }
    }
}

@Composable
private fun SourceDetails(source: VideoSource) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = source.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp),
        )
        Column {
            Text(source.displayName ?: "(no name)", style = MaterialTheme.typography.titleSmall)
            Detail("${source.mimeType ?: "unknown type"} · ${source.sizeBytes?.let { Formatter.formatFileSize(context, it) } ?: "unknown size"}")
            Detail("Duration ${source.duration?.let(::formatSeconds)?.plus(" s") ?: "unknown"}")
            source.info?.streams?.forEach { Detail(describe(it)) }
            source.probeError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
    Detail("${source.picker.label}\n${source.uri}")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSection(
    source: VideoSource,
    enabled: Boolean,
    onTrim: (TimeRange) -> Unit,
    onSplit: (Duration) -> Unit,
    onInvalid: (String) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(Mode.TRIM) }
    var start by rememberSaveable { mutableStateOf("0") }
    var end by rememberSaveable { mutableStateOf("") }
    var segment by rememberSaveable { mutableStateOf("10") }

    Section("2. Edit (stream copy, keyframe aligned)") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Mode.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = mode == entry,
                    onClick = { mode = entry },
                    shape = SegmentedButtonDefaults.itemShape(index, Mode.entries.size),
                ) {
                    Text(if (entry == Mode.TRIM) "Trim" else "Split")
                }
            }
        }
        when (mode) {
            Mode.TRIM -> {
                val total = source.duration
                if (total != null && total.isPositive()) {
                    val max = total.inWholeMilliseconds / 1000f
                    val from = (parseSeconds(start)?.inWholeMilliseconds?.div(1000f) ?: 0f).coerceIn(0f, max)
                    val to = (parseSeconds(end)?.inWholeMilliseconds?.div(1000f) ?: max).coerceIn(from, max)
                    RangeSlider(
                        value = from..to,
                        onValueChange = {
                            start = formatSeconds((it.start * 1000).roundToLong().milliseconds)
                            // The right end at the media end means "to the end", which needs no duration.
                            end = if (it.endInclusive >= max) "" else formatSeconds((it.endInclusive * 1000).roundToLong().milliseconds)
                        },
                        valueRange = 0f..max,
                        enabled = enabled,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondsField("Start (s)", start, enabled, Modifier.weight(1f)) { start = it }
                    SecondsField("End (s), empty = end", end, enabled, Modifier.weight(1f)) { end = it }
                }
                Button(
                    onClick = {
                        val from = parseSeconds(start)
                        val to = if (end.isBlank()) null else parseSeconds(end) ?: return@Button onInvalid("End is not a number")
                        if (from == null) return@Button onInvalid("Start is not a number")
                        try {
                            onTrim(TimeRange(from, to))
                        } catch (e: IllegalArgumentException) {
                            onInvalid(e.message ?: "Invalid range")
                        }
                    },
                    enabled = enabled,
                ) {
                    Text("Trim and save to gallery")
                }
            }

            Mode.SPLIT -> {
                SecondsField("Segment length (s)", segment, enabled, Modifier.fillMaxWidth()) { segment = it }
                Detail("Every segment but the last is at least this long and starts at a keyframe.")
                Button(
                    onClick = {
                        val length = parseSeconds(segment)
                        if (length == null || !length.isPositive()) onInvalid("Segment length must be a positive number") else onSplit(length)
                    },
                    enabled = enabled,
                ) {
                    Text("Split and save to gallery")
                }
            }
        }
    }
}

@Composable
private fun ResultSection(task: Task, onCancel: () -> Unit) {
    val context = LocalContext.current
    Section("3. Result") {
        when (task) {
            Task.Idle -> Unit
            is Task.Running -> {
                Text(task.description)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
            Task.Cancelled -> Text("Cancelled. Nothing was saved.")
            is Task.Failed -> {
                Text(task.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { copy(context, task.report) }) { Text("Copy report") }
            }
            is Task.Done -> {
                Text("Saved ${task.saved.size} video(s) to Movies/FFmpegKit")
                task.saved.forEach { video ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(video.displayName, style = MaterialTheme.typography.bodyMedium)
                            Detail(
                                "${formatSeconds(video.actualRange.start)}–${video.actualRange.endExclusive?.let(::formatSeconds) ?: "end"} s · " +
                                    Formatter.formatFileSize(context, video.sizeBytes),
                            )
                        }
                        TextButton(onClick = { play(context, video.uri) }) { Text("Play") }
                    }
                }
                TextButton(onClick = { copy(context, task.report) }) { Text("Copy report") }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SecondsField(label: String, value: String, enabled: Boolean, modifier: Modifier, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

private fun describe(stream: StreamInfo): String = when (stream) {
    is StreamInfo.Video -> buildString {
        append("#${stream.index} ${stream.codecName} ${stream.width ?: "?"}×${stream.height ?: "?"}")
        stream.frameRate?.let { append(String.format(Locale.ROOT, " · %.2f fps", it)) }
        if (stream.rotation != 0) append(" · rotated ${stream.rotation ?: "?"}°")
        if (stream.isAttachedPicture) append(" · cover")
    }
    is StreamInfo.Audio -> "#${stream.index} ${stream.codecName} · ${stream.sampleRate ?: "?"} Hz · ${stream.channelLayout ?: "${stream.channels ?: "?"} ch"}"
    is StreamInfo.Other -> "#${stream.index} ${stream.mediaType} ${stream.codecName}"
}

/** Seconds with millisecond precision; null for anything that is not a finite number. */
private fun parseSeconds(text: String): Duration? =
    text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { (it * 1000).roundToLong().milliseconds }

private fun formatSeconds(duration: Duration): String = String.format(Locale.ROOT, "%.3f", duration.inWholeMicroseconds / 1e6)

private fun play(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        toast(context, "No app can play this video")
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FFmpegKit report", text))
    toast(context, "Report copied")
}

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
