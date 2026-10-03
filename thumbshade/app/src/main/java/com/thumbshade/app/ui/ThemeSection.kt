package com.thumbshade.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thumbshade.app.data.AppSettings
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.data.ThemeDef
import com.thumbshade.app.data.Themes
import java.util.UUID

@Composable
fun ThemeSection() {
    val s by SettingsRepo.state.collectAsState()
    val context = LocalContext.current
    var managing by remember { mutableStateOf(false) }

    Section("Select theme") {
        if (Build.VERSION.SDK_INT >= 31) {
            SwitchRow(
                "Material You",
                s.dynamicColor,
                "Uses your phone's colour palette, so the app matches the rest of your phone. Your chosen theme then only decides light or dark.",
            ) { on -> SettingsRepo.update { it.copy(dynamicColor = on) } }
            val swatches = remember {
                val d = dynamicDarkColorScheme(context)
                val l = dynamicLightColorScheme(context)
                listOf(d.primary, d.secondary, d.tertiary, l.primaryContainer, l.secondaryContainer, l.tertiaryContainer)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                swatches.forEach { c ->
                    Box(
                        Modifier
                            .width(34.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(c),
                    )
                }
            }
        }
        SwitchRow("Auto (follow system)", s.autoTheme, "Switch between a light and a dark theme with the phone's dark mode") { on ->
            SettingsRepo.update { it.copy(autoTheme = on) }
        }
        if (s.autoTheme) {
            ThemeChooserRow("Light mode theme", s, s.lightThemeId) { id -> SettingsRepo.update { it.copy(lightThemeId = id) } }
            ThemeChooserRow("Dark mode theme", s, s.darkThemeId) { id -> SettingsRepo.update { it.copy(darkThemeId = id) } }
        } else {
            ThemeChooserRow("Theme", s, s.activeThemeId) { id -> SettingsRepo.update { it.copy(activeThemeId = id) } }
        }
        ButtonRow("Add / edit themes", "${Themes.builtIn.size} built in · ${s.customThemes.size} of your own") { managing = true }
    }

    Section("Tab bar position") {
        RadioRow("Top", !s.tabBarBottom) { SettingsRepo.update { it.copy(tabBarBottom = false) } }
        RadioRow("Bottom (default)", s.tabBarBottom) { SettingsRepo.update { it.copy(tabBarBottom = true) } }
        SwitchRow(
            "Show controls in notification",
            s.notificationControls,
            "Add Toggle shade and Toggle button quick-action buttons to the running notification",
        ) { on -> SettingsRepo.update { it.copy(notificationControls = on) } }
    }

    if (managing) ThemeManagerDialog(s) { managing = false }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun ThemeSwatches(theme: ThemeDef, size: Int = 18) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        theme.swatches.forEach { c ->
            Box(
                Modifier
                    .size(size.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(c))
                    .border(1.dp, Color.Gray.copy(alpha = 0.35f), RoundedCornerShape(6.dp)),
            )
        }
    }
}

@Composable
private fun ThemeChooserRow(title: String, s: AppSettings, selectedId: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = Themes.byId(s, selectedId) ?: Themes.dark
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { open = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(selected.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        ThemeSwatches(selected)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(Themes.all(s), key = { it.id }) { t ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onSelect(t.id)
                                    open = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(t.name, fontWeight = if (t.id == selectedId) FontWeight.Bold else FontWeight.Normal)
                                Hint(if (t.dark) "Dark" else "Light")
                            }
                            ThemeSwatches(t, 16)
                            if (t.id == selectedId) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun ThemeManagerDialog(s: AppSettings, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<ThemeDef?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Themes") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(Themes.all(s), key = { it.id }) { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name)
                            ThemeSwatches(t, 14)
                        }
                        if (t.isBuiltIn) {
                            IconButton(onClick = { editing = t.copy(id = UUID.randomUUID().toString(), name = t.name + " (mine)") }) {
                                Icon(Icons.Filled.ContentCopy, "Copy and edit")
                            }
                        } else {
                            IconButton(onClick = { editing = t }) { Icon(Icons.Filled.Edit, "Edit") }
                            IconButton(onClick = { deleteTheme(t.id) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { editing = ThemeDef(name = "My theme") }) { Text("New theme") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    editing?.let { theme ->
        ThemeEditorDialog(theme, onDismiss = { editing = null }) { saved ->
            saveTheme(saved)
            editing = null
        }
    }
}

private fun saveTheme(theme: ThemeDef) = SettingsRepo.update { s ->
    val list = if (s.customThemes.any { it.id == theme.id }) s.customThemes.map { if (it.id == theme.id) theme else it }
    else s.customThemes + theme
    s.copy(customThemes = list)
}

private fun deleteTheme(id: String) = SettingsRepo.update { s ->
    fun fix(current: String, fallback: String) = if (current == id) fallback else current
    s.copy(
        customThemes = s.customThemes.filterNot { it.id == id },
        activeThemeId = fix(s.activeThemeId, Themes.dark.id),
        lightThemeId = fix(s.lightThemeId, Themes.light.id),
        darkThemeId = fix(s.darkThemeId, Themes.dark.id),
    )
}

@Composable
private fun ThemeEditorDialog(initial: ThemeDef, onDismiss: () -> Unit, onSave: (ThemeDef) -> Unit) {
    var t by remember(initial.id) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit theme") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemePreview(t)
                TextRow("Name", t.name) { t = t.copy(name = it) }
                SwitchRow("Dark theme", t.dark, "Used for system parts like the keyboard and status bar") { t = t.copy(dark = it) }
                ColorRow("Accent", t.accent) { t = t.copy(accent = it) }
                ColorRow("Background", t.background) { t = t.copy(background = it) }
                ColorRow("Cards", t.card) { t = t.copy(card = it) }
                ColorRow("Text", t.text) { t = t.copy(text = it) }
                ColorRow("Secondary text", t.secondaryText) { t = t.copy(secondaryText = it) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(t.copy(name = t.name.ifBlank { "My theme" })) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A mini shade card in the theme's colours. */
@Composable
private fun ThemePreview(t: ThemeDef) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(t.background))
            .padding(12.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(t.card))
                .padding(12.dp),
        ) {
            Text("Messages · now", color = Color(t.accent), style = MaterialTheme.typography.labelMedium)
            Text("Alex", color = Color(t.text), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("See you at 7?", color = Color(t.secondaryText), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Reply", color = Color(t.accent), style = MaterialTheme.typography.labelLarge)
                Text("Mark as read", color = Color(t.accent), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
