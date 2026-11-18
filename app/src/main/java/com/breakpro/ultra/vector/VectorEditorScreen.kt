package com.breakpro.ultra.vector

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.drawscope.Fill

/** Artboard is 1000 x 1000 units; shape coordinates are in those units. */
private const val ART = 1000f

private enum class Kind { RECT, ELLIPSE, TRIANGLE, TEXT }

private data class Shape(
    val id: Int, val kind: Kind,
    val x: Float, val y: Float, val w: Float, val h: Float,
    val color: Int, val text: String = "Logo"
)

private val palette = listOf(
    0xFFFF5A36.toInt(), 0xFF2D6CDF.toInt(), 0xFF22A559.toInt(), 0xFFF5C518.toInt(),
    0xFF8E44AD.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF888888.toInt()
)

private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
private fun n(v: Float) = "%.1f".format(java.util.Locale.US, v)

internal fun toSvg(shapes: List<ShapeData>, bg: Int): String = buildString {
    append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1000\" height=\"1000\" viewBox=\"0 0 1000 1000\">\n")
    append("<rect width=\"1000\" height=\"1000\" fill=\"${hex(bg)}\"/>\n")
    shapes.forEach { s ->
        when (s.kind) {
            "RECT" -> append("<rect x=\"${n(s.x)}\" y=\"${n(s.y)}\" width=\"${n(s.w)}\" height=\"${n(s.h)}\" fill=\"${hex(s.color)}\"/>\n")
            "ELLIPSE" -> append("<ellipse cx=\"${n(s.x + s.w / 2)}\" cy=\"${n(s.y + s.h / 2)}\" rx=\"${n(s.w / 2)}\" ry=\"${n(s.h / 2)}\" fill=\"${hex(s.color)}\"/>\n")
            "TRIANGLE" -> append("<polygon points=\"${n(s.x + s.w / 2)},${n(s.y)} ${n(s.x + s.w)},${n(s.y + s.h)} ${n(s.x)},${n(s.y + s.h)}\" fill=\"${hex(s.color)}\"/>\n")
            else -> append("<text x=\"${n(s.x)}\" y=\"${n(s.y + s.h * 0.8f)}\" font-size=\"${n(s.h)}\" font-family=\"sans-serif\" font-weight=\"bold\" fill=\"${hex(s.color)}\">${esc(s.text)}</text>\n")
        }
    }
    append("</svg>\n")
}

/** Plain data copy used by [toSvg] so it can be unit-tested without Compose. */
internal data class ShapeData(val kind: String, val x: Float, val y: Float, val w: Float, val h: Float, val color: Int, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VectorEditorScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val shapes = remember {
        mutableStateListOf(
            Shape(1, Kind.ELLIPSE, 300f, 250f, 400f, 400f, 0xFFFF5A36.toInt()),
            Shape(2, Kind.TEXT, 250f, 680f, 500f, 120f, 0xFF000000.toInt(), "BRAND")
        )
    }
    var selected by remember { mutableIntStateOf(0) }
    var nextId by remember { mutableIntStateOf(3) }
    var bg by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
    var status by remember { mutableStateOf("") }
    val measurer = rememberTextMeasurer()
    val cur = shapes.getOrNull(selected)

    Scaffold(topBar = {
        TopAppBar(title = { Text("Vector / Logo Maker") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Canvas(
                Modifier.fillMaxWidth().aspectRatio(1f).border(1.dp, Color.Gray)
                    .pointerInput(Unit) {
                        val k = ART / size.width
                        var dragging = -1
                        detectDragGestures(
                            onDragStart = { p ->
                                val px = p.x * k; val py = p.y * k
                                dragging = shapes.indexOfLast {
                                    px >= it.x && px <= it.x + it.w && py >= it.y && py <= it.y + it.h
                                }
                                if (dragging >= 0) selected = dragging
                            },
                            onDrag = { change, d ->
                                change.consume()
                                if (dragging in shapes.indices) {
                                    val s = shapes[dragging]
                                    shapes[dragging] = s.copy(x = s.x + d.x * k, y = s.y + d.y * k)
                                }
                            }
                        )
                    }
            ) {
                val k = size.width / ART
                drawRect(Color(bg))
                shapes.forEachIndexed { i, s ->
                    val c = Color(s.color)
                    when (s.kind) {
                        Kind.RECT -> drawRect(c, Offset(s.x * k, s.y * k), Size(s.w * k, s.h * k), style = Fill)
                        Kind.ELLIPSE -> drawOval(c, Offset(s.x * k, s.y * k), Size(s.w * k, s.h * k), style = Fill)
                        Kind.TRIANGLE -> drawPath(Path().apply {
                            moveTo((s.x + s.w / 2) * k, s.y * k)
                            lineTo((s.x + s.w) * k, (s.y + s.h) * k)
                            lineTo(s.x * k, (s.y + s.h) * k)
                            close()
                        }, c)
                        Kind.TEXT -> drawText(
                            measurer, s.text, Offset(s.x * k, s.y * k),
                            style = TextStyle(color = c, fontWeight = FontWeight.Bold,
                                fontSize = TextUnit(s.h * k * 0.75f / density, androidx.compose.ui.unit.TextUnitType.Sp))
                        )
                    }
                    if (i == selected) drawRect(
                        Color(0xFF00AAFF), Offset(s.x * k, s.y * k), Size(s.w * k, s.h * k),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2f)
                    )
                }
            }
            Text("Drag a shape to move it. Tap a layer below to select.", style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { shapes.add(Shape(nextId++, Kind.RECT, 100f, 100f, 300f, 300f, palette[1])); selected = shapes.size - 1 }) { Text("Rect") }
                OutlinedButton(onClick = { shapes.add(Shape(nextId++, Kind.ELLIPSE, 100f, 100f, 300f, 300f, palette[2])); selected = shapes.size - 1 }) { Text("Ellipse") }
                OutlinedButton(onClick = { shapes.add(Shape(nextId++, Kind.TRIANGLE, 100f, 100f, 300f, 300f, palette[3])); selected = shapes.size - 1 }) { Text("Triangle") }
                OutlinedButton(onClick = { shapes.add(Shape(nextId++, Kind.TEXT, 100f, 100f, 400f, 100f, palette[5], "Text")); selected = shapes.size - 1 }) { Text("Text") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shapes.forEachIndexed { i, s ->
                    FilterChip(selected = i == selected, onClick = { selected = i },
                        label = { Text("${i + 1} ${s.kind.name.lowercase()}") })
                }
            }

            if (cur != null) {
                if (cur.kind == Kind.TEXT) {
                    OutlinedTextField(cur.text, { shapes[selected] = cur.copy(text = it) },
                        label = { Text("Text") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Text("Width")
                Slider(cur.w, { shapes[selected] = cur.copy(w = it) }, valueRange = 40f..1000f)
                Text("Height")
                Slider(cur.h, { shapes[selected] = cur.copy(h = it) }, valueRange = 40f..1000f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.forEach { c ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                            .border(1.dp, Color.Gray, CircleShape)
                            .clickable { shapes[selected] = cur.copy(color = c) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(enabled = selected < shapes.size - 1, onClick = {
                        val s = shapes.removeAt(selected); shapes.add(selected + 1, s); selected++
                    }) { Text("Bring forward") }
                    OutlinedButton(enabled = selected > 0, onClick = {
                        val s = shapes.removeAt(selected); shapes.add(selected - 1, s); selected--
                    }) { Text("Send back") }
                    OutlinedButton(onClick = {
                        shapes.add(cur.copy(id = nextId++, x = cur.x + 40f, y = cur.y + 40f)); selected = shapes.size - 1
                    }) { Text("Copy") }
                    OutlinedButton(onClick = { shapes.removeAt(selected); selected = (selected - 1).coerceAtLeast(0) }) { Text("Delete") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        shapes[selected] = cur.copy(x = (ART - cur.w) / 2f, y = (ART - cur.h) / 2f)
                    }) { Text("Center on artboard") }
                }
            }
            Text("Background", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                palette.forEach { c ->
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                        .border(1.dp, Color.Gray, CircleShape).clickable { bg = c })
                }
            }
            Button(onClick = {
                val data = shapes.map { ShapeData(it.kind.name, it.x, it.y, it.w, it.h, it.color, it.text) }
                status = if (saveSvg(ctx, toSvg(data, bg))) "Saved SVG to Documents/BreakPro" else "Save failed"
            }) { Text("Export SVG") }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
            Text("Basic shapes + text only: no pen/bezier tool, boolean ops, gradients or SVG import yet.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun saveSvg(ctx: Context, svg: String): Boolean = try {
    val values = ContentValues().apply {
        put(MediaStore.Files.FileColumns.DISPLAY_NAME, "BreakPro_${System.currentTimeMillis()}.svg")
        put(MediaStore.Files.FileColumns.MIME_TYPE, "image/svg+xml")
        put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/BreakPro")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)!!
    ctx.contentResolver.openOutputStream(uri)!!.use { it.write(svg.toByteArray(Charsets.UTF_8)) }
    com.breakpro.ultra.exportlog.ExportLog.add(ctx, "SVG", "vector 1000x1000", svg.length.toLong())
    true
} catch (_: Throwable) { false }
