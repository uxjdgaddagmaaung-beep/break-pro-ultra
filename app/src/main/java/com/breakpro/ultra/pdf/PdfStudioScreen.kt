package com.breakpro.ultra.pdf

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
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

/** A4 at 72 dpi (PDF points). */
private const val A4_W = 595
private const val A4_H = 842

private fun decode(ctx: Context, uri: Uri, maxSide: Int = 2000): Bitmap? = try {
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
    var s = 1
    while (b.outWidth / s > maxSide || b.outHeight / s > maxSide) s *= 2
    ctx.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
    }
} catch (_: Throwable) { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfStudioScreen(media: List<MediaItemEntity>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = remember { mutableStateListOf<MediaItemEntity>() }
    var fitPage by remember { mutableStateOf(true) }   // true: A4 pages, false: page = image size
    var margin by remember { mutableFloatStateOf(24f) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(media) {
        media.filter { it.mimeType.startsWith("image/") }.forEach { m ->
            if (pages.none { it.id == m.id }) pages.add(m)
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("PDF Studio") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (pages.isEmpty()) {
                Text("No images in this project. Go back, tap Import media and pick images (one per page).")
                return@Column
            }
            Text("Pages (${pages.size})", style = MaterialTheme.typography.labelMedium)
            pages.forEachIndexed { i, m ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${i + 1}. ${m.displayName}", Modifier.weight(1f), maxLines = 1)
                    TextButton(enabled = i > 0, onClick = { val f = pages.removeAt(i); pages.add(i - 1, f) }) { Text("Up") }
                    TextButton(enabled = i < pages.size - 1, onClick = { val f = pages.removeAt(i); pages.add(i + 1, f) }) { Text("Down") }
                    TextButton(onClick = { pages.removeAt(i) }) { Text("Remove") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = fitPage, onClick = { fitPage = true }, label = { Text("A4 pages") })
                FilterChip(selected = !fitPage, onClick = { fitPage = false }, label = { Text("Page = image size") })
            }
            if (fitPage) {
                Text("Margin: ${margin.toInt()} pt")
                Slider(margin, { margin = it }, valueRange = 0f..80f)
            }
            Button(enabled = !busy, onClick = {
                busy = true; status = "Creating PDF…"
                val list = pages.toList(); val fit = fitPage; val m = margin
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { createPdf(ctx, list, fit, m) }
                    busy = false
                    status = if (ok) "Saved PDF to Documents/BreakPro" else "PDF export failed"
                }
            }) { Text("Export PDF") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
            Text("Images only for now: no merging/splitting of existing PDFs or text editing.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun createPdf(ctx: Context, items: List<MediaItemEntity>, fitPage: Boolean, margin: Float): Boolean = try {
    val doc = PdfDocument()
    items.forEachIndexed { i, m ->
        val bmp = decode(ctx, Uri.parse(m.uri)) ?: throw IllegalStateException("decode failed")
        val pw = if (fitPage) A4_W else bmp.width
        val ph = if (fitPage) A4_H else bmp.height
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
        val c = page.canvas
        c.drawColor(Color.WHITE)
        val m2 = if (fitPage) margin else 0f
        val availW = pw - 2 * m2; val availH = ph - 2 * m2
        val scale = minOf(availW / bmp.width, availH / bmp.height)
        val dw = bmp.width * scale; val dh = bmp.height * scale
        val left = (pw - dw) / 2f; val top = (ph - dh) / 2f
        c.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        doc.finishPage(page)
        bmp.recycle()
    }
    val values = ContentValues().apply {
        put(MediaStore.Files.FileColumns.DISPLAY_NAME, "BreakPro_${System.currentTimeMillis()}.pdf")
        put(MediaStore.Files.FileColumns.MIME_TYPE, "application/pdf")
        put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/BreakPro")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)!!
    ctx.contentResolver.openOutputStream(uri)!!.use { doc.writeTo(it) }
    doc.close()
    com.breakpro.ultra.exportlog.ExportLog.add(ctx, "PDF", "${items.size} pages")
    true
} catch (_: Throwable) { false }
