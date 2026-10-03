package com.thumbshade.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.icons.IconStore
import com.thumbshade.app.notif.AppInfoCache
import com.thumbshade.app.rules.Effects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Icon pack and per-app icons, used everywhere ThumbShade shows an app icon. */
@Composable
fun IconsSection() {
    val context = LocalContext.current
    val s by SettingsRepo.state.collectAsState()
    var choosingPack by remember { mutableStateOf(false) }
    var choosingApp by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }

    Section("Icons") {
        Hint("Use an icon pack, or give any app your own icon, from a pack or a picture. They show everywhere in ThumbShade.")
        val packLabel = if (s.iconPack.isBlank()) "Apps' own icons" else AppInfoCache.label(context, s.iconPack)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { choosingPack = true }
                .padding(vertical = 8.dp),
        ) {
            Text("Icon pack", Modifier.weight(1f))
            Text(packLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        s.customIcons.keys.sortedBy { AppInfoCache.label(context, it).lowercase() }.forEach { pkg ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { editing = pkg }) {
                AppIcon(pkg, Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Text(AppInfoCache.label(context, pkg), Modifier.weight(1f))
                IconButton(onClick = { IconStore.setCustom(pkg, null) }) { androidx.compose.material3.Icon(Icons.Filled.Close, "Reset icon") }
            }
        }
        OutlinedButton(onClick = { choosingApp = true }, modifier = Modifier.fillMaxWidth()) { Text("Set an app's icon") }
    }

    if (choosingPack) {
        val packs = remember { IconStore.installedPacks(context) }
        AlertDialog(
            onDismissRequest = { choosingPack = false },
            title = { Text("Icon pack") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        Text("Apps' own icons", Modifier.fillMaxWidth().clickable { IconStore.setPack(""); choosingPack = false }.padding(vertical = 12.dp))
                    }
                    if (packs.isEmpty()) item { Hint("No icon packs installed. Any pack made for Nova, ADW or similar launchers works.") }
                    items(packs, key = { it.pkg }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { IconStore.setPack(p.pkg); choosingPack = false }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(p.pkg, Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(p.label, color = if (p.pkg == s.iconPack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingPack = false }) { Text("Close") } },
        )
    }
    if (choosingApp) {
        AppPickerDialog("Which app?", emptySet(), single = true, onDismiss = { choosingApp = false }) { chosen ->
            choosingApp = false
            editing = chosen.firstOrNull()
        }
    }
    editing?.let { pkg -> AppIconEditor(pkg) { editing = null } }
}

@Composable
private fun AppIconEditor(pkg: String, onDone: () -> Unit) {
    val context = LocalContext.current
    var fromPack by remember { mutableStateOf<String?>(null) }
    val picture = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ref = IconStore.importPicture(context, pkg, uri)
            if (ref != null) IconStore.setCustom(pkg, ref) else Effects.toast(context, "Couldn't read that picture")
            onDone()
        }
    }
    if (fromPack != null) {
        PackIconGrid(fromPack!!, onDismiss = { fromPack = null }) { name ->
            IconStore.setCustom(pkg, "pack:$fromPack/$name")
            onDone()
        }
        return
    }
    val packs = remember { IconStore.installedPacks(context) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Icon for " + AppInfoCache.label(context, pkg)) },
        text = {
            Column {
                TextButton(onClick = { picture.launch(arrayOf("image/*")) }) { Text("Choose a picture") }
                packs.forEach { p -> TextButton(onClick = { fromPack = p.pkg }) { Text("From " + p.label) } }
                TextButton(onClick = { IconStore.setCustom(pkg, null); onDone() }) { Text("Reset to the default") }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Close") } },
    )
}

@Composable
private fun PackIconGrid(pack: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var names by remember { mutableStateOf<List<String>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(pack) { names = withContext(Dispatchers.IO) { IconStore.packIcons(context, pack) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppInfoCache.label(context, pack)) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val shown = names.orEmpty().filter { query.isBlank() || it.contains(query.replace(' ', '_'), ignoreCase = true) }
                if (names == null) Hint("Loading…")
                LazyVerticalGrid(GridCells.Fixed(4), Modifier.heightIn(max = 420.dp)) {
                    items(shown.take(400), key = { it }) { name ->
                        val bmp = remember(name) {
                            IconStore.packDrawable(context, pack, name)?.let { d -> runCatching { d.toBitmap(96, 96).asImageBitmap() }.getOrNull() }
                        }
                        if (bmp != null) {
                            Image(bmp, name, Modifier.padding(6.dp).size(48.dp).clickable { onPick(name) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
