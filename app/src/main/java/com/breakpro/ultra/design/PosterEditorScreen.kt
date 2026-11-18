package com.breakpro.ultra.design

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt

private data class TextLayer(
    val id: Int,
    val text: String,
    val x: Float,          // 0..1 of canvas width (top-left of text)
    val y: Float,          // 0..1 of canvas height
    val size: Float,       // fraction of canvas width, e.g. 0.08
    val color: Int,
    val bold: Boolean = true
)

private data class Snap(val layers: List<TextLayer>, val bg: Int, val ratioIdx: Int)

private val palette = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF5A36.toInt(), 0xFF2D6CDF.toInt(),
    0xFF22A559.toInt(), 0xFFF5C518.toInt(), 0xFF8E44AD.toInt(), 0xFF1E1E2C.toInt()
)
private val ratios = listOf("1:1" to (1f / 1f), "4:5" to (4f / 5f), "9:16" to (9f / 16f), "16:9" to (16f / 9f))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosterEditorScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val layers = remember { mutableStateListOf(TextLayer(1, "YOUR TITLE", 0.1f, 0.4f, 0.1f, 0xFFFFFFFF.toInt())) }
    var selected by remember { mutableIntStateOf(0) }
    var nextId by remember { mutableIntStateOf(2) }
    var bg by remember { mutableIntStateOf(0xFF1E1E2C.toInt()) }
    var ratioIdx by remember { mutableIntStateOf(2) }
    var status by remember { mutableStateOf("") }
    var canvasPx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = LocalDensity.current
    val cur = layers.getOrNull(selected)

    // Undo/redo: snapshot after edits settle (400 ms), so slider drags count as one step.
    val undoStack = remember { mutableStateListOf<Snap>() }
    val redoStack = remember { mutableStateListOf<Snap>() }
    var last by remember { mutableStateOf(Snap(layers.toList(), bg, ratioIdx)) }
    val snap = Snap(layers.toList(), bg, ratioIdx)
    LaunchedEffect(snap) {
        kotlinx.coroutines.delay(400)
        if (snap != last) {
            undoStack.add(last); if (undoStack.size > 50) undoStack.removeAt(0)
            redoStack.clear(); last = snap
        }
    }
    fun restore(s: Snap) {
        layers.clear(); layers.addAll(s.layers); bg = s.bg; ratioIdx = s.ratioIdx
        selected = selected.coerceIn(0, (layers.size - 1).coerceAtLeast(0)); last = s
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Poster Maker") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ratios.forEachIndexed { i, (n, _) ->
                    FilterChip(selected = ratioIdx == i, onClick = { ratioIdx = i }, label = { Text(n) })
                }
            }

            // Design canvas: drag a text layer to move it.
            val ratio = ratios[ratioIdx].second
            Box(
                Modifier
                    .fillMaxWidth(if (ratio < 1f) 0.6f else 1f)
                    .aspectRatio(ratio)
                    .background(Color(bg))
                    .onSizeChanged { canvasPx = it }
            ) {
                layers.forEachIndexed { i, l ->
                    val w = canvasPx.width.toFloat().coerceAtLeast(1f)
                    val h = canvasPx.height.toFloat().coerceAtLeast(1f)
                    Text(
                        l.text,
                        color = Color(l.color),
                        fontWeight = if (l.bold) FontWeight.Bold else FontWeight.Normal,
                        fontSize = with(density) { (l.size * w).toSp() },
                        modifier = Modifier
                            .offset { IntOffset((l.x * w).roundToInt(), (l.y * h).roundToInt()) }
                            .pointerInput(l.id, canvasPx) {
                                detectDragGestures(onDragStart = { selected = i }) { change, drag ->
                                    change.consume()
                                    val idx = layers.indexOfFirst { it.id == l.id }
                                    if (idx >= 0) {
                                        val c = layers[idx]
                                        layers[idx] = c.copy(
                                            x = (c.x + drag.x / w).coerceIn(0f, 0.95f),
                                            y = (c.y + drag.y / h).coerceIn(0f, 0.95f)
                                        )
                                    }
                                }
                            }
                    )
                }
            }

            Text("Background", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                palette.forEach { c ->
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Color(c)).clickable { bg = c })
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = undoStack.isNotEmpty(), onClick = {
                    redoStack.add(last); restore(undoStack.removeAt(undoStack.lastIndex))
                }) { Text("Undo") }
                OutlinedButton(enabled = redoStack.isNotEmpty(), onClick = {
                    undoStack.add(last); restore(redoStack.removeAt(redoStack.lastIndex))
                }) { Text("Redo") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    layers.add(TextLayer(nextId++, "New text", 0.1f, 0.1f + 0.1f * layers.size % 0.8f, 0.07f, 0xFFFFFFFF.toInt()))
                    selected = layers.size - 1
                }) { Text("Add text") }
                OutlinedButton(enabled = cur != null, onClick = {
                    cur?.let {
                        layers.add(it.copy(id = nextId++, x = (it.x + 0.03f).coerceAtMost(0.9f), y = (it.y + 0.03f).coerceAtMost(0.9f)))
                        selected = layers.size - 1
                    }
                }) { Text("Copy layer") }
                OutlinedButton(enabled = cur != null, onClick = {
                    layers.removeAt(selected); selected = (selected - 1).coerceAtLeast(0)
                }) { Text("Delete layer") }
            }

            if (cur != null) {
                Text("Layer ${selected + 1} of ${layers.size} (drag text on canvas to move)",
                    style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = cur.text, onValueChange = { layers[selected] = cur.copy(text = it) },
                    label = { Text("Text") }, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val p = Paint().apply {
                            textSize = cur.size * 1000f
                            typeface = if (cur.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        }
                        val wFrac = p.measureText(cur.text) / 1000f
                        layers[selected] = cur.copy(x = ((1f - wFrac) / 2f).coerceIn(0f, 0.95f))
                    }) { Text("Center H") }
                    OutlinedButton(onClick = {
                        val p = Paint().apply {
                            textSize = cur.size * 1000f
                            typeface = if (cur.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        }
                        val hFrac = (p.fontMetrics.descent - p.fontMetrics.ascent) / (1000f / ratio)
                        layers[selected] = cur.copy(y = ((1f - hFrac) / 2f).coerceIn(0f, 0.95f))
                    }) { Text("Center V") }
                }
                Text("Size")
                Slider(cur.size, { layers[selected] = cur.copy(size = it) }, valueRange = 0.03f..0.3f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.forEach { c ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                            .clickable { layers[selected] = cur.copy(color = c) })
                    }
                }
                FilterChip(selected = cur.bold, label = { Text("Bold") },
                    onClick = { layers[selected] = cur.copy(bold = !cur.bold) })
            }

            Button(onClick = {
                status = if (exportPng(ctx, layers.toList(), bg, ratio)) "Saved PNG to Pictures/BreakPro"
                else "Save failed"
            }) { Text("Export PNG") }
            Button(onClick = {
                status = if (exportPdf(ctx, layers.toList(), bg, ratio)) "Saved PDF to Documents/BreakPro"
                else "PDF save failed"
            }) { Text("Export PDF") }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Renders the design at 1080px wide with the same layout rules as the on-screen canvas. */
private fun exportPng(ctx: Context, layers: List<TextLayer>, bg: Int, ratio: Float): Boolean = try {
    val w = 1080
    val h = (w / ratio).roundToInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(bg)
    layers.forEach { l ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = l.color
            textSize = l.size * w
            typeface = if (l.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        // Compose positions the text box top-left; Canvas draws from the baseline.
        canvas.drawText(l.text, l.x * w, l.y * h - paint.fontMetrics.ascent, paint)
    }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "BreakPro_poster_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BreakPro")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
    ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    com.breakpro.ultra.exportlog.ExportLog.add(ctx, "PNG", "poster ${w}x${h}")
    true
} catch (_: Throwable) { false }

/** One-page PDF whose page size equals the 1080px-wide render (points), image drawn 1:1. */
private fun exportPdf(ctx: Context, layers: List<TextLayer>, bg: Int, ratio: Float): Boolean = try {
    val w = 1080
    val h = (w / ratio).roundToInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(bg)
    layers.forEach { l ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = l.color
            textSize = l.size * w
            typeface = if (l.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        canvas.drawText(l.text, l.x * w, l.y * h - paint.fontMetrics.ascent, paint)
    }
    val doc = android.graphics.pdf.PdfDocument()
    val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, 1).create())
    page.canvas.drawBitmap(bmp, 0f, 0f, null)
    doc.finishPage(page)
    val values = ContentValues().apply {
        put(MediaStore.Files.FileColumns.DISPLAY_NAME, "BreakPro_poster_${System.currentTimeMillis()}.pdf")
        put(MediaStore.Files.FileColumns.MIME_TYPE, "application/pdf")
        put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/BreakPro")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)!!
    ctx.contentResolver.openOutputStream(uri)!!.use { doc.writeTo(it) }
    doc.close()
    com.breakpro.ultra.exportlog.ExportLog.add(ctx, "PDF", "poster ${w}x${h}")
    true
} catch (_: Throwable) { false }
