package com.ghghuser.fusenize.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * アプリ内部の設定保存（txtには書かない）。
 * ウィジェットID→設定、お気に入り、作成日時、最近開いたファイル、一覧の見た目。
 */
class Store(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("fusen", Context.MODE_PRIVATE)

    companion object {
        private val lock = Any()
        const val SORT_UPDATED = 0
        const val SORT_CREATED = 1
        const val SORT_NAME = 2
        private const val MAX_RECENTS = 100
    }

    // ---- 保存フォルダ ----
    var treeUri: String?
        get() = sp.getString("tree", null)
        set(v) {
            sp.edit().putString("tree", v).apply()
        }

    // ---- ウィジェット設定 ----
    fun getConfig(id: Int): WidgetConfig = synchronized(lock) { readConfig(id) }

    private fun readConfig(id: Int): WidgetConfig {
        val s = sp.getString("w_$id", null) ?: return WidgetConfig(id)
        return try {
            WidgetConfig.fromJson(id, JSONObject(s))
        } catch (e: Exception) {
            WidgetConfig(id)
        }
    }

    fun updateConfig(id: Int, f: (WidgetConfig) -> WidgetConfig) {
        synchronized(lock) {
            val next = f(readConfig(id))
            sp.edit().putString("w_$id", next.toJson().toString()).apply()
        }
    }

    fun removeConfig(id: Int) {
        synchronized(lock) { sp.edit().remove("w_$id").apply() }
    }

    fun allConfigs(): List<WidgetConfig> = synchronized(lock) {
        sp.all.keys.filter { it.startsWith("w_") }
            .mapNotNull { it.removePrefix("w_").toIntOrNull() }
            .map { readConfig(it) }
    }

    // ---- お気に入り ----
    fun favorites(): Set<String> = synchronized(lock) {
        sp.getStringSet("fav", emptySet())?.toSet() ?: emptySet()
    }

    fun setFavorite(uri: String, on: Boolean) {
        synchronized(lock) {
            val s = favorites().toMutableSet()
            if (on) s.add(uri) else s.remove(uri)
            sp.edit().putStringSet("fav", s).apply()
        }
    }

    // ---- 作成日時（SAFでは取得できないためアプリで作成した時刻を記録） ----
    fun createdAt(uri: String): Long? = synchronized(lock) {
        val o = readObj("created")
        if (o.has(uri)) o.optLong(uri) else null
    }

    fun setCreatedAt(uri: String, time: Long) {
        synchronized(lock) {
            val o = readObj("created")
            o.put(uri, time)
            sp.edit().putString("created", o.toString()).apply()
        }
    }

    private fun readObj(key: String): JSONObject = try {
        JSONObject(sp.getString(key, "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    // ---- 最近開いたファイル ----
    fun recents(): List<String> = synchronized(lock) { readRecents() }

    private fun readRecents(): List<String> = try {
        val a = JSONArray(sp.getString("recents", "[]") ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun addRecent(uri: String) {
        synchronized(lock) {
            val l = readRecents().toMutableList()
            l.remove(uri)
            l.add(0, uri)
            while (l.size > MAX_RECENTS) l.removeAt(l.size - 1)
            sp.edit().putString("recents", JSONArray(l).toString()).apply()
        }
    }

    // ---- ファイルのURIが変わった/消えた時の整合 ----
    fun replaceUri(old: String, new: String) {
        if (old == new) return
        synchronized(lock) {
            for (c in allConfigs()) {
                if (c.fileUri == old) {
                    sp.edit().putString("w_${c.widgetId}", c.copy(fileUri = new).toJson().toString()).apply()
                }
            }
            val fav = favorites()
            if (old in fav) {
                sp.edit().putStringSet("fav", (fav - old) + new).apply()
            }
            val created = readObj("created")
            if (created.has(old)) {
                val t = created.optLong(old)
                created.remove(old)
                created.put(new, t)
                sp.edit().putString("created", created.toString()).apply()
            }
            val r = readRecents().map { if (it == old) new else it }.distinct()
            sp.edit().putString("recents", JSONArray(r).toString()).apply()
        }
    }

    fun removeUri(uri: String) {
        synchronized(lock) {
            for (c in allConfigs()) {
                if (c.fileUri == uri) {
                    sp.edit().putString(
                        "w_${c.widgetId}",
                        c.copy(fileUri = null, manualName = false, cachedText = "").toJson().toString()
                    ).apply()
                }
            }
            sp.edit().putStringSet("fav", favorites() - uri).apply()
            val created = readObj("created")
            created.remove(uri)
            sp.edit().putString("created", created.toString()).apply()
            val r = readRecents().filter { it != uri }
            sp.edit().putString("recents", JSONArray(r).toString()).apply()
        }
    }

    // ---- 一覧画面の見た目 ----
    var sortMode: Int
        get() = sp.getInt("sort", SORT_UPDATED)
        set(v) {
            sp.edit().putInt("sort", v).apply()
        }

    /** null = テーマ既定色 */
    var listBgColor: Int?
        get() = if (sp.contains("listBg")) sp.getInt("listBg", 0) else null
        set(v) {
            if (v == null) sp.edit().remove("listBg").apply() else sp.edit().putInt("listBg", v and 0xFFFFFF).apply()
        }

    var listBgAlpha: Int
        get() = sp.getInt("listBgA", 255)
        set(v) {
            sp.edit().putInt("listBgA", v.coerceIn(0, 255)).apply()
        }
}
