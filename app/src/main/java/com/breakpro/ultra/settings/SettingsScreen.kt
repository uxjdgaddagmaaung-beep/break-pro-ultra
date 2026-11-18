package com.breakpro.ultra.settings

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.breakpro.ultra.data.MediaItemEntity
import com.breakpro.ultra.data.ProjectDao
import com.breakpro.ultra.data.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private fun dirSize(f: File): Long =
    if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0L

/** Backup = JSON list of projects and their media references (URIs, not the media files themselves). */
private suspend fun backupProjects(ctx: Context, dao: ProjectDao): String = withContext(Dispatchers.IO) {
    val projects = JSONArray()
    dao.observeProjects().first().forEach { p ->
        val media = JSONArray()
        dao.mediaOf(p.id).forEach {
            media.put(JSONObject().put("uri", it.uri).put("name", it.displayName)
                .put("mime", it.mimeType).put("size", it.sizeBytes))
        }
        projects.put(JSONObject().put("name", p.name).put("type", p.type).put("media", media))
    }
    val json = JSONObject().put("format", "breakpro-backup").put("version", 1).put("projects", projects).toString(2)
    val values = ContentValues().apply {
        put(MediaStore.Files.FileColumns.DISPLAY_NAME, "BreakPro_backup_${System.currentTimeMillis()}.json")
        put(MediaStore.Files.FileColumns.MIME_TYPE, "application/json")
        put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/BreakPro")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)!!
    ctx.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) }
    "Backed up ${projects.length()} projects to Documents/BreakPro"
}

/** Restore adds the projects as new ones; existing projects are never overwritten. */
private suspend fun restoreProjects(ctx: Context, dao: ProjectDao, uri: Uri): String = withContext(Dispatchers.IO) {
    val root = JSONObject(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
    require(root.optString("format") == "breakpro-backup") { "Not a BREAK PRO backup file" }
    val arr = root.getJSONArray("projects")
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val id = dao.insert(ProjectEntity(name = o.getString("name") + " (restored)", type = o.getString("type")))
        val media = o.getJSONArray("media")
        for (j in 0 until media.length()) {
            val m = media.getJSONObject(j)
            dao.insertMedia(MediaItemEntity(projectId = id, uri = m.getString("uri"), displayName = m.getString("name"),
                mimeType = m.getString("mime"), sizeBytes = m.optLong("size")))
        }
    }
    "Restored ${arr.length()} projects. Media must still exist at the same locations."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(dao: ProjectDao, dark: Boolean, onDarkChange: (Boolean) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var defaultHeight by remember { mutableIntStateOf(prefs.getInt("default_height", 720)) }
    var backupStatus by remember { mutableStateOf("") }
    val logFile = remember { File(ctx.filesDir, "logs/crash.log") }
    var log by remember { mutableStateOf(if (logFile.exists()) logFile.readText().takeLast(4000) else "") }
    var cacheKb by remember { mutableLongStateOf(dirSize(ctx.cacheDir) / 1024) }
    val assetsKb = remember { dirSize(File(ctx.filesDir, "assets")) / 1024 }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            backupStatus = try { restoreProjects(ctx, dao, uri) } catch (t: Throwable) { "Restore failed: ${t.message}" }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("BREAK PRO ULTRA  v$version", style = MaterialTheme.typography.titleMedium)

            Text("Theme", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = dark, onClick = { onDarkChange(true) }, label = { Text("Dark") })
                FilterChip(selected = !dark, onClick = { onDarkChange(false) }, label = { Text("Light") })
            }

            Text("Default video export resolution", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(480, 720, 1080).forEach { h ->
                    FilterChip(selected = defaultHeight == h, label = { Text("${h}p") },
                        onClick = { defaultHeight = h; prefs.edit().putInt("default_height", h).apply() })
                }
            }

            Text("Backup / restore projects", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        backupStatus = try { backupProjects(ctx, dao) } catch (t: Throwable) { "Backup failed: ${t.message}" }
                    }
                }) { Text("Back up") }
                OutlinedButton(onClick = { restorePicker.launch(arrayOf("application/json", "application/octet-stream")) }) {
                    Text("Restore")
                }
            }
            if (backupStatus.isNotEmpty()) Text(backupStatus, style = MaterialTheme.typography.bodySmall)
            Text("Backs up project names, types and media references only, not the media files or edits inside editors.",
                style = MaterialTheme.typography.bodySmall)

            Text("Storage", style = MaterialTheme.typography.titleSmall)
            Text("Cache: $cacheKb KB    Installed asset packs: $assetsKb KB")
            OutlinedButton(onClick = {
                ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                cacheKb = dirSize(ctx.cacheDir) / 1024
            }) { Text("Clear cache") }

            Text("Crash log", style = MaterialTheme.typography.titleSmall)
            if (log.isBlank()) Text("No crashes recorded.")
            else {
                Text(log, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { logFile.delete(); log = "" }) { Text("Clear crash log") }
            }
        }
    }
}
