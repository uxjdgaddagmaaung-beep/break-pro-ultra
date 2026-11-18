package com.breakpro.ultra.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.TextOverlay
import com.google.common.collect.ImmutableList
import android.text.SpannableString
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.*
import androidx.media3.ui.PlayerView
import com.breakpro.ultra.data.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

data class Clip(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val startMs: Long = 0,
    val endMs: Long = durationMs,
    val grayscale: Boolean = false,
    val brightness: Float = 0f,   // -0.5..0.5
    val contrast: Float = 0f,     // -0.5..0.5
    val rotation: Int = 0,        // 0/90/180/270
    val mute: Boolean = false,
    val caption: String = "",
    val captionColor: Int = 0xFFFFFFFF.toInt(),
    val captionSize: Int = 48
) {
    val lengthMs get() = endMs - startMs
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d.%d".format(s / 60, s % 60, (ms % 1000) / 100)
}

private suspend fun readDuration(ctx: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (_: Throwable) {
        0L
    } finally {
        r.release()
    }
}

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun VideoEditorScreen(media: List<MediaItemEntity>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clips = remember { mutableStateListOf<Clip>() }
    var selected by remember { mutableIntStateOf(0) }
    var nextId by remember { mutableLongStateOf(1) }
    var height by remember {
        mutableIntStateOf(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("default_height", 720))
    }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var transformer by remember { mutableStateOf<Transformer?>(null) }

    val player = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { player.release(); transformer?.cancel() } }

    // Add imported video files to the timeline (once per uri).
    LaunchedEffect(media) {
        media.filter { it.mimeType.startsWith("video/") }.forEach { m ->
            val uri = Uri.parse(m.uri)
            if (clips.none { it.uri == uri }) {
                val d = readDuration(ctx, uri)
                if (d > 0) clips.add(Clip(nextId++, uri, m.displayName, d))
            }
        }
    }
    if (selected >= clips.size) selected = (clips.size - 1).coerceAtLeast(0)
    val cur = clips.getOrNull(selected)

    // Preview the selected clip with its trim applied.
    LaunchedEffect(cur?.id, cur?.startMs, cur?.endMs) {
        if (cur != null) {
            player.setMediaItem(
                MediaItem.Builder().setUri(cur.uri).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(cur.startMs).setEndPositionMs(cur.endMs).build()
                ).build()
            )
            player.prepare()
        } else player.clearMediaItems()
    }

    // Poll export progress.
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
        val out = File(ctx.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val items = clips.map { c ->
            val mi = MediaItem.Builder().setUri(c.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.startMs).setEndPositionMs(c.endMs).build()
            ).build()
            val fx = mutableListOf<Effect>()
            if (c.rotation != 0) fx.add(
                ScaleAndRotateTransformation.Builder().setRotationDegrees(c.rotation.toFloat()).build()
            )
            if (c.grayscale) fx.add(RgbFilter.createGrayscaleFilter())
            if (c.brightness != 0f) fx.add(Brightness(c.brightness))
            if (c.contrast != 0f) fx.add(Contrast(c.contrast))
            if (c.caption.isNotBlank()) fx.add(
                OverlayEffect(ImmutableList.of(TextOverlay.createStaticTextOverlay(styledCaption(c))))
            )
            fx.add(Presentation.createForHeight(height))
            EditedMediaItem.Builder(mi)
                .setRemoveAudio(c.mute)
                .setEffects(Effects(emptyList(), fx))
                .build()
        }
        val composition = Composition.Builder(listOf(EditedMediaItemSequence(items))).build()
        val t = Transformer.Builder(ctx)
            .setVideoMimeType("video/avc")
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val saved = saveToMovies(ctx, out)
                    if (saved) com.breakpro.ultra.exportlog.ExportLog.add(ctx, "MP4", "${height}p, ${clips.size} clips", out.length())
                    exporting = false
                    progress = 1f
                    status = if (saved) "Saved to Movies/BreakPro (${out.length() / 1024} KB)"
                    else "Export finished but saving to gallery failed"
                    out.delete()
                }

                override fun onError(
                    composition: Composition, exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    exporting = false
                    status = "Export failed: ${exportException.errorCodeName}"
                    out.delete()
                }
            }).build()
        transformer = t
        progress = 0f
        status = "Exporting ${height}p…"
        exporting = true
        t.start(composition, out.absolutePath)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Video Editor") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(12.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (clips.isEmpty()) {
                Text("No video in this project. Go back, tap Import media and pick videos.")
                return@Column
            }
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player } },
                modifier = Modifier.fillMaxWidth().height(220.dp)
            )

            // Timeline: clips in order, widths proportional to trimmed length.
            Text("Timeline (${clips.size} clips, total ${fmt(clips.sumOf { it.lengthMs })})",
                style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(clips, key = { _, c -> c.id }) { i, c ->
                    val w = (c.lengthMs / 100).coerceIn(60, 400).dp
                    Card(
                        Modifier.width(w).height(56.dp).clickable { selected = i },
                        colors = CardDefaults.cardColors(
                            containerColor = if (i == selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(Modifier.padding(6.dp)) {
                            Text(c.name, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                            Text(fmt(c.lengthMs), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (cur != null) {
                var range by remember(cur.id, cur.startMs, cur.endMs) {
                    mutableStateOf(cur.startMs.toFloat()..cur.endMs.toFloat())
                }
                Text("Trim: ${fmt(range.start.toLong())} – ${fmt(range.endInclusive.toLong())}")
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    valueRange = 0f..cur.durationMs.toFloat(),
                    onValueChangeFinished = {
                        val s = range.start.toLong()
                        val e = range.endInclusive.toLong()
                        if (e - s >= 200) clips[selected] = cur.copy(startMs = s, endMs = e)
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        val at = cur.startMs + player.currentPosition
                        if (at > cur.startMs + 200 && at < cur.endMs - 200) {
                            clips[selected] = cur.copy(endMs = at)
                            clips.add(selected + 1, cur.copy(id = nextId++, startMs = at))
                        }
                    }) { Text("Split") }
                    OutlinedButton(enabled = selected > 0, onClick = {
                        val c = clips.removeAt(selected); clips.add(selected - 1, c); selected--
                    }) { Text("◀") }
                    OutlinedButton(enabled = selected < clips.size - 1, onClick = {
                        val c = clips.removeAt(selected); clips.add(selected + 1, c); selected++
                    }) { Text("▶") }
                    OutlinedButton(onClick = { clips.add(selected + 1, cur.copy(id = nextId++)) }) { Text("Copy") }
                    OutlinedButton(onClick = { clips.removeAt(selected) }) { Text("Delete") }
                }

                Text("Clip effects (applied on export, not in preview)",
                    style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = cur.grayscale, label = { Text("B&W") },
                        onClick = { clips[selected] = cur.copy(grayscale = !cur.grayscale) })
                    FilterChip(selected = cur.mute, label = { Text("Mute") },
                        onClick = { clips[selected] = cur.copy(mute = !cur.mute) })
                    OutlinedButton(onClick = {
                        clips[selected] = cur.copy(rotation = (cur.rotation + 90) % 360)
                    }) { Text("Rotate ${cur.rotation}°") }
                }
                var br by remember(cur.id) { mutableFloatStateOf(cur.brightness) }
                Text("Brightness")
                Slider(br, { br = it }, valueRange = -0.5f..0.5f,
                    onValueChangeFinished = { clips[selected] = cur.copy(brightness = br) })
                var ct by remember(cur.id) { mutableFloatStateOf(cur.contrast) }
                Text("Contrast")
                Slider(ct, { ct = it }, valueRange = -0.5f..0.5f,
                    onValueChangeFinished = { clips[selected] = cur.copy(contrast = ct) })
                OutlinedTextField(
                    value = cur.caption,
                    onValueChange = { clips[selected] = cur.copy(caption = it) },
                    label = { Text("Caption text (whole clip)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (cur.caption.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("White" to 0xFFFFFFFF.toInt(), "Yellow" to 0xFFF5C518.toInt(),
                            "Red" to 0xFFFF3B30.toInt(), "Black" to 0xFF000000.toInt()).forEach { (n, col) ->
                            FilterChip(selected = cur.captionColor == col, label = { Text(n) },
                                onClick = { clips[selected] = cur.copy(captionColor = col) })
                        }
                    }
                    Text("Caption size: ${cur.captionSize}")
                    Slider(cur.captionSize.toFloat(), { clips[selected] = cur.copy(captionSize = it.toInt()) },
                        valueRange = 24f..120f)
                }
            }

            Text("Export resolution", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(480, 720, 1080).forEach { h ->
                    FilterChip(selected = height == h, onClick = { height = h }, label = { Text("${h}p") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !exporting, onClick = { startExport() }) { Text("Export MP4") }
                if (exporting) OutlinedButton(onClick = {
                    transformer?.cancel(); exporting = false; status = "Export cancelled"
                }) { Text("Cancel") }
            }
            if (exporting) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun saveToMovies(ctx: Context, file: File): Boolean = try {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "BreakPro_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BreakPro")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
    ctx.contentResolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
    true
} catch (_: Throwable) {
    false
}

/** Caption text with colour and size applied as standard Android spans (used by the export overlay). */
private fun styledCaption(c: Clip): SpannableString {
    val sp = SpannableString(c.caption)
    sp.setSpan(android.text.style.ForegroundColorSpan(c.captionColor), 0, sp.length,
        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    sp.setSpan(android.text.style.AbsoluteSizeSpan(c.captionSize), 0, sp.length,
        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return sp
}
