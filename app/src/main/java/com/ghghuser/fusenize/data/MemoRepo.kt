package com.ghghuser.fusenize.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** SAFで選んだフォルダ内のtxtを読み書きする */
object MemoRepo {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private val numberRegex = Regex("^(\\d+)_")
    private val badChars = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    data class Entry(val uri: Uri, val name: String, val lastModified: Long)
    data class Saved(val uri: Uri, val name: String)

    class ReadError(message: String) : Exception(message)

    private fun treeDoc(ctx: Context): DocumentFile? {
        val t = Store(ctx).treeUri ?: return null
        return try {
            DocumentFile.fromTreeUri(ctx, Uri.parse(t))
        } catch (e: Exception) {
            null
        }
    }

    fun hasFolderPermission(ctx: Context): Boolean {
        val t = Store(ctx).treeUri ?: return false
        return ctx.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == t && it.isReadPermission && it.isWritePermission
        }
    }

    fun folderName(ctx: Context): String? = treeDoc(ctx)?.name

    fun listTxt(ctx: Context): List<Entry> {
        val dir = treeDoc(ctx) ?: return emptyList()
        return try {
            dir.listFiles().mapNotNull { f ->
                val n = f.name ?: return@mapNotNull null
                if (f.isFile && n.endsWith(".txt", ignoreCase = true)) Entry(f.uri, n, f.lastModified()) else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- 名前まわり ----
    fun numberOf(name: String?): Long? =
        name?.let { numberRegex.find(it)?.groupValues?.get(1)?.toLongOrNull() }

    fun hasNumberPrefix(name: String?): Boolean = numberOf(name) != null

    /** 一覧用の表示タイトル（連番と拡張子を除く） */
    fun displayTitle(name: String): String =
        name.removeSuffix(".txt").removeSuffix(".TXT").replace(numberRegex, "").ifEmpty { name }

    /** 1行目からファイル名用のタイトルを作る */
    fun titleFor(text: String): String {
        var first = text.lineSequence().firstOrNull() ?: ""
        first = first.trim().trimStart('☐', '☑').trim()
        first = first.replace(badChars, " ").replace(Regex("\\s+"), " ").trim()
        first = first.take(40).trim().trimEnd('.')
        return first.ifEmpty { "無題" }
    }

    fun sanitizeBase(s: String): String {
        var r = s.replace(badChars, " ").replace(Regex("\\s+"), " ").trim()
        if (r.endsWith(".txt", ignoreCase = true)) r = r.dropLast(4)
        return r.take(80).trim().trimEnd('.')
    }

    // ---- 単体ドキュメント ----
    fun displayName(ctx: Context, uri: Uri): String? = try {
        DocumentFile.fromSingleUri(ctx, uri)?.name
    } catch (e: Exception) {
        null
    }

    fun lastModified(ctx: Context, uri: Uri): Long = try {
        DocumentFile.fromSingleUri(ctx, uri)?.lastModified() ?: 0L
    } catch (e: Exception) {
        0L
    }

    fun exists(ctx: Context, uri: Uri): Boolean = try {
        DocumentFile.fromSingleUri(ctx, uri)?.exists() == true
    } catch (e: Exception) {
        false
    }

    /** UTF-8のみ対応。それ以外の文字コードは上書きで壊さないよう読み込みを拒否する */
    fun read(ctx: Context, uri: Uri): Result<String> {
        return try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(ReadError("ファイルを開けませんでした"))
            if (bytes.size > MAX_BYTES) return Result.failure(ReadError("ファイルが大きすぎます（2MBまで）"))
            val s = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            Result.success(s.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n'))
        } catch (e: CharacterCodingException) {
            Result.failure(ReadError("UTF-8以外の文字コードのため開けません"))
        } catch (e: Exception) {
            Result.failure(ReadError("読み込みに失敗しました"))
        }
    }

    fun createNew(ctx: Context): Saved? {
        val dir = treeDoc(ctx) ?: return null
        return try {
            val max = dir.listFiles().mapNotNull { numberOf(it.name) }.maxOrNull() ?: 0L
            val f = dir.createFile("text/plain", "${max + 1}_無題.txt") ?: return null
            Store(ctx).setCreatedAt(f.uri.toString(), System.currentTimeMillis())
            Saved(f.uri, f.name ?: "${max + 1}_無題.txt")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 本文を書き込み、自動命名モードならファイル名も更新する。
     * 書き込み失敗時は null（呼び出し側が再試行できるようにする）。
     */
    fun save(ctx: Context, uri: Uri, text: String, manualName: Boolean): Saved? {
        try {
            val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: return null
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            return null
        }
        var cur = uri
        var name = displayName(ctx, uri) ?: ""
        if (!manualName) {
            val num = numberOf(name)
            if (num != null) {
                val want = "${num}_${titleFor(text)}.txt"
                if (want != name) {
                    val r = renameInternal(ctx, cur, want)
                    if (r != null) {
                        cur = r.uri
                        name = r.name
                    }
                }
            }
        }
        return Saved(cur, name)
    }

    /** 手動でファイル名を決める（拡張子は自動で .txt） */
    fun renameTo(ctx: Context, uri: Uri, base: String): Saved? {
        val b = sanitizeBase(base)
        if (b.isEmpty()) return null
        return renameInternal(ctx, uri, "$b.txt")
    }

    private fun renameInternal(ctx: Context, uri: Uri, want: String): Saved? {
        val current = displayName(ctx, uri)
        if (current == want) return Saved(uri, want)
        val dir = treeDoc(ctx)
        var target = want
        if (dir != null) {
            try {
                val names = dir.listFiles().mapNotNull { it.name }.toSet()
                if (target in names) {
                    val base = want.removeSuffix(".txt")
                    var i = 2
                    while ("$base($i).txt" in names && i < 100) i++
                    target = "$base($i).txt"
                }
            } catch (e: Exception) {
                // 名前衝突の確認に失敗した場合はそのまま試す
            }
        }
        return try {
            val newUri = DocumentsContract.renameDocument(ctx.contentResolver, uri, target) ?: return null
            Store(ctx).replaceUri(uri.toString(), newUri.toString())
            Saved(newUri, displayName(ctx, newUri) ?: target)
        } catch (e: Exception) {
            null
        }
    }

    fun delete(ctx: Context, uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(ctx.contentResolver, uri)
    } catch (e: Exception) {
        false
    }
}

/** クラッシュ・強制終了に備えた端末内の下書き（成功保存で削除） */
object Drafts {
    data class Draft(val text: String, val time: Long)

    private fun file(ctx: Context, uri: Uri): File {
        val dir = File(ctx.filesDir, "drafts").apply { mkdirs() }
        val h = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$h.txt")
    }

    fun write(ctx: Context, uri: Uri, text: String) {
        try {
            file(ctx, uri).writeText(text)
        } catch (e: Exception) {
            // 下書きは補助機能のため失敗は無視
        }
    }

    fun read(ctx: Context, uri: Uri): Draft? = try {
        val f = file(ctx, uri)
        if (f.exists()) Draft(f.readText(), f.lastModified()) else null
    } catch (e: Exception) {
        null
    }

    fun delete(ctx: Context, uri: Uri) {
        try {
            file(ctx, uri).delete()
        } catch (e: Exception) {
        }
    }
}
