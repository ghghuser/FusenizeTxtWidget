package com.ghghuser.fusenizetxt.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * メモごとの履歴（保存された版）。端末内（アプリ専用領域）にだけ保存し、txtには書かない。
 * 2分以内の連続した保存は1つの版にまとめ、最大 [MAX_PER_MEMO] 版まで残す。
 */
object History {
    const val MAX_PER_MEMO = 50
    private const val MERGE_MS = 120_000L
    private const val MAX_BYTES = 2 * 1024 * 1024

    data class Version(val file: File, val time: Long)

    private fun dirFor(ctx: Context, uri: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(ctx.filesDir, "history"), h)
    }

    @Synchronized
    fun list(ctx: Context, uri: Uri): List<Version> = listInternal(dirFor(ctx, uri.toString()))

    private fun listInternal(d: File): List<Version> =
        (d.listFiles() ?: emptyArray())
            .mapNotNull { f -> f.name.removeSuffix(".txt").toLongOrNull()?.let { Version(f, it) } }
            .sortedByDescending { it.time }

    fun read(v: Version): String? = try {
        v.file.readText()
    } catch (e: Exception) {
        null
    }

    /** 保存された内容を版として記録。直前の版と同じなら何もしない。force=true なら統合せず別の版にする */
    @Synchronized
    fun add(ctx: Context, uri: Uri, text: String, force: Boolean = false) {
        try {
            if (text.length > MAX_BYTES / 3) return
            val d = dirFor(ctx, uri.toString()).apply { mkdirs() }
            val versions = listInternal(d)
            val latest = versions.firstOrNull()
            if (latest != null && read(latest) == text) return
            val now = System.currentTimeMillis()
            if (!force && latest != null && now - latest.time < MERGE_MS) latest.file.delete()
            var t = now
            while (File(d, "$t.txt").exists()) t++
            File(d, "$t.txt").writeText(text)
            listInternal(d).drop(MAX_PER_MEMO).forEach { it.file.delete() }
        } catch (e: Exception) {
            // 履歴は補助機能のため失敗は無視
        }
    }

    @Synchronized
    fun rename(ctx: Context, old: String, new: String) {
        try {
            val o = dirFor(ctx, old)
            val n = dirFor(ctx, new)
            if (o.exists() && !n.exists()) o.renameTo(n)
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun clear(ctx: Context, uri: String) {
        try {
            dirFor(ctx, uri).deleteRecursively()
        } catch (e: Exception) {
        }
    }
}
