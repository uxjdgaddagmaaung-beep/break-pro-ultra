package com.breakpro.ultra.video

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.effect.BitmapOverlay
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
import androidx.media3.effect.HslAdjustment
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.media3.effect.Contrast
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.TextOverlay
import com.google.common.collect.ImmutableList
import android.text.SpannableString
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.*
import androidx.media3.transformer.Composition
import androidx.media3.ui.PlayerView
import com.breakpro.ultra.data.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
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
    val speed: Float = 1f,
    val saturation: Float = 0f,   // -100..100
    val overlayUri: Uri? = null,
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
    var detecting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var transformer by remember { mutableStateOf<Transformer?>(null) }

    val player = remember { ExoPlayer.Builder(ctx).build() }
    val scope = rememberCoroutineScope()
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
    LaunchedEffect(cur?.id, cur?.startMs, cur?.endMs, cur?.speed) {
        if (cur != null) {
            player.setMediaItem(
                MediaItem.Builder().setUri(cur.uri).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(cur.startMs).setEndPositionMs(cur.endMs).build()
                ).build()
            )
            player.prepare()
            player.setPlaybackSpeed(cur.speed)
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
            if (c.saturation != 0f) fx.add(HslAdjustment.Builder().adjustSaturation(c.saturation).build())
            if (c.caption.isNotBlank()) fx.add(
                OverlayEffect(ImmutableList.of(TextOverlay.createStaticTextOverlay(styledCaption(c))))
            )
            if (c.overlayUri != null) {
                overlayBitmapFor(ctx, c)?.let { bmp ->
                    fx.add(OverlayEffect(ImmutableList.of(BitmapOverlay.createStaticBitmapOverlay(bmp))))
                }
            }
            if (c.speed != 1f) fx.add(0, SpeedChangeEffect(c.speed))
            fx.add(Presentation.createForHeight(height))
            val audioFx: List<AudioProcessor> =
                if (c.speed != 1f) listOf(SonicAudioProcessor().apply { setSpeed(c.speed) }) else emptyList()
            EditedMediaItem.Builder(mi)
                .setRemoveAudio(c.mute)
                .setEffects(Effects(audioFx, fx))
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

    // ---- Undo / redo: snapshots of the clip list, taken after edits settle (300 ms) ----
    val undoStack = remember { mutableStateListOf<List<Clip>>() }
    val redoStack = remember { mutableStateListOf<List<Clip>>() }
    var lastSnap by remember { mutableStateOf<List<Clip>>(emptyList()) }
    val snap = clips.toList()
    LaunchedEffect(snap) {
        delay(300)
        if (snap != lastSnap) {
            if (lastSnap.isNotEmpty()) {
                undoStack.add(lastSnap)
                if (undoStack.size > 50) undoStack.removeAt(0)
                redoStack.clear()
            }
            lastSnap = snap
        }
    }
    fun restore(s: List<Clip>) { clips.clear(); clips.addAll(s); lastSnap = s }

    var tool by remember { mutableStateOf("Trim") }
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 700
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    val overlayPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (selected in clips.indices) clips[selected] = clips[selected].copy(overlayUri = uri)
        }
    }

    fun doSplit() {
        val c = cur ?: return
        val at = c.startMs + player.currentPosition
        if (at > c.startMs + 200 && at < c.endMs - 200) {
            clips[selected] = c.copy(endMs = at)
            clips.add(selected + 1, c.copy(id = nextId++, startMs = at))
        } else {
            status = "Move the playhead inside the clip (not at its edges) to split"
        }
    }

    val tools = listOf(
        ToolBtn("Trim", Icons.Default.ContentCut, "Trim"),
        ToolBtn("Split", Icons.Default.VerticalSplit, null) { doSplit() },
        ToolBtn("Speed", Icons.Default.Speed, "Speed"),
        ToolBtn("Color", Icons.Default.Palette, "Color"),
        ToolBtn("Effects", Icons.Default.AutoAwesome, "Effects"),
        ToolBtn("Text", Icons.Default.TextFields, "Text"),
        ToolBtn("Overlay", Icons.Default.AddPhotoAlternate, "Overlay"),
        ToolBtn("Audio", Icons.Default.VolumeUp, "Audio"),
        ToolBtn("Auto Cut", Icons.Default.AutoFixHigh, "Auto Cut"),
        ToolBtn("Copy", Icons.Default.ContentCopy, null) {
            cur?.let { clips.add(selected + 1, it.copy(id = nextId++)) }
        },
        ToolBtn("Delete", Icons.Default.Delete, null) {
            if (clips.isNotEmpty()) clips.removeAt(selected)
        },
        ToolBtn("Quality", Icons.Default.HighQuality, "Quality")
    )

    val previewBlock: @Composable () -> Unit = {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player } },
            modifier = Modifier.fillMaxWidth().height(if (wide) 300.dp else 210.dp)
        )
        if (exporting) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }

    val timelineBlock: @Composable () -> Unit = {
        Text("Timeline: ${clips.size} clips, total ${fmt(clips.sumOf { it.lengthMs })}",
            style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.width(48.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Video 1", fontSize = 10.sp, modifier = Modifier.height(48.dp))
                Text("Audio 1", fontSize = 10.sp, modifier = Modifier.height(28.dp))
            }
            Column(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    clips.forEachIndexed { i, c ->
                        val w = (c.lengthMs / 100).toInt().coerceIn(60, 400).dp
                        Card(
                            Modifier.width(w).height(48.dp).clickable { selected = i },
                            colors = CardDefaults.cardColors(
                                containerColor = if (i == selected) accent else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(Modifier.padding(4.dp)) {
                                Text(c.name, maxLines = 1, fontSize = 10.sp)
                                Text(fmt(c.lengthMs), fontSize = 10.sp)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    clips.forEach { c ->
                        val w = (c.lengthMs / 100).toInt().coerceIn(60, 400).dp
                        Box(
                            Modifier.width(w).height(28.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (c.mute) Color.Gray.copy(alpha = 0.4f) else Color(0xFFD946A8).copy(alpha = 0.55f)),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(if (c.mute) "muted" else "audio", fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }

    val panelBlock: @Composable () -> Unit = {
        if (cur == null) {
            Text("Tap a clip on the timeline.")
        } else when (tool) {
            "Trim" -> {
                var range by remember(cur.id, cur.startMs, cur.endMs) {
                    mutableStateOf(cur.startMs.toFloat()..cur.endMs.toFloat())
                }
                Text("Trim: ${fmt(range.start.toLong())} – ${fmt(range.endInclusive.toLong())}")
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    valueRange = 0f..cur.durationMs.toFloat(),
                    onValueChangeFinished = {
                        val a = range.start.toLong()
                        val b = range.endInclusive.toLong()
                        if (b - a >= 200) clips[selected] = cur.copy(startMs = a, endMs = b)
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(enabled = selected > 0, onClick = {
                        val c = clips.removeAt(selected); clips.add(selected - 1, c); selected--
                    }) { Text("Move left") }
                    OutlinedButton(enabled = selected < clips.size - 1, onClick = {
                        val c = clips.removeAt(selected); clips.add(selected + 1, c); selected++
                    }) { Text("Move right") }
                }
                Text("Use Split in the bar below to cut at the playhead.", style = MaterialTheme.typography.bodySmall)
            }
            "Speed" -> {
                Text("Speed (preview + export)", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f, 1f, 1.5f, 2f).forEach { sp ->
                        FilterChip(selected = cur.speed == sp, label = { Text("${sp}x") },
                            onClick = { clips[selected] = cur.copy(speed = sp) })
                    }
                }
            }
            "Color" -> {
                Text("Color (applied on export)", style = MaterialTheme.typography.labelMedium)
                var br by remember(cur.id) { mutableFloatStateOf(cur.brightness) }
                Text("Brightness")
                Slider(br, { br = it }, valueRange = -0.5f..0.5f,
                    onValueChangeFinished = { clips[selected] = cur.copy(brightness = br) })
                var ct by remember(cur.id) { mutableFloatStateOf(cur.contrast) }
                Text("Contrast")
                Slider(ct, { ct = it }, valueRange = -0.5f..0.5f,
                    onValueChangeFinished = { clips[selected] = cur.copy(contrast = ct) })
                var sat by remember(cur.id) { mutableFloatStateOf(cur.saturation) }
                Text("Saturation")
                Slider(sat, { sat = it }, valueRange = -100f..100f,
                    onValueChangeFinished = { clips[selected] = cur.copy(saturation = sat) })
                Text("Colour wheels (shadows / midtones / highlights) are not built yet.",
                    style = MaterialTheme.typography.bodySmall)
            }
            "Effects" -> {
                Text("Effects (applied on export)", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = cur.grayscale, label = { Text("B&W") },
                        onClick = { clips[selected] = cur.copy(grayscale = !cur.grayscale) })
                    OutlinedButton(onClick = {
                        clips[selected] = cur.copy(rotation = (cur.rotation + 90) % 360)
                    }) { Text("Rotate ${cur.rotation}°") }
                }
                Text("Blur, glow and transitions are not built yet.", style = MaterialTheme.typography.bodySmall)
            }
            "Text" -> {
                OutlinedTextField(
                    value = cur.caption,
                    onValueChange = { clips[selected] = cur.copy(caption = it) },
                    label = { Text("Caption text (whole clip, export only)") },
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
            "Overlay" -> {
                Text("Overlay image / logo (bottom-right, export only)", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { overlayPicker.launch(arrayOf("image/*")) }) {
                        Text(if (cur.overlayUri == null) "Add image" else "Change image")
                    }
                    if (cur.overlayUri != null) {
                        OutlinedButton(onClick = { clips[selected] = cur.copy(overlayUri = null) }) { Text("Remove") }
                    }
                }
            }
            "Audio" -> {
                Text("Clip audio", style = MaterialTheme.typography.labelMedium)
                FilterChip(selected = cur.mute, label = { Text(if (cur.mute) "Muted" else "Mute this clip") },
                    onClick = { clips[selected] = cur.copy(mute = !cur.mute) })
                Text("Voiceover recording is in an Audio project.", style = MaterialTheme.typography.bodySmall)
            }
            "Auto Cut" -> {
                Button(enabled = !detecting, onClick = {
                    val target = cur
                    val idx = selected
                    detecting = true
                    status = "Detecting scene changes..."
                    scope.launch {
                        val cuts = detectScenes(ctx, target)
                        detecting = false
                        if (cuts.isEmpty()) {
                            status = "No scene changes found in this clip"
                        } else {
                            val bounds = listOf(target.startMs) + cuts + listOf(target.endMs)
                            val segs = (0 until bounds.size - 1).map { i ->
                                target.copy(id = nextId++, startMs = bounds[i], endMs = bounds[i + 1])
                            }
                            if (idx in clips.indices && clips[idx].id == target.id) {
                                clips.removeAt(idx); clips.addAll(idx, segs)
                                status = "Auto Cut: split into ${segs.size} clips"
                            }
                        }
                    }
                }) { Text(if (detecting) "Detecting..." else "Split at scene changes") }
            }
            "Quality" -> {
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
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Break Pro", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    IconButton(enabled = undoStack.isNotEmpty(), onClick = {
                        redoStack.add(lastSnap); restore(undoStack.removeAt(undoStack.lastIndex))
                    }) { Icon(Icons.Default.Undo, "Undo") }
                    IconButton(enabled = redoStack.isNotEmpty(), onClick = {
                        undoStack.add(lastSnap); restore(redoStack.removeAt(redoStack.lastIndex))
                    }) { Icon(Icons.Default.Redo, "Redo") }
                    Button(enabled = !exporting && clips.isNotEmpty(), onClick = { startExport() }) { Text("Export") }
                    Spacer(Modifier.width(8.dp))
                }
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                tools.forEach { t ->
                    val active = t.panel != null && tool == t.panel
                    Column(
                        Modifier.width(66.dp)
                            .clickable { if (t.panel != null) tool = t.panel else t.run?.invoke() }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(t.icon, t.label, tint = if (active) accent else muted)
                        Text(t.label, fontSize = 10.sp, color = if (active) accent else muted)
                    }
                }
            }
        }
    ) { pad ->
        if (clips.isEmpty()) {
            Box(Modifier.padding(pad).padding(12.dp)) {
                Text("No video in this project. Go back, tap Import media and pick videos.")
            }
            return@Scaffold
        }
        if (wide) {
            Row(
                Modifier.padding(pad).fillMaxSize().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    Modifier.weight(1.4f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) { previewBlock(); timelineBlock() }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) { panelBlock() }
            }
        } else {
            Column(
                Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) { previewBlock(); timelineBlock(); panelBlock() }
        }
    }
}

/** One button in the bottom tool bar. panel != null opens that panel; otherwise [run] is an immediate action. */
private class ToolBtn(
    val label: String,
    val icon: ImageVector,
    val panel: String?,
    val run: (() -> Unit)? = null
)

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

/**
 * Builds a transparent overlay bitmap with the same aspect ratio as the (rotated) video frame and the
 * chosen image drawn bottom-right at ~22% width, so the full-frame BitmapOverlay does not stretch it.
 */
private fun overlayBitmapFor(ctx: Context, c: Clip): android.graphics.Bitmap? {
    try {
        var vw = 1280
        var vh = 720
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, c.uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val total = (rot + c.rotation) % 360
            if (total == 90 || total == 270) { vw = h; vh = w } else { vw = w; vh = h }
        } finally {
            r.release()
        }
        val scale = 1280f / maxOf(vw, vh)
        val cw = (vw * scale).toInt().coerceAtLeast(2)
        val ch = (vh * scale).toInt().coerceAtLeast(2)
        val uri = c.overlayUri ?: return null
        val src = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            ?: return null
        val out = android.graphics.Bitmap.createBitmap(cw, ch, android.graphics.Bitmap.Config.ARGB_8888)
        val tw = cw * 0.22f
        val th = tw * src.height / src.width
        val m = cw * 0.03f
        android.graphics.Canvas(out).drawBitmap(
            src, null, android.graphics.RectF(cw - m - tw, ch - m - th, cw - m, ch - m),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        )
        src.recycle()
        return out
    } catch (_: Throwable) {
        return null
    }
}

/**
 * Scene detection: samples a tiny frame every 500 ms (nearest keyframe, for speed) and reports the times where
 * the average luminance difference to the previous sample is large. Cuts are at least 1.5 s apart.
 */
private suspend fun detectScenes(ctx: Context, c: Clip): List<Long> = withContext(Dispatchers.Default) {
    val cuts = mutableListOf<Long>()
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, c.uri)
        var prev: IntArray? = null
        var t = c.startMs
        var lastCut = c.startMs
        while (t < c.endMs) {
            val bmp = r.getScaledFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 32, 32)
            if (bmp != null) {
                val px = IntArray(32 * 32)
                bmp.getPixels(px, 0, 32, 0, 0, 32, 32)
                bmp.recycle()
                val lum = IntArray(px.size) {
                    val p = px[it]
                    (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
                }
                val pv = prev
                if (pv != null) {
                    var d = 0L
                    for (i in lum.indices) d += abs(lum[i] - pv[i])
                    if (d / lum.size > 28 && t - lastCut >= 1500) { cuts.add(t); lastCut = t }
                }
                prev = lum
            }
            t += 500
        }
    } catch (_: Throwable) {
    } finally {
        r.release()
    }
    cuts
}
