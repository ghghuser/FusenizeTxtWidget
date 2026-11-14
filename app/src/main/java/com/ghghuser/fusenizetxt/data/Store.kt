package com.ghghuser.fusenizetxt.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * アプリ内部の設定保存（txtには書かない）。
 * ウィジェットID→設定、お気に入り、作成日時、最近開いたファイル、一覧の見た目。
 */
/** ファイルごとの文字コード・改行コード（UTF-8/LF以外のときだけ保存） */
data class FileMeta(val charset: String = "UTF-8", val crlf: Boolean = false)

class Store(ctx: Context) {
    private val app = ctx.applicationContext
    private val sp = app.getSharedPreferences("fusen", Context.MODE_PRIVATE)

    companion object {
        private val lock = Any()
        const val SORT_UPDATED = 0
        const val SORT_CREATED = 1
        const val SORT_NAME = 2
        private const val MAX_RECENTS = 100
        const val DEFAULT_FOLDER = "FusenizeTxt"
        private val badFolderChars = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

        /** フォルダ名として使えない文字を除く。空・「.」「..」は null */
        fun sanitizeFolder(s: String): String? {
            val c = s.replace(badFolderChars, "").trim().trim('.').take(60).trim()
            return c.ifEmpty { null }
        }
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

    // ---- 文字コード・改行コード ----
    fun meta(uri: String): FileMeta = synchronized(lock) {
        val o = readObj("meta").optJSONObject(uri) ?: return FileMeta()
        FileMeta(o.optString("cs", "UTF-8").ifEmpty { "UTF-8" }, o.optBoolean("crlf", false))
    }

    fun setMeta(uri: String, m: FileMeta) {
        synchronized(lock) {
            val all = readObj("meta")
            if (m == FileMeta()) all.remove(uri)
            else all.put(uri, JSONObject().put("cs", m.charset).put("crlf", m.crlf))
            sp.edit().putString("meta", all.toString()).apply()
        }
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
                if (c.pages.any { it.fileUri == old }) {
                    val n = c.copy(pages = c.pages.map { if (it.fileUri == old) it.copy(fileUri = new) else it })
                    sp.edit().putString("w_${c.widgetId}", n.toJson().toString()).apply()
                }
            }
            History.rename(app, old, new)
            if (isManual(old)) {
                sp.edit().putStringSet("manualNames", (manualSet() - old) + new).apply()
            }
            Reminders.rename(app, old, new)
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
            val metaAll = readObj("meta")
            if (metaAll.has(old)) {
                val m = metaAll.get(old)
                metaAll.remove(old)
                metaAll.put(new, m)
                sp.edit().putString("meta", metaAll.toString()).apply()
            }
            val r = readRecents().map { if (it == old) new else it }.distinct()
            sp.edit().putString("recents", JSONArray(r).toString()).apply()
        }
    }

    fun removeUri(uri: String) {
        synchronized(lock) {
            for (c in allConfigs()) {
                if (c.pages.any { it.fileUri == uri }) {
                    val n = c.copy(pages = c.pages.map {
                        if (it.fileUri == uri) PageCfg(bgColor = it.bgColor) else it
                    })
                    sp.edit().putString("w_${c.widgetId}", n.toJson().toString()).apply()
                }
            }
            History.clear(app, uri)
            sp.edit().putStringSet("manualNames", manualSet() - uri).apply()
            Reminders.clear(app, uri)
            sp.edit().putStringSet("fav", favorites() - uri).apply()
            val created = readObj("created")
            created.remove(uri)
            sp.edit().putString("created", created.toString()).apply()
            val metaAll = readObj("meta")
            metaAll.remove(uri)
            sp.edit().putString("meta", metaAll.toString()).apply()
            val r = readRecents().filter { it != uri }
            sp.edit().putString("recents", JSONArray(r).toString()).apply()
        }
    }

    // ---- 定時バックアップ ----
    var backupEnabled: Boolean
        get() = sp.getBoolean("bk_on", false)
        set(v) {
            sp.edit().putBoolean("bk_on", v).apply()
        }

    var backupHours: Int
        get() = sp.getInt("bk_h", 24)
        set(v) {
            sp.edit().putInt("bk_h", v.coerceIn(1, 720)).apply()
        }

    /** 残す個数（1〜100）。古いものから自動で削除 */
    var backupKeep: Int
        get() = sp.getInt("bk_keep", 10).coerceIn(1, 100)
        set(v) {
            sp.edit().putInt("bk_keep", v.coerceIn(1, 100)).apply()
        }

    /** バックアップ先フォルダ（null = Documents/FusenizeTxtWidget-Backup） */
    var backupTree: String?
        get() = sp.getString("bk_tree", null)
        set(v) {
            if (v == null) sp.edit().remove("bk_tree").apply() else sp.edit().putString("bk_tree", v).apply()
        }

    var backupSig: String?
        get() = sp.getString("bk_sig", null)
        set(v) {
            sp.edit().putString("bk_sig", v).apply()
        }

    var backupStatus: String
        get() = sp.getString("bk_status", "") ?: ""
        set(v) {
            sp.edit().putString("bk_status", v).apply()
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
        get() = sp.getInt("listBgA", 200)
        set(v) {
            sp.edit().putInt("listBgA", v.coerceIn(0, 255)).apply()
        }

    /** アプリで開いた時の編集欄の色。null = 一覧の配色に合わせる */
    var editorBgColor: Int?
        get() = if (sp.contains("editorBg")) sp.getInt("editorBg", 0) else null
        set(v) {
            if (v == null) sp.edit().remove("editorBg").apply() else sp.edit().putInt("editorBg", v and 0xFFFFFF).apply()
        }

    /** 画面の明るさをアプリ内で固定するか（既定はオフ） */
    var brightnessLock: Boolean
        get() = sp.getBoolean("brLock", false)
        set(v) {
            sp.edit().putBoolean("brLock", v).apply()
        }

    var brightnessLevel: Int
        get() = sp.getInt("brLevel", 50)
        set(v) {
            sp.edit().putInt("brLevel", v.coerceIn(5, 100)).apply()
        }

    /** 保存フォルダ名（既定 FusenizeTxt）。未設定の保存先では Documents/<名前>、フォルダ指定時はその中（または同名のそのフォルダ）を使う */
    var folderName: String
        get() = sp.getString("folderName", null)?.let { sanitizeFolder(it) } ?: DEFAULT_FOLDER
        set(v) {
            val c = sanitizeFolder(v)
            if (c == null || c == DEFAULT_FOLDER) sp.edit().remove("folderName").apply()
            else sp.edit().putString("folderName", c).apply()
        }

    /** 直近の保存先作成の問題（原因の切り分け用。問題がなければ空） */
    var storageNote: String
        get() = sp.getString("storageNote", "") ?: ""
        set(v) {
            sp.edit().putString("storageNote", v.take(400)).apply()
        }

    /** アプリ側（一覧・編集欄）の紙の質感の強さ 0〜100（0でなし） */
    var appPaper: Int
        get() = sp.getInt("appPaper", 70)
        set(v) {
            sp.edit().putInt("appPaper", v.coerceIn(0, 100)).apply()
        }

    /** 編集画面で行番号を表示するか */
    var lineNumbers: Boolean
        get() = sp.getBoolean("lineNo", false)
        set(v) {
            sp.edit().putBoolean("lineNo", v).apply()
        }

    // ---- 自分で名前を付けた（固定した）ファイル ----
    fun manualSet(): Set<String> = synchronized(lock) { sp.getStringSet("manualNames", emptySet()) ?: emptySet() }

    fun isManual(uri: String): Boolean = uri in manualSet()

    fun setManual(uri: String, on: Boolean) {
        synchronized(lock) {
            val cur = manualSet()
            sp.edit().putStringSet("manualNames", if (on) cur + uri else cur - uri).apply()
        }
    }

    /** 並び順の向きを既定から反転するか（日時は新しい順、名前は昇順が既定） */
    var sortReverse: Boolean
        get() = sp.getBoolean("sortRev", false)
        set(v) {
            sp.edit().putBoolean("sortRev", v).apply()
        }

    /** ウィジェットの編集画面での行番号表示（アプリ側は lineNumbers） */
    var lineNumbersWidget: Boolean
        get() = sp.getBoolean("lineNoW", false)
        set(v) {
            sp.edit().putBoolean("lineNoW", v).apply()
        }
}
