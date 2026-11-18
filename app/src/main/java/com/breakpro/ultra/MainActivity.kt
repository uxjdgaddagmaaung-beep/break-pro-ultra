package com.breakpro.ultra

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.breakpro.ultra.data.*
import kotlinx.coroutines.launch

/** Studios shown on the dashboard. Only "My Projects" is functional in Phase 1. */
private data class Studio(val name: String, val phase: Int)

private val studios = listOf(
    Studio("Video Studio", 2), Studio("Motion Studio", 4), Studio("Photo Studio", 5),
    Studio("Graphic Design Studio", 7), Studio("Audio Studio", 6), Studio("Animation Studio", 6),
    Studio("3D Studio", 8), Studio("AI Creative Studio", 8), Studio("PDF Studio", 7),
    Studio("GIF Studio", 6), Studio("Template Marketplace", 9), Studio("Effects Marketplace", 9),
    Studio("Asset Library", 9), Studio("My Projects", 1), Studio("Export Manager", 2),
    Studio("Settings", 1)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = (application as BreakProApp).db.projectDao()
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            var dark by remember { mutableStateOf(prefs.getBoolean("dark_theme", true)) }
            val accent = Color(0xFF8B3DFF)
            val darkScheme = darkColorScheme(
                primary = accent,
                background = Color(0xFF0B0B14),
                surface = Color(0xFF0B0B14),
                surfaceVariant = Color(0xFF1A1A2B)
            )
            MaterialTheme(colorScheme = if (dark) darkScheme else lightColorScheme(primary = accent)) {
                Surface(Modifier.fillMaxSize()) {
                    Root(dao, dark) { dark = it; prefs.edit().putBoolean("dark_theme", it).apply() }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(dao: ProjectDao, dark: Boolean, onDarkChange: (Boolean) -> Unit) {
    var openProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var showProjects by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showAssets by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showExports by remember { mutableStateOf(false) }
    val projects by dao.observeProjects().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    when {
        openProject != null -> ProjectScreen(dao, openProject!!) { openProject = null }
        showAssets -> com.breakpro.ultra.assets.AssetLibraryScreen { showAssets = false }
        showSettings -> com.breakpro.ultra.settings.SettingsScreen(dao, dark, onDarkChange) { showSettings = false }
        showExports -> com.breakpro.ultra.exportlog.ExportManagerScreen { showExports = false }
        else -> if (!showProjects) {
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
                Box(Modifier.padding(pad)) {
                    com.breakpro.ultra.home.HomeScreen(
                        com.breakpro.ultra.home.HomeActions(
                            newProject = { showAdd = true },
                            newOfType = { type ->
                                scope.launch {
                                    val id = dao.insert(
                                        ProjectEntity(name = "Untitled " + type.label.removeSuffix(" Project"), type = type.name)
                                    )
                                    openProject = dao.get(id)
                                }
                            },
                            projects = { showProjects = true },
                            assets = { showAssets = true },
                            exports = { showExports = true },
                            settings = { showSettings = true },
                            soon = { name ->
                                scope.launch { snackbar.showSnackbar("$name: not implemented yet") }
                            }
                        )
                    )
                }
            }
        } else Scaffold(
            topBar = { TopAppBar(title = { Text(if (showProjects) "My Projects" else "BREAK PRO ULTRA") }) },
            floatingActionButton = {
                FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add Project") }
            },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { pad ->
            Box(Modifier.padding(pad)) {
                if (showProjects) {
                    ProjectList(projects, onOpen = { openProject = it },
                        onDelete = { p -> scope.launch { dao.delete(p) } },
                        onRename = { p, name -> scope.launch { dao.update(p.copy(name = name, updatedAt = System.currentTimeMillis())) } },
                        onDuplicate = { p ->
                            scope.launch {
                                val newId = dao.insert(ProjectEntity(name = p.name + " copy", type = p.type))
                                dao.mediaOf(p.id).forEach { dao.insertMedia(it.copy(id = 0, projectId = newId)) }
                            }
                        },
                        onBack = { showProjects = false })
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(studios) { s ->
                            Card(Modifier.fillMaxWidth().height(96.dp).clickable {
                                if (s.name == "My Projects") showProjects = true
                                else if (s.name == "Asset Library") showAssets = true
                                else if (s.name == "Settings") showSettings = true
                                else if (s.name == "Export Manager") showExports = true
                                else scope.launch {
                                    snackbar.showSnackbar("${s.name}: not implemented yet (planned Phase ${s.phase})")
                                }
                            }) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.Center) {
                                    Text(s.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        if (s.phase == 1) "Available" else "Phase ${s.phase}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddProjectDialog(
            onDismiss = { showAdd = false },
            onCreate = { type, name ->
                showAdd = false
                scope.launch {
                    val id = dao.insert(ProjectEntity(name = name, type = type.name))
                    openProject = dao.get(id)
                }
            })
    }
}

@Composable
private fun ProjectList(
    projects: List<ProjectEntity>, onOpen: (ProjectEntity) -> Unit,
    onDelete: (ProjectEntity) -> Unit,
    onRename: (ProjectEntity, String) -> Unit,
    onDuplicate: (ProjectEntity) -> Unit,
    onBack: () -> Unit
) {
    var renaming by remember { mutableStateOf<ProjectEntity?>(null) }
    var newName by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    renaming?.let { p ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename project") },
            text = { OutlinedTextField(newName, { newName = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { onRename(p, newName.ifBlank { p.name }); renaming = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }
    if (projects.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No projects yet. Tap + to create one.")
                TextButton(onClick = onBack) { Text("Back to dashboard") }
            }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = onBack) { Text("← Dashboard") }
            OutlinedTextField(query, { query = it }, label = { Text("Search projects") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        items(projects.filter { query.isBlank() || it.name.contains(query, true) }) { p ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(p) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text(ProjectType.valueOf(p.type).label, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { newName = p.name; renaming = p }) { Text("Rename") }
                    TextButton(onClick = { onDuplicate(p) }) { Text("Copy") }
                    IconButton(onClick = { onDelete(p) }) { Icon(Icons.Default.Delete, "Delete") }
                }
            }
        }
    }
}

@Composable
private fun AddProjectDialog(onDismiss: () -> Unit, onCreate: (ProjectType, String) -> Unit) {
    var name by remember { mutableStateOf("Untitled") }
    var type by remember { mutableStateOf(ProjectType.VIDEO) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Project") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                ProjectType.values().forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { type = t }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Text(t.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(type, name.ifBlank { "Untitled" }) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectScreen(dao: ProjectDao, project: ProjectEntity, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val media by dao.observeMedia(project.id).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            uris.forEach { uri ->
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val (name, size) = queryMeta(ctx, uri)
                dao.insertMedia(
                    MediaItemEntity(
                        projectId = project.id, uri = uri.toString(), displayName = name,
                        mimeType = ctx.contentResolver.getType(uri) ?: "application/octet-stream",
                        sizeBytes = size
                    )
                )
            }
            dao.update(project.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    var editing by remember { mutableStateOf(false) }
    if (editing) {
        when (project.type) {
            ProjectType.VIDEO.name -> com.breakpro.ultra.video.VideoEditorScreen(media) { editing = false }
            ProjectType.PHOTO.name -> com.breakpro.ultra.photo.PhotoEditorScreen(media) { editing = false }
            ProjectType.AUDIO.name -> com.breakpro.ultra.audio.AudioEditorScreen(media) { editing = false }
            ProjectType.POSTER.name -> com.breakpro.ultra.design.PosterEditorScreen { editing = false }
            ProjectType.PDF.name -> com.breakpro.ultra.pdf.PdfStudioScreen(media) { editing = false }
            ProjectType.VECTOR.name -> com.breakpro.ultra.vector.VectorEditorScreen { editing = false }
            ProjectType.GIF.name, ProjectType.ANIMATION.name ->
                com.breakpro.ultra.gif.GifStudioScreen(media) { editing = false }
        }
        return
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(project.name) },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp)) {
            Text(ProjectType.valueOf(project.type).label, style = MaterialTheme.typography.titleSmall)
            Text("Editor for this type arrives in a later phase. Media import works now.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { picker.launch(arrayOf("video/*", "image/*", "audio/*", "application/pdf")) }) {
                Text("Import media")
            }
            if (project.type in listOf(ProjectType.VIDEO.name, ProjectType.PHOTO.name, ProjectType.AUDIO.name, ProjectType.POSTER.name,
            ProjectType.GIF.name, ProjectType.ANIMATION.name, ProjectType.PDF.name, ProjectType.VECTOR.name)) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = { editing = true }) { Text("Open editor") }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(media) { m ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.displayName)
                                Text("${m.mimeType} • ${m.sizeBytes / 1024} KB", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { scope.launch { dao.deleteMedia(m) } }) {
                                Icon(Icons.Default.Delete, "Remove")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun queryMeta(ctx: android.content.Context, uri: Uri): Pair<String, Long> {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val s = c.getColumnIndex(OpenableColumns.SIZE)
            return (if (n >= 0) c.getString(n) else "media") to (if (s >= 0) c.getLong(s) else 0L)
        }
    }
    return "media" to 0L
}
