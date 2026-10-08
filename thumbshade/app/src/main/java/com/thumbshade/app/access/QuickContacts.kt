package com.thumbshade.app.access

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import com.thumbshade.app.data.GestureAction
import com.thumbshade.app.data.GestureType
import com.thumbshade.app.data.QuickContact
import com.thumbshade.app.data.QuickTextApp
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.rules.Effects
import java.util.concurrent.ConcurrentHashMap

/**
 * Favourite people for the switcher's "quick text" ring: your starred contacts and anyone you
 * added in ThumbShade. Picking one opens a new message to them in your messaging app.
 */
object QuickContacts {
    private const val SEP = "\u001F"

    /** Packs a number and an optional photo address into a gesture argument. */
    fun arg(number: String, photo: String?) = number + SEP + (photo ?: "")
    fun number(arg: String) = arg.substringBefore(SEP)
    fun photo(arg: String) = arg.substringAfter(SEP, "").ifBlank { null }

    private fun canRead(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Contacts starred in the Contacts app that have a phone number. */
    fun starred(context: Context, max: Int): List<QuickContact> {
        if (!canRead(context)) return emptyList()
        val out = mutableListOf<QuickContact>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                    ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
                ),
                ContactsContract.CommonDataKinds.Phone.STARRED + "=1",
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { c ->
                val seen = HashSet<Long>()
                // Prefer each person's default number: rows marked primary first.
                val rows = mutableListOf<Array<Any?>>()
                while (c.moveToNext()) rows += arrayOf(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4))
                rows.sortedByDescending { it[4] as Int }.forEach { r ->
                    val id = r[0] as Long
                    if (id in seen) return@forEach
                    seen += id
                    val number = (r[2] as String?)?.takeIf { it.isNotBlank() } ?: return@forEach
                    out += QuickContact((r[1] as String?).orEmpty().ifBlank { number }, number, r[3] as String?)
                }
            }
        }
        return out.sortedBy { it.name.lowercase() }.take(max)
    }

    /** Who goes in the ring: the people you added first, then starred contacts. */
    fun forSwitcher(context: Context): List<GestureAction> {
        val s = SettingsRepo.current
        if (!s.quickTextEnabled) return emptyList()
        val max = s.quickTextCount.coerceIn(1, com.thumbshade.app.data.GestureMode.RINGS[1])
        val people = LinkedHashMap<String, QuickContact>()
        s.quickContacts.forEach { people.putIfAbsent(digits(it.number), it) }
        if (s.quickTextStarred) starred(context, max).forEach { people.putIfAbsent(digits(it.number), it) }
        return people.values.take(max).map { GestureAction(GestureType.QUICK_TEXT, arg(it.number, it.photo), it.name) }
    }

    private fun digits(n: String) = n.filter { it.isDigit() }.takeLast(10)

    /** Opens a new message to [number] in the chosen app. */
    fun text(context: Context, number: String) {
        val clean = number.filter { it.isDigit() || it == '+' }
        val intent = when (SettingsRepo.current.quickTextApp) {
            QuickTextApp.MESSAGES -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$clean"))
            QuickTextApp.WHATSAPP -> Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + clean.removePrefix("+"))).setPackage("com.whatsapp")
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
            // WhatsApp missing: fall back to the default messaging app.
            runCatching {
                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$clean")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Effects.toast(context, "No messaging app found") }
        }
    }

    private val photos = ConcurrentHashMap<String, Any>()
    private object None

    /** The contact's small photo, if there is one and it can be read. */
    fun photoBitmap(context: Context, uri: String?): Bitmap? {
        if (uri.isNullOrBlank()) return null
        photos[uri]?.let { return it as? Bitmap }
        val bmp = runCatching { context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
        photos[uri] = bmp ?: None
        return bmp
    }

    fun initials(name: String): String =
        name.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "?" }
}
