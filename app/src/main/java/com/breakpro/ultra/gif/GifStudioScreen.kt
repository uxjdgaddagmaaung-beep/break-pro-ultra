package com.breakpro.ultra.gif

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.breakpro.ultra.data.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun decodeScaled(ctx: Context, uri: Uri, w: Int, h: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
    val src = ctx.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    src?.let {
        // centre-crop into w x h on a white background
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.WHITE)
        val scale = maxOf(w.toFloat() / it.width, h.toFloat() / it.height)
        val sw = w / scale; val sh = h / scale
        val sx = (it.width - sw) / 2f; val sy = (it.height - sh) / 2f
        c.drawBitmap(it, Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()),
            Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        it.recycle()
        out
    }
} catch (_: Throwable) { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GifStudioScreen(media: List<MediaItemEntity>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val frames = remember { mutableStateListOf<MediaItemEntity>() }
    var delayMs by remember { mutableFloatStateOf(300f) }
    var width by remember { mutableIntStateOf(480) }
    var aspect by remember { mutableStateOf("1:1") }
    var busy by remember { mutableStateOf(false) }
    var pingPong by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(media) {
        media.filter { it.mimeType.startsWith("image/") }.forEach { m ->
            if (frames.none { it.id == m.id }) frames.add(m)
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("GIF Studio") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (frames.isEmpty()) {
                Text("No images in this project. Go back, tap Import media and pick 2 or more images.")
                return@Column
            }
            Text("Frames (${frames.size})", style = MaterialTheme.typography.labelMedium)
            frames.forEachIndexed { i, m ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${i + 1}. ${m.displayName}", Modifier.weight(1f), maxLines = 1)
                    TextButton(enabled = i > 0, onClick = { val f = frames.removeAt(i); frames.add(i - 1, f) }) { Text("Up") }
                    TextButton(enabled = i < frames.size - 1, onClick = { val f = frames.removeAt(i); frames.add(i + 1, f) }) { Text("Down") }
                    TextButton(onClick = { frames.removeAt(i) }) { Text("Remove") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { frames.reverse() }) { Text("Reverse order") }
                FilterChip(selected = pingPong, onClick = { pingPong = !pingPong }, label = { Text("Ping-pong") })
            }
            Text("Frame delay: ${delayMs.toInt()} ms")
            Slider(delayMs, { delayMs = it }, valueRange = 50f..1500f)
            Text("Width", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(240, 360, 480, 720).forEach { w ->
                    FilterChip(selected = width == w, onClick = { width = w }, label = { Text("$w px") })
                }
            }
            Text("Aspect", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("1:1", "4:3", "16:9", "9:16").forEach { a ->
                    FilterChip(selected = aspect == a, onClick = { aspect = a }, label = { Text(a) })
                }
            }
            Button(enabled = !busy && frames.size >= 2, onClick = {
                busy = true; progress = 0f; status = "Encoding…"
                val base = frames.toList()
                val list = if (pingPong && base.size > 2) base + base.reversed().drop(1).dropLast(1) else base
                val d = delayMs.toInt(); val w = width
                val (aw, ah) = aspect.split(":").map { it.toInt() }
                val h = (w * ah / aw)
                scope.launch {
                    val ok = withContext(Dispatchers.Default) {
                        encodeToGallery(ctx, list, w, h, d) { progress = it }
                    }
                    busy = false
                    status = if (ok) "Saved GIF to Pictures/BreakPro" else "GIF export failed"
                }
            }) { Text("Export GIF") }
            if (frames.size < 2) Text("Add at least 2 images.", style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
            Text("Note: fixed 216-colour palette, so photos look posterised.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun encodeToGallery(
    ctx: Context, items: List<MediaItemEntity>, w: Int, h: Int, delayMs: Int, onProgress: (Float) -> Unit
): Boolean = try {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "BreakPro_${System.currentTimeMillis()}.gif")
        put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BreakPro")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
    ctx.contentResolver.openOutputStream(uri)!!.buffered().use { os ->
        val enc = GifEncoder(os, w, h)
        enc.start()
        items.forEachIndexed { i, m ->
            val bmp = decodeScaled(ctx, Uri.parse(m.uri), w, h) ?: throw IllegalStateException("decode failed")
            enc.addFrame(bmp, delayMs)
            bmp.recycle()
            onProgress((i + 1f) / items.size)
        }
        enc.finish()
    }
    com.breakpro.ultra.exportlog.ExportLog.add(ctx, "GIF", "${items.size} frames, ${w}x${h}")
    true
} catch (_: Throwable) { false }
