package com.ghghuser.fusenizetxt.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 保存先のtxtをzipにまとめる / zipから取り込む */
object ZipTools {
    private const val META_NAME = "_fusenize_meta.json"
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 3000
    private val badChars = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    class ZipContent(
        val items: List<Importer.Item>,
        val favorites: Set<String>,
        val created: Map<String, Long>,
        val skipped: Int,
    )

    /**
     * 現在の保存先のtxt（文字コードはそのまま）を書き出す。お気に入りと作成日時も一緒に入れる。
     * [digest] を渡すと、中身から変更の有無を判定するための値を更新する。戻り値は書き出した件数。
     */
    fun export(ctx: Context, out: OutputStream, digest: MessageDigest? = null): Int {
        val store = Store(ctx)
        val fav = store.favorites()
        val favNames = JSONArray()
        val created = JSONObject()
        val used = HashSet<String>()
        var n = 0
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            for (e in MemoRepo.listTxt(ctx).sortedBy { it.name }) {
                if (!used.add(e.name.lowercase())) continue
                val bytes = MemoRepo.readBytes(ctx, e.uri) ?: continue
                zip.putNextEntry(ZipEntry(e.name).apply { if (e.lastModified > 0) time = e.lastModified })
                zip.write(bytes)
                zip.closeEntry()
                digest?.update(e.name.toByteArray(Charsets.UTF_8))
                digest?.update(bytes)
                if (e.uri.toString() in fav) favNames.put(e.name)
                store.createdAt(e.uri.toString())?.let { created.put(e.name, it) }
                n++
            }
            zip.putNextEntry(ZipEntry(META_NAME))
            zip.write(
                JSONObject().put("version", 1).put("favorites", favNames).put("created", created)
                    .toString().toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
        return n
    }

    /** zipを読み、取り込み対象のtxtを返す。フォルダ構造は無視してファイル名だけを使う */
    fun readZip(input: InputStream): ZipContent {
        val items = ArrayList<Importer.Item>()
        var fav: Set<String> = emptySet()
        var created: Map<String, Long> = emptyMap()
        var skipped = 0
        var total = 0L
        var count = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                if (++count > MAX_ENTRIES) {
                    skipped++
                    break
                }
                val base = e.name.substringAfterLast('/').substringAfterLast('\\')
                val isMeta = base == META_NAME
                val isTxt = base.endsWith(".txt", ignoreCase = true)
                if (!isMeta && !isTxt) continue
                val bytes = readLimited(zip, MAX_ENTRY_BYTES)
                if (bytes == null) {
                    // 上限を超えるエントリ。展開を続けると時間と容量を浪費するため、ここで読み込みを打ち切る
                    skipped++
                    break
                }
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) {
                    skipped++
                    break
                }
                if (isMeta) {
                    try {
                        val o = JSONObject(String(bytes, Charsets.UTF_8))
                        val fa = o.optJSONArray("favorites")
                        if (fa != null) fav = (0 until fa.length()).map { fa.getString(it) }.toSet()
                        val co = o.optJSONObject("created")
                        if (co != null) created = co.keys().asSequence().associateWith { co.optLong(it) }
                    } catch (ex: Exception) {
                        // メタ情報が壊れていてもtxt自体は取り込む
                    }
                } else {
                    val name = base.replace(badChars, "_").trim()
                    if (name.isEmpty() || name == ".txt") {
                        skipped++
                    } else {
                        items.add(Importer.Item(Uri.parse("zip://entry/${items.size}"), name, bytes))
                    }
                }
            }
        }
        return ZipContent(items, fav, created, skipped)
    }

    /** 制限を超えたら即座に null（zip爆弾対策のため、実際に展開したバイト数で判定する） */
    private fun readLimited(input: InputStream, limit: Int): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(16 * 1024)
        while (true) {
            val r = input.read(tmp)
            if (r < 0) break
            if (buf.size() + r > limit) return null
            buf.write(tmp, 0, r)
        }
        return buf.toByteArray()
    }

    /** 取り込んだファイルに、zip内のお気に入り・作成日時を反映する */
    fun applyMeta(ctx: Context, pairs: List<Pair<Importer.Item, MemoRepo.Entry>>, content: ZipContent) {
        val store = Store(ctx)
        for ((item, entry) in pairs) {
            if (item.name in content.favorites) store.setFavorite(entry.uri.toString(), true)
            content.created[item.name]?.let { store.setCreatedAt(entry.uri.toString(), it) }
        }
    }
}
