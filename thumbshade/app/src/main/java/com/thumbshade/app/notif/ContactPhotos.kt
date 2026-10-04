package com.thumbshade.app.notif

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

/**
 * The picture of the person a notification is from: the one the app attached (chat apps do),
 * else the photo saved for them in Contacts. Off the main thread; results are cached.
 */
object ContactPhotos {
    private val cache = ConcurrentHashMap<String, Any>()
    private object None

    fun forItem(context: Context, item: ShadeItem): Bitmap? {
        val key = item.key + ":" + item.postTime
        cache[key]?.let { return it as? Bitmap }
        val bmp = fromNotification(context, item) ?: fromContacts(context, item)
        cache[key] = bmp ?: None
        if (cache.size > 200) cache.keys.take(100).forEach { cache.remove(it) }
        return bmp
    }

    private fun fromNotification(context: Context, item: ShadeItem): Bitmap? {
        val icon = item.senderIcon ?: return null
        return runCatching { icon.loadDrawable(context)?.toBitmap(192, 192) }.getOrNull()
    }

    private fun fromContacts(context: Context, item: ShadeItem): Bitmap? {
        if (item.people.isEmpty()) return null
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val cr = context.contentResolver
        for (person in item.people) {
            val uri = person.uri ?: continue
            val contactUri: Uri? = runCatching {
                when {
                    uri.startsWith("tel:") -> lookup(context, Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(uri.removePrefix("tel:"))), ContactsContract.PhoneLookup._ID)
                    uri.startsWith("mailto:") -> lookup(context, Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI, Uri.encode(uri.removePrefix("mailto:"))), ContactsContract.Data.CONTACT_ID)
                    uri.startsWith("content://com.android.contacts") -> ContactsContract.Contacts.lookupContact(cr, Uri.parse(uri)) ?: Uri.parse(uri)
                    else -> null
                }
            }.getOrNull() ?: continue
            val bmp = runCatching {
                ContactsContract.Contacts.openContactPhotoInputStream(cr, contactUri, true)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null) return bmp
        }
        return null
    }

    private fun lookup(context: Context, uri: Uri, idColumn: String): Uri? =
        context.contentResolver.query(uri, arrayOf(idColumn), null, null, null)?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, c.getLong(0)) else null
        }
}
