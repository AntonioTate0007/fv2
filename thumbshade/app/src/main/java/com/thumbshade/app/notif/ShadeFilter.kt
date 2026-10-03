package com.thumbshade.app.notif

import com.thumbshade.app.data.AppSettings
import com.thumbshade.app.rules.RuleMatcher

/** Which notifications the shade shows, in which order, folded into app groups where asked. */
object ShadeFilter {
    const val OWN_PACKAGE = "com.thumbshade.app"

    sealed interface Entry {
        val id: String
        val pkg: String
        val time: Long
    }

    data class Single(val item: ShadeItem) : Entry {
        override val id get() = item.key
        override val pkg get() = item.pkg
        override val time get() = item.postTime
    }

    data class Group(override val pkg: String, val appName: String, val items: List<ShadeItem>) : Entry {
        override val id get() = "group:$pkg"
        override val time get() = items.maxOf { it.postTime }
    }

    fun visible(all: List<ShadeItem>, s: AppSettings): List<ShadeItem> {
        val groupSizes = all.groupingBy { it.groupKey }.eachCount()
        val showOnly = RuleMatcher.splitWords(s.showOnlyWords)
        val hide = RuleMatcher.splitWords(s.hideWords)
        return all.filter { item ->
            when {
                item.pkg == OWN_PACKAGE && item.channelId != com.thumbshade.app.App.CHANNEL_DIGEST -> false
                s.showMedia && item.mediaToken != null -> false
                s.includeApps.isNotEmpty() && item.pkg !in s.includeApps -> false
                item.pkg in s.excludeApps -> false
                s.hideOngoing && (item.ongoing || !item.clearable) -> false
                s.hideNoTime && !item.showWhen -> false
                s.applyDnd && !item.matchesDnd -> false
                item.groupSummary && s.ungroup && (groupSizes[item.groupKey] ?: 0) > 1 -> false
                item.title.isBlank() && item.displayText.isBlank() && item.messages.isEmpty() -> false
                s.textFilter && !passesText(item, showOnly, hide) -> false
                else -> true
            }
        }
    }

    private fun passesText(item: ShadeItem, showOnly: List<String>, hide: List<String>): Boolean {
        val hay = item.appName + "\n" + item.allText
        if (showOnly.isNotEmpty() && showOnly.none { hay.contains(it, ignoreCase = true) }) return false
        if (hide.any { hay.contains(it, ignoreCase = true) }) return false
        return true
    }

    /**
     * Entries in on-screen order, top to bottom: top-pinned apps, then the rest (newest nearest the
     * thumb when "newest at the bottom" is on), then bottom-pinned apps.
     */
    fun entries(all: List<ShadeItem>, s: AppSettings, priority: (ShadeItem) -> Float = { 0f }): List<Entry> {
        val items = visible(all, s)
        val grouped = items.filter { it.pkg in s.groupApps }.groupBy { it.pkg }
        val entries = mutableListOf<Entry>()
        items.filter { it.pkg !in s.groupApps }.forEach { entries += Single(it) }
        grouped.forEach { (pkg, list) ->
            entries += if (list.size == 1) Single(list.first()) else Group(pkg, list.first().appName, list.sortedByDescending { it.postTime })
        }
        val pinOrder = compareBy<Entry> {
            when (it.pkg) {
                in s.pinTop -> 0
                in s.pinBottom -> 2
                else -> 1
            }
        }
        if (!s.ai.smartOrder) return entries.sortedWith(pinOrder.thenBy { if (s.newestAtBottom) it.time else -it.time })
        // Smart order: the most important land nearest the thumb (the bottom when newest is at
        // the bottom, else the top); ties go by time.
        fun score(e: Entry): Float = when (e) {
            is Single -> priority(e.item)
            is Group -> e.items.maxOf(priority)
        }
        val scores = entries.associateWith(::score)
        return entries.sortedWith(
            pinOrder.thenBy { if (s.newestAtBottom) scores[it] else -(scores[it] ?: 0f) }
                .thenBy { if (s.newestAtBottom) it.time else -it.time }
        )
    }
}
