package com.breakpro.ultra.photo

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.breakpro.ultra.data.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.ColorFilter as ComposeColorFilter
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix

private val filters = listOf("None", "B&W", "Sepia", "Invert")

/** brightness: -100..100, contrast: 0.5..2, saturation: 0..2, filter: index into [filters]. */
private fun buildMatrix(brightness: Float, contrast: Float, saturation: Float, filter: Int, temp: Float, tint: Float): FloatArray {
    val m = ColorMatrix().apply { setSaturation(saturation) }
    val t = (1 - contrast) * 128f + brightness
    m.postConcat(ColorMatrix(floatArrayOf(
        contrast, 0f, 0f, 0f, t,
        0f, contrast, 0f, 0f, t,
        0f, 0f, contrast, 0f, t,
        0f, 0f, 0f, 1f, 0f
    )))
    if (temp != 0f || tint != 0f) m.postConcat(ColorMatrix(floatArrayOf(
        1f + temp * 0.3f, 0f, 0f, 0f, 0f,
        0f, 1f - tint * 0.3f, 0f, 0f, 0f,
        0f, 0f, 1f - temp * 0.3f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )))
    when (filter) {
        1 -> m.postConcat(ColorMatrix().apply { setSaturation(0f) })
        2 -> m.postConcat(ColorMatrix(floatArrayOf(
            .393f, .769f, .189f, 0f, 0f,
            .349f, .686f, .168f, 0f, 0f,
            .272f, .534f, .131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))
        3 -> m.postConcat(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )))
    }
    return m.array
}

private suspend fun loadBitmap(ctx: Context, uri: Uri, maxSide: Int = 2048): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val orientation = ctx.contentResolver.openInputStream(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
            } ?: 1
            val fix = Matrix()
            when (orientation) {
                2 -> fix.postScale(-1f, 1f)
                3 -> fix.postRotate(180f)
                4 -> fix.postScale(1f, -1f)
                5 -> { fix.postRotate(90f); fix.postScale(-1f, 1f) }
                6 -> fix.postRotate(90f)
                7 -> { fix.postRotate(270f); fix.postScale(-1f, 1f) }
                8 -> fix.postRotate(270f)
            }
            if (decoded != null && !fix.isIdentity)
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, fix, true)
            else decoded
        } catch (_: Throwable) {
            null
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoEditorScreen(media: List<MediaItemEntity>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val images = media.filter { it.mimeType.startsWith("image/") }
    var pick by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var filter by remember { mutableIntStateOf(0) }
    var rotation by remember { mutableIntStateOf(0) }
    var cropIdx by remember { mutableIntStateOf(0) }
    var flip by remember { mutableStateOf(false) }
    var temp by remember { mutableFloatStateOf(0f) }
    var tint by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }

    val current = images.getOrNull(pick)
    LaunchedEffect(current?.uri) {
        bitmap = current?.let { loadBitmap(ctx, Uri.parse(it.uri)) }
        brightness = 0f; contrast = 1f; saturation = 1f; filter = 0; rotation = 0; cropIdx = 0; flip = false; temp = 0f; tint = 0f
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Photo Editor") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val bmp = bitmap
            if (bmp == null) {
                Text("No image in this project. Go back, tap Import media and pick a photo.")
                return@Column
            }
            if (images.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    images.forEachIndexed { i, m ->
                        FilterChip(selected = pick == i, onClick = { pick = i },
                            label = { Text(m.displayName.take(12)) })
                    }
                }
            }
            val matrix = buildMatrix(brightness, contrast, saturation, filter, temp, tint)
            val work = remember(bmp, cropIdx, flip) { cropAndFlip(bmp, cropRatios[cropIdx].second, flip) }
            Image(
                bitmap = work.asImageBitmap(),
                contentDescription = null,
                colorFilter = ComposeColorFilter.colorMatrix(ComposeColorMatrix(matrix)),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(300.dp).rotate(rotation.toFloat())
            )
            val hist = remember(work, brightness, contrast, saturation, filter, temp, tint) { histogram(work, matrix) }
            val histMax = (hist.maxOrNull() ?: 1).coerceAtLeast(1)
            Text("Histogram (brightness)", style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(60.dp)) {
                val bw = size.width / 256f
                for (i in 0 until 256) {
                    val bh = size.height * hist[i] / histMax
                    drawRect(
                        color = androidx.compose.ui.graphics.Color.Gray,
                        topLeft = androidx.compose.ui.geometry.Offset(i * bw, size.height - bh),
                        size = androidx.compose.ui.geometry.Size(bw + 0.5f, bh)
                    )
                }
            }
            Text("Crop", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cropRatios.forEachIndexed { i, (n, _) ->
                    FilterChip(selected = cropIdx == i, onClick = { cropIdx = i }, label = { Text(n) })
                }
                FilterChip(selected = flip, onClick = { flip = !flip }, label = { Text("Flip") })
            }
            Text("Temperature (cool - warm)")
            Slider(temp, { temp = it }, valueRange = -1f..1f)
            Text("Tint (green - magenta)")
            Slider(tint, { tint = it }, valueRange = -1f..1f)
            Text("Brightness")
            Slider(brightness, { brightness = it }, valueRange = -100f..100f)
            Text("Contrast")
            Slider(contrast, { contrast = it }, valueRange = 0.5f..2f)
            Text("Saturation")
            Slider(saturation, { saturation = it }, valueRange = 0f..2f)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                filters.forEachIndexed { i, n ->
                    FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(n) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { rotation = (rotation + 90) % 360 }) { Text("Rotate 90°") }
                OutlinedButton(onClick = {
                    brightness = 0f; contrast = 1f; saturation = 1f; filter = 0; rotation = 0; cropIdx = 0; flip = false; temp = 0f; tint = 0f
                }) { Text("Reset") }
                Button(onClick = {
                    status = if (exportJpg(ctx, work, matrix, rotation)) "Saved to Pictures/BreakPro"
                    else "Save failed"
                }) { Text("Save JPG") }
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun exportJpg(ctx: Context, src: Bitmap, matrix: FloatArray, rotation: Int): Boolean = try {
    val rot = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, rot, true)
    val out = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
    Canvas(out).drawBitmap(rotated, 0f, 0f, paint)
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "BreakPro_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BreakPro")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
    ctx.contentResolver.openOutputStream(uri)!!.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    com.breakpro.ultra.exportlog.ExportLog.add(ctx, "JPG", "${out.width}x${out.height}")
    true
} catch (_: Throwable) {
    false
}

private val cropRatios: List<Pair<String, Float?>> =
    listOf("Free" to null, "1:1" to 1f, "4:5" to 0.8f, "16:9" to (16f / 9f), "9:16" to (9f / 16f))

/** Centre-crops [src] to [ratio] (width/height) when given, then optionally mirrors it horizontally. */
private fun cropAndFlip(src: Bitmap, ratio: Float?, flip: Boolean): Bitmap {
    var x = 0; var y = 0; var w = src.width; var h = src.height
    if (ratio != null) {
        val cur = w.toFloat() / h
        if (cur > ratio) { w = (h * ratio).toInt().coerceAtLeast(1); x = (src.width - w) / 2 }
        else { h = (w / ratio).toInt().coerceAtLeast(1); y = (src.height - h) / 2 }
    }
    if (x == 0 && y == 0 && w == src.width && h == src.height && !flip) return src
    val m = Matrix().apply { if (flip) postScale(-1f, 1f) }
    return Bitmap.createBitmap(src, x, y, w, h, m, true)
}

/** 256-bin luminance histogram of [src] with [matrix] applied, computed on a 128px thumbnail. */
private fun histogram(src: Bitmap, matrix: FloatArray): IntArray {
    val s = 128f / maxOf(src.width, src.height)
    val w = (src.width * s).toInt().coerceAtLeast(1)
    val h = (src.height * s).toInt().coerceAtLeast(1)
    val t = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
    Canvas(t).drawBitmap(src, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
    val px = IntArray(w * h)
    t.getPixels(px, 0, w, 0, 0, w, h)
    val bins = IntArray(256)
    for (c in px) {
        val l = (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)).toInt()
        bins[l.coerceIn(0, 255)]++
    }
    t.recycle()
    return bins
}
