package com.breakpro.ultra.audio

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.*
import androidx.media3.transformer.Composition
import com.breakpro.ultra.data.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private data class AClip(
    val id: Long, val uri: Uri, val name: String, val durationMs: Long,
    val startMs: Long = 0, val endMs: Long = durationMs
) {
    val lengthMs get() = endMs - startMs
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d.%d".format(s / 60, s % 60, (ms % 1000) / 100)
}

private suspend fun duration(ctx: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (_: Throwable) { 0L } finally { r.release() }
}

private fun clipItem(c: AClip) = MediaItem.Builder().setUri(c.uri).setClippingConfiguration(
    MediaItem.ClippingConfiguration.Builder()
        .setStartPositionMs(c.startMs).setEndPositionMs(c.endMs).build()
).build()

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun AudioEditorScreen(media: List<MediaItemEntity>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clips = remember { mutableStateListOf<AClip>() }
    var selected by remember { mutableIntStateOf(0) }
    var nextId by remember { mutableLongStateOf(1) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var transformer by remember { mutableStateOf<Transformer?>(null) }
    val scope = rememberCoroutineScope()
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recFile by remember { mutableStateOf<File?>(null) }

    val player = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { player.release(); transformer?.cancel(); try { recorder?.release() } catch (_: Throwable) { } } }

    LaunchedEffect(media) {
        media.filter { it.mimeType.startsWith("audio/") }.forEach { m ->
            val uri = Uri.parse(m.uri)
            if (clips.none { it.uri == uri }) {
                val d = duration(ctx, uri)
                if (d > 0) clips.add(AClip(nextId++, uri, m.displayName, d))
            }
        }
    }
    if (selected >= clips.size) selected = (clips.size - 1).coerceAtLeast(0)
    val cur = clips.getOrNull(selected)

    fun startRecording() {
        try {
            val f = File(ctx.filesDir, "voiceover_${System.currentTimeMillis()}.m4a")
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r; recFile = f; recording = true; status = "Recording..."
        } catch (t: Throwable) {
            status = "Recording failed: ${t.message}"
        }
    }

    fun stopRecording() {
        val r = recorder
        val f = recFile
        try { r?.stop() } catch (_: Throwable) { }
        r?.release()
        recorder = null; recording = false
        if (f != null && f.exists() && f.length() > 0) {
            scope.launch {
                val u = Uri.fromFile(f)
                val d = duration(ctx, u)
                if (d > 0) { clips.add(AClip(nextId++, u, f.name, d)); status = "Voiceover added to tracks" }
                else status = "Recording was empty"
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else status = "Microphone permission denied"
    }

    LaunchedEffect(cur?.id, cur?.startMs, cur?.endMs) {
        if (cur != null) { player.setMediaItem(clipItem(cur)); player.prepare() }
        else player.clearMediaItems()
    }

    LaunchedEffect(exporting) {
        val holder = ProgressHolder()
        while (exporting) {
            val t = transformer
            if (t != null && t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                progress = holder.progress / 100f
            }
            delay(250)
        }
    }

    fun startExport() {
        if (clips.isEmpty()) return
        val out = File(ctx.cacheDir, "audio_${System.currentTimeMillis()}.m4a")
        val items = clips.map {
            EditedMediaItem.Builder(clipItem(it)).setRemoveVideo(true).build()
        }
        val composition = Composition.Builder(listOf(EditedMediaItemSequence(items))).build()
        val t = Transformer.Builder(ctx)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val ok = saveAudio(ctx, out)
                    if (ok) com.breakpro.ultra.exportlog.ExportLog.add(ctx, "AAC", "${clips.size} tracks", out.length())
                    exporting = false; progress = 1f
                    status = if (ok) "Saved to Music/BreakPro (${out.length() / 1024} KB)" else "Saving failed"
                    out.delete()
                }

                override fun onError(composition: Composition, exportResult: ExportResult, e: ExportException) {
                    exporting = false
                    status = "Export failed: ${e.errorCodeName}"
                    out.delete()
                }
            }).build()
        transformer = t
        progress = 0f; status = "Exporting AAC…"; exporting = true
        t.start(composition, out.absolutePath)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Audio Editor") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = {
                if (recording) stopRecording()
                else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(if (recording) "Stop recording" else "Record voiceover") }
            if (clips.isEmpty()) {
                Text("No audio in this project. Go back, tap Import media and pick audio files.")
                return@Column
            }
            Text("Tracks (${clips.size}, total ${fmt(clips.sumOf { it.lengthMs })})",
                style = MaterialTheme.typography.labelMedium)
            clips.forEachIndexed { i, c ->
                Card(
                    Modifier.fillMaxWidth().clickable { selected = i },
                    colors = CardDefaults.cardColors(
                        containerColor = if (i == selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, Modifier.weight(1f), maxLines = 1)
                        Text(fmt(c.lengthMs), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (cur != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                        Text("Play / Pause")
                    }
                }
                var range by remember(cur.id, cur.startMs, cur.endMs) {
                    mutableStateOf(cur.startMs.toFloat()..cur.endMs.toFloat())
                }
                Text("Trim: ${fmt(range.start.toLong())} – ${fmt(range.endInclusive.toLong())}")
                RangeSlider(
                    value = range, onValueChange = { range = it },
                    valueRange = 0f..cur.durationMs.toFloat(),
                    onValueChangeFinished = {
                        val s = range.start.toLong(); val e = range.endInclusive.toLong()
                        if (e - s >= 200) clips[selected] = cur.copy(startMs = s, endMs = e)
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(enabled = selected > 0, onClick = {
                        val c = clips.removeAt(selected); clips.add(selected - 1, c); selected--
                    }) { Text("Up") }
                    OutlinedButton(enabled = selected < clips.size - 1, onClick = {
                        val c = clips.removeAt(selected); clips.add(selected + 1, c); selected++
                    }) { Text("Down") }
                    OutlinedButton(onClick = { clips.add(selected + 1, cur.copy(id = nextId++)) }) { Text("Copy") }
                    OutlinedButton(onClick = { clips.removeAt(selected) }) { Text("Delete") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !exporting, onClick = { startExport() }) { Text("Export AAC (.m4a)") }
                if (exporting) OutlinedButton(onClick = {
                    transformer?.cancel(); exporting = false; status = "Export cancelled"
                }) { Text("Cancel") }
            }
            if (exporting) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun saveAudio(ctx: Context, file: File): Boolean = try {
    val values = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, "BreakPro_${System.currentTimeMillis()}.m4a")
        put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
        put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/BreakPro")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
    ctx.contentResolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
    true
} catch (_: Throwable) { false }
