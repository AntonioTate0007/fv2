package com.thumbshade.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.thumbshade.app.R
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.notif.NotificationRepo
import com.thumbshade.app.notif.ShadeFilter
import com.thumbshade.app.notif.ShadeItem
import com.thumbshade.app.ui.ShowShadeActivity
import com.thumbshade.app.ui.clockTime

object Widgets {
    fun updateAll(context: Context) {
        val awm = AppWidgetManager.getInstance(context)
        CountWidget.update(context, awm, awm.getAppWidgetIds(ComponentName(context, CountWidget::class.java)))
        StripWidget.update(context, awm, awm.getAppWidgetIds(ComponentName(context, StripWidget::class.java)))
        val listIds = awm.getAppWidgetIds(ComponentName(context, ListWidget::class.java))
        if (listIds.isNotEmpty()) {
            ListWidget.update(context, awm, listIds)
            @Suppress("DEPRECATION")
            awm.notifyAppWidgetViewDataChanged(listIds, R.id.widget_list)
        }
    }

    fun visible(): List<ShadeItem> =
        ShadeFilter.visible(NotificationRepo.items.value, SettingsRepo.current).sortedByDescending { it.postTime }

    fun openShadeIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, ShowShadeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** A tiny count of notifications. Tap opens the shade. */
class CountWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, manager, ids)

    companion object {
        fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val n = Widgets.visible().size
            ids.forEach { id ->
                val v = RemoteViews(context.packageName, R.layout.widget_count)
                v.setTextViewText(R.id.widget_count, n.toString())
                v.setOnClickPendingIntent(R.id.widget_root, Widgets.openShadeIntent(context))
                manager.updateAppWidget(id, v)
            }
        }
    }
}

/** A slim strip of the latest notifications' app icons. */
class StripWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, manager, ids)

    companion object {
        private val slots = intArrayOf(R.id.strip_0, R.id.strip_1, R.id.strip_2, R.id.strip_3, R.id.strip_4, R.id.strip_5)

        fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val items = Widgets.visible().distinctBy { it.pkg }
            ids.forEach { id ->
                val v = RemoteViews(context.packageName, R.layout.widget_strip)
                slots.forEachIndexed { i, slot ->
                    val item = items.getOrNull(i)
                    if (item?.smallIcon != null) {
                        v.setViewVisibility(slot, View.VISIBLE)
                        v.setImageViewIcon(slot, item.smallIcon)
                        v.setInt(slot, "setColorFilter", if (item.color != 0) item.color or 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                    } else {
                        v.setViewVisibility(slot, View.GONE)
                    }
                }
                val extra = items.size - slots.size
                v.setTextViewText(R.id.strip_more, if (extra > 0) "+$extra" else "")
                v.setOnClickPendingIntent(R.id.widget_root, Widgets.openShadeIntent(context))
                manager.updateAppWidget(id, v)
            }
        }
    }
}

/** The full notification list on the home or lock screen. */
class ListWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, manager, ids)

    companion object {
        fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            ids.forEach { id ->
                val v = RemoteViews(context.packageName, R.layout.widget_list)
                val svc = Intent(context, ListWidgetService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
                @Suppress("DEPRECATION")
                v.setRemoteAdapter(R.id.widget_list, svc)
                v.setEmptyView(R.id.widget_list, R.id.widget_empty)
                val template = PendingIntent.getActivity(
                    context, 1,
                    Intent(context, ShowShadeActivity::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                v.setPendingIntentTemplate(R.id.widget_list, template)
                v.setOnClickPendingIntent(R.id.widget_header, Widgets.openShadeIntent(context))
                manager.updateAppWidget(id, v)
            }
        }
    }
}

class ListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val context: Context) : RemoteViewsFactory {
        private var items: List<ShadeItem> = emptyList()

        override fun onCreate() = Unit
        override fun onDataSetChanged() {
            items = Widgets.visible()
        }

        override fun onDestroy() = Unit
        override fun getCount() = items.size
        override fun getViewAt(position: Int): RemoteViews {
            val item = items.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_list_item)
            return RemoteViews(context.packageName, R.layout.widget_list_item).apply {
                setTextViewText(R.id.item_app, item.appName)
                setTextViewText(R.id.item_time, clockTime(item.postTime))
                setTextViewText(R.id.item_title, item.title)
                setTextViewText(R.id.item_text, item.displayText)
                item.smallIcon?.let { setImageViewIcon(R.id.item_icon, it) }
                setOnClickFillInIntent(R.id.item_root, Intent().putExtra(ShowShadeActivity.EXTRA_KEY, item.key))
            }
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = items.getOrNull(position)?.key?.hashCode()?.toLong() ?: position.toLong()
        override fun hasStableIds() = true
    }
}
