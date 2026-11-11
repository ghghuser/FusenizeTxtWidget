package com.ghghuser.fusenizetxt.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * txtの取り込み（ファイル名は変更しない）と、保存先変更時の移動。
 * 同名ファイルがある場合は「上書き」か「連番を付けて保存（名前(2).txt）」を選べる。
 */
object Importer {
    class Item(val uri: Uri, val name: String, val bytes: ByteArray)

    data class ConflictDecision(val mode: MemoRepo.OnConflict, val applyAll: Boolean)

    data class Summary(
        val done: Int = 0,
        val overwritten: Int = 0,
        val failed: Int = 0,
        val cancelled: Boolean = false,
        val entries: List<MemoRepo.Entry> = emptyList(),
    ) {
        fun describe(verb: String): String {
            val sb = StringBuilder("${done}件を${verb}しました")
            if (overwritten > 0) sb.append("（うち上書き${overwritten}件）")
            if (failed > 0) sb.append("、失敗${failed}件")
            if (cancelled) sb.append("（途中で中止）")
            return sb.toString()
        }
    }

    fun readItem(ctx: Context, uri: Uri): Item? {
        val name = MemoRepo.displayName(ctx, uri) ?: return null
        val bytes = MemoRepo.readBytes(ctx, uri) ?: return null
        val fixed = if (name.endsWith(".txt", ignoreCase = true)) name else "$name.txt"
        return Item(uri, fixed, bytes)
    }

    /** 同じドキュメントを指すURIか（ピッカーのURIと保存フォルダ内のURIは文字列が異なるため） */
    private fun sameDoc(a: Uri, b: Uri): Boolean {
        if (a == b) return true
        return try {
            a.authority == b.authority && DocumentsContract.getDocumentId(a) == DocumentsContract.getDocumentId(b)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * [ask] は同名ファイルがあるたびに呼ばれ、null を返すと残りの取り込みを中止する。
     * applyAll = true の判断は以降の重複すべてに使い回す。
     * [move] が true なら、成功したものは元ファイルを削除し、ウィジェット等の紐付けも新しい場所へ付け替える。
     */
    suspend fun run(
        ctx: Context,
        items: List<Item>,
        move: Boolean,
        ask: suspend (name: String, remaining: Int) -> ConflictDecision?,
    ): Summary {
        val taken = withContext(Dispatchers.IO) {
            MemoRepo.listTxt(ctx).associateBy { it.name.lowercase() }.toMutableMap()
        }
        var sticky: ConflictDecision? = null
        var done = 0
        var over = 0
        var failed = 0
        val entries = ArrayList<MemoRepo.Entry>()

        for ((i, item) in items.withIndex()) {
            val conflict = taken[item.name.lowercase()]
            if (conflict != null && sameDoc(conflict.uri, item.uri)) {
                // すでに保存先にあるファイル自身。何も書かずにそのまま使う
                done++
                entries.add(conflict)
                continue
            }
            var mode = MemoRepo.OnConflict.RENAME
            if (conflict != null) {
                val d = sticky ?: ask(item.name, items.size - i - 1)
                    ?: return Summary(done, over, failed, true, entries)
                if (d.applyAll) sticky = d
                mode = d.mode
            }
            val e = withContext(Dispatchers.IO) {
                MemoRepo.importOne(ctx, item.bytes, item.name, conflict, mode, taken)
            }
            if (e == null) {
                failed++
                continue
            }
            done++
            if (conflict != null && mode == MemoRepo.OnConflict.OVERWRITE) over++
            entries.add(e)
            if (move && e.uri != item.uri) {
                withContext(Dispatchers.IO) {
                    Store(ctx).replaceUri(item.uri.toString(), e.uri.toString())
                    MemoRepo.delete(ctx, item.uri)
                }
            }
        }
        return Summary(done, over, failed, false, entries)
    }

    /** 保存フォルダを設定した後、Documents/FusenizeTxtWidget にあったメモを新しい保存先へ移動する */
    suspend fun migrateDefault(
        ctx: Context,
        ask: suspend (name: String, remaining: Int) -> ConflictDecision?,
    ): Summary? {
        if (!MemoRepo.usingTree(ctx)) return null
        val items = withContext(Dispatchers.IO) {
            MemoRepo.listDefault(ctx).mapNotNull { e ->
                MemoRepo.readBytes(ctx, e.uri)?.let { Item(e.uri, e.name, it) }
            }
        }
        if (items.isEmpty()) return null
        return run(ctx, items, true, ask)
    }
}
