package com.breakpro.ultra.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakpro.ultra.data.ProjectType

private val Purple = Color(0xFF8B3DFF)
private val PurpleLight = Color(0xFFB06BFF)
private val Gold = Color(0xFFFFC24B)

class HomeActions(
    val newProject: () -> Unit,
    val newOfType: (ProjectType) -> Unit,
    val projects: () -> Unit,
    val assets: () -> Unit,
    val exports: () -> Unit,
    val settings: () -> Unit,
    val soon: (String) -> Unit
)

/** onClick == null means the feature is not implemented yet (tile shows "Soon"). */
private class Tool(
    val name: String,
    val icon: ImageVector,
    val sub: String = "",
    val onClick: ((HomeActions) -> Unit)? = null
)

private val quickActions = listOf(
    Tool("New Project", Icons.Default.MovieCreation, "Start Fresh") { it.newProject() },
    Tool("Edit Photo", Icons.Default.Image, "Enhance & Edit") { it.newOfType(ProjectType.PHOTO) },
    Tool("Auto Cut", Icons.Default.ContentCut, "Smart Cut") { it.newOfType(ProjectType.VIDEO) },
    Tool("AI Edit", Icons.Default.AutoFixHigh, "With AI")
)

private val popularTools = listOf(
    Tool("Timeline Editor", Icons.Default.Movie) { it.newOfType(ProjectType.VIDEO) },
    Tool("Multi-Layer Editing", Icons.Default.Layers),
    Tool("Effects", Icons.Default.AutoAwesome) { it.newOfType(ProjectType.VIDEO) },
    Tool("Transitions", Icons.Default.SwapHoriz),
    Tool("Filters", Icons.Default.FilterVintage) { it.newOfType(ProjectType.PHOTO) },
    Tool("Text", Icons.Default.TextFields) { it.newOfType(ProjectType.POSTER) },
    Tool("Stickers", Icons.Default.EmojiEmotions) { it.newOfType(ProjectType.POSTER) },
    Tool("Overlay", Icons.Default.Photo) { it.newOfType(ProjectType.VIDEO) },
    Tool("Chroma Key", Icons.Default.Person),
    Tool("Speed Control", Icons.Default.Speed) { it.newOfType(ProjectType.VIDEO) },
    Tool("Reverse", Icons.Default.FastRewind),
    Tool("Voiceover", Icons.Default.Mic) { it.newOfType(ProjectType.AUDIO) }
)

private val moreFeatures = listOf(
    Tool("Templates", Icons.Default.GridView) { it.newOfType(ProjectType.POSTER) },
    Tool("Music", Icons.Default.MusicNote) { it.newOfType(ProjectType.AUDIO) },
    Tool("Sound FX", Icons.Default.GraphicEq) { it.newOfType(ProjectType.AUDIO) },
    Tool("Background", Icons.Default.Landscape) { it.newOfType(ProjectType.POSTER) },
    Tool("Crop & Trim", Icons.Default.Crop) { it.newOfType(ProjectType.PHOTO) },
    Tool("Export", Icons.Default.FileUpload) { it.exports() }
)

@Composable
fun HomeScreen(a: HomeActions) {
    var moreOpen by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxSize()) {
        NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
            Spacer(Modifier.height(8.dp))
            NavigationRailItem(selected = true, onClick = {},
                icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home", fontSize = 11.sp) })
            NavigationRailItem(selected = false, onClick = a.projects,
                icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Projects", fontSize = 11.sp) })
            NavigationRailItem(selected = false, onClick = a.assets,
                icon = { Icon(Icons.Default.Star, null) }, label = { Text("Template", fontSize = 11.sp) })
            NavigationRailItem(selected = false, onClick = { a.soon("Cloud sync") },
                icon = { Icon(Icons.Default.Cloud, null) }, label = { Text("Cloud", fontSize = 11.sp) })
            Box {
                NavigationRailItem(selected = false, onClick = { moreOpen = true },
                    icon = { Icon(Icons.Default.MoreHoriz, null) }, label = { Text("More", fontSize = 11.sp) })
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(text = { Text("Export Manager") }, onClick = { moreOpen = false; a.exports() })
                    DropdownMenuItem(text = { Text("Asset Library") }, onClick = { moreOpen = false; a.assets() })
                    DropdownMenuItem(text = { Text("Settings") }, onClick = { moreOpen = false; a.settings() })
                }
            }
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.padding(6.dp).clip(RoundedCornerShape(12.dp))
                    .background(Purple.copy(alpha = 0.18f)).clickable { a.soon("Pro plans") }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Star, null, tint = Gold)
                Text("Pro", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Soon", fontSize = 9.sp)
            }
            Spacer(Modifier.height(8.dp))
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val wide = maxWidth > 600.dp
            val toolsPerRow = if (wide) 6 else 3
            val quickPerRow = if (wide) 4 else 2

            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Header(a) }
                item { Hero(a) }
                quickActions.chunked(quickPerRow).forEach { row ->
                    item { GridRow(row, quickPerRow) { t, m -> QuickTile(t, a, m) } }
                }
                item { SectionTitle("Popular Tools") }
                popularTools.chunked(toolsPerRow).forEach { row ->
                    item { GridRow(row, toolsPerRow) { t, m -> ToolTile(t, a, m) } }
                }
                item { SectionTitle("More Features") }
                moreFeatures.chunked(toolsPerRow).forEach { row ->
                    item { GridRow(row, toolsPerRow) { t, m -> ToolTile(t, a, m) } }
                }
                item {
                    Text(
                        "Tiles marked \"Soon\" are planned but not built yet. Use + New Project for GIF, PDF, vector, audio and poster projects.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(a: HomeActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    append("Break ")
                    withStyle(SpanStyle(color = Purple)) { append("Pro") }
                },
                fontSize = 26.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic
            )
            Text("Edit • Create • Inspire", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { a.soon("Pro plans") }) { Icon(Icons.Default.Star, null, tint = Gold) }
        IconButton(onClick = { a.soon("Notifications") }) { Icon(Icons.Default.Notifications, null) }
        IconButton(onClick = a.settings) { Icon(Icons.Default.Settings, null) }
    }
}

@Composable
private fun Hero(a: HomeActions) {
    Box(
        Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1B0A3D), Color(0xFF5B22C8), Color(0xFF2A0B5E))))
    ) {
        Column(Modifier.padding(16.dp).align(Alignment.CenterStart)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White)) { append("Break ") }
                    withStyle(SpanStyle(color = PurpleLight)) { append("Pro") }
                },
                fontSize = 30.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic
            )
            Text("Your Ultimate Editing Partner", fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = a.newProject,
                colors = ButtonDefaults.buttonColors(containerColor = PurpleLight, contentColor = Color.Black)
            ) { Text("Start Editing  ›") }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(Purple))
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun <T> GridRow(items: List<T>, perRow: Int, content: @Composable (T, Modifier) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { content(it, Modifier.weight(1f)) }
        repeat(perRow - items.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun QuickTile(t: Tool, a: HomeActions, modifier: Modifier) {
    val ready = t.onClick != null
    Card(
        modifier.height(96.dp).clickable { t.onClick?.invoke(a) ?: a.soon(t.name) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ready) Purple.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(t.icon, null, Modifier.size(28.dp), tint = if (ready) PurpleLight else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(t.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(if (ready) t.sub else "Soon", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToolTile(t: Tool, a: HomeActions, modifier: Modifier, height: Dp = 84.dp) {
    val ready = t.onClick != null
    Card(
        modifier.height(height).clickable { t.onClick?.invoke(a) ?: a.soon(t.name) },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxSize().padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(t.icon, null, tint = if (ready) PurpleLight else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(4.dp))
            Text(t.name, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 13.sp)
            if (!ready) Text("Soon", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}
