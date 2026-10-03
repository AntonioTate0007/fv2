package com.thumbshade.app.rules

import android.content.Context
import android.content.SharedPreferences
import com.thumbshade.app.data.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer

/** The user's rules, in the order they run. */
object RuleStore {
    private var prefs: SharedPreferences? = null
    private val serializer = ListSerializer(Rule.serializer())
    private val _rules = MutableStateFlow<List<Rule>>(emptyList())
    val rules: StateFlow<List<Rule>> = _rules

    fun init(context: Context) {
        val p = context.getSharedPreferences("rules", Context.MODE_PRIVATE)
        prefs = p
        p.getString("json", null)
            ?.let { runCatching { AppJson.decodeFromString(serializer, it) }.getOrNull() }
            ?.let { _rules.value = it }
    }

    @Synchronized
    fun update(transform: (List<Rule>) -> List<Rule>) {
        val next = transform(_rules.value)
        _rules.value = next
        prefs?.edit()?.putString("json", AppJson.encodeToString(serializer, next))?.apply()
    }

    fun upsert(rule: Rule) = update { list ->
        if (list.any { it.id == rule.id }) list.map { if (it.id == rule.id) rule else it } else list + rule
    }

    fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    fun move(id: String, delta: Int) = update { list ->
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) list else list.toMutableList().apply { add(j, removeAt(i)) }
    }

    fun duplicate(id: String) = update { list ->
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) list else list.toMutableList().apply {
            add(i + 1, list[i].copy(id = java.util.UUID.randomUUID().toString(), name = list[i].name + " (copy)"))
        }
    }
}

/** A short in-memory history of what rules did, shown on the Rules screen. */
object RuleLog {
    data class Entry(val time: Long, val text: String)

    private const val MAX = 200
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    @Synchronized
    fun add(text: String) {
        _entries.value = (listOf(Entry(System.currentTimeMillis(), text)) + _entries.value).take(MAX)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
