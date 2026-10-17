package io.github.nova.ffmpegkit.editor

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nova.ffmpegkit.FFmpeg
import io.github.nova.ffmpegkit.FFmpegException
import io.github.nova.ffmpegkit.MediaInfo
import io.github.nova.ffmpegkit.MediaInput
import io.github.nova.ffmpegkit.SplitRequest
import io.github.nova.ffmpegkit.TimeRange
import io.github.nova.ffmpegkit.TrimMode
import io.github.nova.ffmpegkit.TrimRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Where the input came from; recorded in the report because each picker hands out different Uris. */
enum class Picker(val label: String) {
    FILES("System file picker (ACTION_OPEN_DOCUMENT)"),
    PHOTOS("Photo picker"),
}

/** The picked input as its provider and [FFmpeg.probe] describe it. Unknown values stay null. */
class VideoSource(
    val uri: Uri,
    val picker: Picker,
    val displayName: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    /** Null when probing failed; see [probeError]. */
    val info: MediaInfo?,
    val probeError: String?,
) {
    val duration: Duration? get() = info?.duration

    /** Container of the outputs: the input's own, since stream copy keeps the codecs. */
    val extension: String =
        mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: displayName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
            ?: "mp4"

    /** TrimMode.EDIT_LIST needs an MP4-family container. */
    val supportsEditList: Boolean = extension.lowercase() in setOf("mp4", "m4v", "mov", "3gp", "3g2")

    val outputMimeType: String =
        mimeType?.takeIf { it.startsWith("video/") }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "video/mp4"

    /** File name stem for outputs: the input's name without extension and without characters MediaStore rejects. */
    val stem: String =
        (displayName?.substringBeforeLast('.') ?: "video").replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_").take(60)
}

sealed interface Task {
    data object Idle : Task
    class Running(val description: String) : Task
    class Done(val saved: List<SavedVideo>, val report: String) : Task
    class Failed(val message: String, val report: String) : Task
    data object Cancelled : Task
}

class EditorState(
    val source: VideoSource? = null,
    val loadingSource: Boolean = false,
    val task: Task = Task.Idle,
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val resolver = application.contentResolver
    private val gallery = Gallery(application)
    private val _state = MutableStateFlow(EditorState())
    private var job: Job? = null

    val state: StateFlow<EditorState> = _state.asStateFlow()

    val needsWritePermission: Boolean get() = gallery.needsWritePermission

    fun select(uri: Uri, picker: Picker) {
        if (_state.value.task is Task.Running) return
        job?.cancel()
        _state.value = EditorState(loadingSource = true)
        job = viewModelScope.launch {
            val source = withContext(Dispatchers.IO) { describe(uri, picker) }
            _state.value = EditorState(source = source)
        }
    }

    fun trim(range: TimeRange, mode: TrimMode) {
        val source = _state.value.source ?: return
        run(source, "Trimming to $range ($mode)") {
            val name = "${source.stem}-trim-${timestamp()}.${source.extension}"
            val saved = gallery.saveTrim(name, source.outputMimeType, source.extension) { output ->
                FFmpeg.trim(TrimRequest(MediaInput.of(resolver, source.uri), output, range, mode))
            }
            "trim requested=$range mode=$mode" to listOf(saved)
        }
    }

    fun split(segmentDuration: Duration) {
        val source = _state.value.source ?: return
        run(source, "Splitting into ≥ $segmentDuration segments") {
            val saved = gallery.saveSplit(source.outputMimeType) { directory ->
                FFmpeg.split(
                    SplitRequest.byDuration(
                        MediaInput.of(resolver, source.uri),
                        directory,
                        segmentDuration,
                        "${source.stem}-split-${timestamp()}",
                        source.extension,
                    ),
                )
            }
            "split segmentDuration=$segmentDuration" to saved
        }
    }

    /** Cancels the running task; the library removes its temporary files and nothing is saved. */
    fun cancel() {
        job?.cancel()
    }

    private fun run(source: VideoSource, description: String, work: suspend () -> Pair<String, List<SavedVideo>>) {
        if (job?.isActive == true) return
        _state.update { EditorState(source = it.source, task = Task.Running(description)) }
        job = viewModelScope.launch {
            val started = TimeSource.Monotonic.markNow()
            val task = try {
                val (operation, saved) = work()
                Task.Done(saved, report(source, operation, started.elapsedNow(), saved.joinToString("\n") {
                    "  ${it.displayName}: actual=${it.actualRange}, ${it.sizeBytes} bytes, ${it.uri}"
                }))
            } catch (e: CancellationException) {
                // Our own cancel() keeps the source; a cleared ViewModel discards the state anyway.
                _state.update { EditorState(source = it.source, task = Task.Cancelled) }
                throw e
            } catch (e: FFmpegException) {
                Task.Failed("${e.reason}: ${e.message}", report(source, description, started.elapsedNow(), "  failed: $e"))
            } catch (e: Exception) {
                Task.Failed(e.toString(), report(source, description, started.elapsedNow(), "  failed: $e"))
            }
            Log.i(TAG, if (task is Task.Done) task.report else (task as Task.Failed).report)
            _state.update { EditorState(source = it.source, task = task) }
        }
    }

    private suspend fun describe(uri: Uri, picker: Picker): VideoSource {
        var displayName: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                displayName = if (it.isNull(0)) null else it.getString(0)
                size = if (it.isNull(1)) null else it.getLong(1)
            }
        }
        // Probing first tells whether trim and split can read the input at all.
        val (info, error) = try {
            FFmpeg.probe(MediaInput.of(resolver, uri)) to null
        } catch (e: FFmpegException) {
            null to "${e.reason}: ${e.message}"
        }
        return VideoSource(uri, picker, displayName, resolver.getType(uri), size, info, error)
    }

    private fun report(source: VideoSource, operation: String, elapsed: Duration, outcome: String): String = buildString {
        appendLine("FFmpegKit demo, ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.first()}")
        append("picker: ${source.picker.label}")
        if (source.picker == Picker.PHOTOS) append(", photo picker available=${PickVisualMedia.isPhotoPickerAvailable(getApplication())}")
        appendLine()
        appendLine("input: ${source.uri} (authority ${source.uri.authority})")
        appendLine("  name=${source.displayName}, mime=${source.mimeType}, size=${source.sizeBytes}")
        appendLine("  probe: ${source.info ?: source.probeError}")
        appendLine("$operation, container=${source.extension}, elapsed=$elapsed")
        append(outcome)
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())

    private companion object {
        const val TAG = "FFmpegKitDemo"
    }
}
