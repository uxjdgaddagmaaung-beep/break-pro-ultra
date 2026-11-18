package com.breakpro.ultra.assets

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetLibraryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var packs by remember { mutableStateOf(AssetManager.list(ctx)) }
    var favTick by remember { mutableIntStateOf(0) }
    var onlyFavs by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            status = try {
                val p = AssetManager.install(ctx, uri)
                packs = AssetManager.list(ctx)
                "Installed '${p.name}' (${p.assets.size} assets)"
            } catch (t: Throwable) { "Install failed: ${t.message}" }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Asset Library") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) {
                        Text("Install ZIP pack")
                    }
                    FilterChip(selected = onlyFavs, onClick = { onlyFavs = !onlyFavs }, label = { Text("Favorites") })
                }
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, label = { Text("Search name or type") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Packs need manifest.json with a license (format documented in AssetManager.kt). " +
                    "Online marketplace and applying assets inside editors are not implemented yet.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (packs.isEmpty()) item { Text("No packs installed.") }
            packs.forEach { pack ->
                item(key = "h_${pack.id}") {
                    Column {
                        Text("${pack.name} v${pack.version}", style = MaterialTheme.typography.titleMedium)
                        Text("License: ${pack.license}  •  Source: ${pack.source.ifBlank { "unknown" }}",
                            style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = {
                            AssetManager.deletePack(ctx, pack.id); packs = AssetManager.list(ctx)
                        }) { Text("Delete pack") }
                    }
                }
                val shown = pack.assets.filter {
                    (!onlyFavs || AssetManager.isFavorite(ctx, it)) &&
                        (query.isBlank() || it.name.contains(query, true) || it.type.contains(query, true))
                }
                items(shown, key = { it.key }) { a ->
                    val refresh = favTick   // re-read favourites when toggled
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val ext = a.path.extension.lowercase()
                            if (ext in listOf("png", "jpg", "jpeg", "webp")) {
                                val bmp = remember(a.key) {
                                    runCatching {
                                        BitmapFactory.decodeFile(a.path.absolutePath,
                                            BitmapFactory.Options().apply { inSampleSize = 4 })
                                    }.getOrNull()
                                }
                                if (bmp != null) Image(bmp.asImageBitmap(), null,
                                    Modifier.size(56.dp), contentScale = ContentScale.Crop)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(a.name)
                                Text("${a.type} • ${a.sizeBytes / 1024} KB", style = MaterialTheme.typography.labelSmall)
                                if (a.description.isNotBlank()) Text(a.description, style = MaterialTheme.typography.bodySmall)
                            }
                            val fav = AssetManager.isFavorite(ctx, a)
                            TextButton(onClick = { AssetManager.toggleFavorite(ctx, a); favTick++ }) {
                                Text(if (fav) "★" else "☆")
                            }
                        }
                    }
                }
            }
        }
    }
}
