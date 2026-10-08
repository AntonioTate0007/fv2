package com.thumbshade.app.icons

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import com.thumbshade.app.data.SettingsRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Icon packs (the common launcher-theme format: an appfilter.xml mapping app components to
 * drawables) and per-app custom icons, from a pack or from a picture.
 */
object IconStore {
    data class Pack(val pkg: String, val label: String)

    private val packIntents = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme",
        "org.adw.ActivityStarter.THEMES",
        "com.anddoes.launcher.THEME",
        "com.teslacoilsw.launcher.THEME",
    )

    private val _version = MutableStateFlow(0)
    /** Bumps whenever icons change, so views can reload. */
    val version: StateFlow<Int> = _version

    /** pack package → (app package → drawable name). */
    private val filters = ConcurrentHashMap<String, Map<String, String>>()
    private val drawables = ConcurrentHashMap<String, Drawable>()

    fun changed() {
        drawables.clear()
        _version.value++
    }

    fun installedPacks(context: Context): List<Pack> {
        val pm = context.packageManager
        return packIntents.flatMap { action ->
            runCatching { pm.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA) }.getOrDefault(emptyList())
        }
            .map { it.activityInfo.packageName }
            .distinct()
            .map { Pack(it, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrDefault(it)) }
            .sortedBy { it.label.lowercase() }
    }

    /** The icon to show for [pkg], or null to use the app's own. */
    fun iconFor(context: Context, pkg: String): Drawable? {
        val s = SettingsRepo.current
        val custom = s.customIcons[pkg]
        if (custom != null) load(context, custom)?.let { return it }
        if (s.iconPack.isNotBlank()) {
            val name = filterOf(context, s.iconPack)[pkg] ?: return null
            return load(context, "pack:${s.iconPack}/$name")
        }
        return null
    }

    private fun load(context: Context, ref: String): Drawable? = drawables[ref] ?: run {
        when {
            ref.startsWith("pack:") -> {
                val pack = ref.removePrefix("pack:").substringBefore('/')
                val name = ref.substringAfter('/')
                packDrawable(context, pack, name)
            }
            ref.startsWith("file:") -> {
                val f = File(iconDir(context), ref.removePrefix("file:"))
                BitmapFactory.decodeFile(f.path)?.let { BitmapDrawable(context.resources, it) }
            }
            else -> null
        }
    }?.also { drawables[ref] = it }

    fun packDrawable(context: Context, pack: String, name: String): Drawable? = runCatching {
        val res = context.packageManager.getResourcesForApplication(pack)
        val id = res.getIdentifier(name, "drawable", pack)
        if (id == 0) null else res.getDrawable(id, null)
    }.getOrNull()

    /** Drawable names a pack offers, for choosing one by hand. */
    fun packIcons(context: Context, pack: String): List<String> {
        val res = resourcesOf(context, pack) ?: return emptyList()
        val names = LinkedHashSet<String>()
        // drawable.xml lists everything the pack wants to show in pickers; appfilter.xml fills gaps.
        parse(res, pack, "drawable") { p -> if (p.name == "item") p.getAttributeValue(null, "drawable")?.let(names::add) }
        names += filterOf(context, pack).values
        return names.toList()
    }

    private fun filterOf(context: Context, pack: String): Map<String, String> = filters.getOrPut(pack) {
        val res = resourcesOf(context, pack) ?: return@getOrPut emptyMap()
        val map = HashMap<String, String>()
        val launchers = HashMap<String, String>()
        parse(res, pack, "appfilter") { p ->
            if (p.name != "item") return@parse
            val component = p.getAttributeValue(null, "component") ?: return@parse
            val drawable = p.getAttributeValue(null, "drawable") ?: return@parse
            // ComponentInfo{com.app/com.app.MainActivity}
            val inner = component.substringAfter('{', "").substringBefore('}')
            val appPkg = inner.substringBefore('/')
            if (appPkg.isBlank()) return@parse
            map.putIfAbsent(appPkg, drawable)
            launchers[inner] = drawable
        }
        // Prefer the entry for the app's launcher activity when a pack lists several.
        map.keys.toList().forEach { appPkg ->
            val launch = context.packageManager.getLaunchIntentForPackage(appPkg)?.component ?: return@forEach
            launchers[launch.packageName + "/" + launch.className]?.let { map[appPkg] = it }
        }
        map
    }

    private fun resourcesOf(context: Context, pack: String): Resources? =
        runCatching { context.packageManager.getResourcesForApplication(pack) }.getOrNull()

    /** Reads res/xml/<name>.xml, or assets/<name>.xml when the pack ships it there. */
    private fun parse(res: Resources, pack: String, name: String, onTag: (XmlPullParser) -> Unit) {
        runCatching {
            val id = res.getIdentifier(name, "xml", pack)
            val parser: XmlPullParser = if (id != 0) res.getXml(id) else {
                val stream = res.assets.open("$name.xml")
                android.util.Xml.newPullParser().apply { setInput(stream, "utf-8") }
            }
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) onTag(parser)
                event = parser.next()
            }
        }
    }

    fun iconDir(context: Context): File = File(context.filesDir, "icons").apply { mkdirs() }

    /** Copies a picture into the icon folder, scaled down, and returns its reference. */
    fun importPicture(context: Context, pkg: String, uri: Uri): String? = runCatching {
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val side = 192
        val scaled = Bitmap.createScaledBitmap(bmp, side, side * bmp.height / bmp.width.coerceAtLeast(1), true)
        val name = pkg.replace(Regex("[^A-Za-z0-9._]"), "_") + "_" + System.currentTimeMillis() + ".png"
        File(iconDir(context), name).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        "file:$name"
    }.getOrNull()

    fun setCustom(pkg: String, ref: String?) {
        SettingsRepo.update { s -> s.copy(customIcons = if (ref == null) s.customIcons - pkg else s.customIcons + (pkg to ref)) }
        changed()
    }

    fun setPack(pack: String) {
        SettingsRepo.update { it.copy(iconPack = pack) }
        filters.remove(pack)
        changed()
    }
}
