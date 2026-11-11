package com.ghghuser.fusenizetxt.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * txtの読み書き。
 *  - 保存フォルダ（SAF）が設定されていればそのフォルダ
 *  - 未設定なら MediaStore 経由で Documents/FusenizeTxtWidget に自動作成して保存
 * どちらのURIでも同じ関数で扱えるようにしている。
 */
object MemoRepo {
    const val DEFAULT_FOLDER_LABEL = "Documents/FusenizeTxtWidget"
    private const val REL_PATH = "Documents/FusenizeTxtWidget/"
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_COPY_BYTES = 8 * 1024 * 1024
    private const val MAX_ORIG_BACKUPS = 100
    private val numberRegex = Regex("^(\\d+)_")
    private val badChars = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    data class Entry(val uri: Uri, val name: String, val lastModified: Long)
    data class Saved(val uri: Uri, val name: String)
    data class FileText(val text: String, val crlf: Boolean)
    data class SaveOutcome(val saved: Saved?, val encodeError: Boolean = false)

    class ReadError(message: String) : Exception(message)

    /** UTF-8として読めなかった（文字コードの確認が必要） */
    class EncodingException(val bytes: ByteArray) : Exception("UTF-8以外の文字コードです")

    enum class OnConflict { RENAME, OVERWRITE }

    /** 文字コードの候補（表示名つき） */
    val ENCODINGS: List<Pair<String, String>> = listOf(
        "windows-31j" to "Shift_JIS",
        "EUC-JP" to "EUC-JP",
        "ISO-2022-JP" to "JIS (ISO-2022-JP)",
        "UTF-16LE" to "UTF-16 LE",
        "UTF-16BE" to "UTF-16 BE",
    )

    // ------------------------------------------------------------
    // 保存先
    // ------------------------------------------------------------

    private val filesUri: Uri get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun isMedia(uri: Uri): Boolean = uri.authority == MediaStore.AUTHORITY

    /**
     * アプリ内のフォルダに作ったメモ（file://）。MediaStore（Documents/FusenizeTxtWidget）に作ったメモが
     * 作成直後に読めない端末で、自動的にこちらへ切り替える。保存フォルダを設定すると移動される。
     */
    fun isLocal(uri: Uri): Boolean = uri.scheme == "file"

    private fun localDir(ctx: Context): File = File(ctx.filesDir, "memos").apply { mkdirs() }

    private fun listLocal(ctx: Context): List<Entry> = try {
        (localDir(ctx).listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".txt", ignoreCase = true) }
            .map { Entry(Uri.fromFile(it), it.name, it.lastModified()) }
    } catch (e: Exception) {
        emptyList()
    }

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

    /** SAFの保存フォルダを使っているか（false なら Documents/FusenizeTxtWidget） */
    fun usingTree(ctx: Context): Boolean = hasFolderPermission(ctx) && treeDoc(ctx) != null

    fun storageLabel(ctx: Context): String =
        if (usingTree(ctx)) (treeDoc(ctx)?.name ?: "選択したフォルダ") else DEFAULT_FOLDER_LABEL

    // ------------------------------------------------------------
    // 一覧
    // ------------------------------------------------------------

    /** 現在の保存先のtxt一覧 */
    fun listTxt(ctx: Context): List<Entry> = if (usingTree(ctx)) listTree(ctx) else listMedia(ctx)

    /** 自動作成フォルダ（Documents/FusenizeTxtWidget）内のtxt一覧 */
    fun listDefault(ctx: Context): List<Entry> = listMedia(ctx)

    private fun listTree(ctx: Context): List<Entry> {
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

    private fun listMedia(ctx: Context): List<Entry> {
        val out = ArrayList<Entry>()
        try {
            val proj = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
            )
            ctx.contentResolver.query(
                filesUri, proj, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(REL_PATH), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(1) ?: continue
                    if (!n.endsWith(".txt", ignoreCase = true)) continue
                    out.add(Entry(ContentUris.withAppendedId(filesUri, c.getLong(0)), n, c.getLong(2) * 1000))
                }
            }
        } catch (e: Exception) {
            // 一覧を取れなければ空として扱う
        }
        return out + listLocal(ctx)
    }

    // ------------------------------------------------------------
    // 名前まわり
    // ------------------------------------------------------------

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

    /** "name.txt" → "name(2).txt"（既存名と重なる間、連番を増やす） */
    fun uniqueName(name: String, takenLower: Set<String>): String {
        if (name.lowercase() !in takenLower) return name
        val base = name.removeSuffix(".txt").removeSuffix(".TXT")
        var i = 2
        while ("$base($i).txt".lowercase() in takenLower && i < 10000) i++
        return "$base($i).txt"
    }

    // ------------------------------------------------------------
    // 単体ドキュメントの情報
    // ------------------------------------------------------------

    private fun queryMediaLong(ctx: Context, uri: Uri, col: String): Long? = try {
        ctx.contentResolver.query(uri, arrayOf(col), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    } catch (e: Exception) {
        null
    }

    fun displayName(ctx: Context, uri: Uri): String? = try {
        if (isLocal(uri)) {
            File(uri.path ?: "").name
        } else if (isMedia(uri)) {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } else DocumentFile.fromSingleUri(ctx, uri)?.name
    } catch (e: Exception) {
        null
    }

    fun lastModified(ctx: Context, uri: Uri): Long = try {
        if (isLocal(uri)) File(uri.path ?: "").lastModified()
        else if (isMedia(uri)) (queryMediaLong(ctx, uri, MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L) * 1000
        else DocumentFile.fromSingleUri(ctx, uri)?.lastModified() ?: 0L
    } catch (e: Exception) {
        0L
    }

    fun exists(ctx: Context, uri: Uri): Boolean = try {
        if (isLocal(uri)) File(uri.path ?: "").exists()
        else if (isMedia(uri)) queryMediaLong(ctx, uri, MediaStore.MediaColumns._ID) != null
        else DocumentFile.fromSingleUri(ctx, uri)?.exists() == true
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------
    // 文字コード
    // ------------------------------------------------------------

    private fun strictDecode(bytes: ByteArray, cs: Charset): String =
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()

    /** 指定した文字コードで厳密にデコード。失敗したら null */
    fun tryDecode(bytes: ByteArray, charsetName: String): String? = try {
        strictDecode(bytes, Charset.forName(charsetName)).removePrefix("﻿")
            .replace("\r\n", "\n").replace('\r', '\n')
    } catch (e: Exception) {
        null
    }

    /** 候補のうち最もそれらしいものを返す */
    fun guessEncoding(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return "UTF-16LE"
            if (b0 == 0xFE && b1 == 0xFF) return "UTF-16BE"
        }
        for (name in listOf("windows-31j", "EUC-JP", "ISO-2022-JP")) {
            if (tryDecode(bytes, name) != null) return name
        }
        return "windows-31j"
    }

    /** ファイルの文字コード・改行コードに合わせて保存用のバイト列にする。変換できない文字があれば null */
    private fun encode(text: String, meta: FileMeta): ByteArray? {
        val t = if (meta.crlf) text.replace("\n", "\r\n") else text
        val cs = try {
            Charset.forName(meta.charset)
        } catch (e: Exception) {
            Charsets.UTF_8
        }
        if (cs == Charsets.UTF_8) return t.toByteArray(Charsets.UTF_8)
        return try {
            val bb = cs.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(t))
            ByteArray(bb.remaining()).also { bb.get(it) }
        } catch (e: CharacterCodingException) {
            null
        }
    }

    // ------------------------------------------------------------
    // 読み込み
    // ------------------------------------------------------------

    /**
     * ファイルを読む。保存済みの文字コード設定があればそれで、なければUTF-8で。
     * UTF-8として読めない場合は EncodingException（呼び出し側で文字コードを確認する）。
     */
    fun readText(ctx: Context, uri: Uri): Result<FileText> {
        return try {
            val bytes = openIn(ctx, uri).use { it.readBytes() }
            if (bytes.size > MAX_BYTES) return Result.failure(ReadError("ファイルが大きすぎます（2MBまで）"))
            val meta = Store(ctx).meta(uri.toString())
            val decoded = tryDecode(bytes, meta.charset) ?: return Result.failure(EncodingException(bytes))
            Result.success(FileText(decoded, meta.crlf || hasCrlf(bytes, meta.charset)))
        } catch (e: Exception) {
            Result.failure(ReadError("読み込めません（${describe(e)}）"))
        }
    }

    /** 失敗の原因を短く表す（画面に出して原因を切り分けられるようにする） */
    fun describe(e: Throwable): String = "${e.javaClass.simpleName}: ${e.message ?: ""}".take(140)

    /**
     * 読み取り用のストリームを開く。通常の方法で開けなければ、ファイル記述子、
     * MediaStoreのファイルパスの順に試す。すべて失敗したら最初の例外を投げる。
     */
    private fun openIn(ctx: Context, uri: Uri): InputStream {
        var first: Exception? = null
        try {
            ctx.contentResolver.openInputStream(uri)?.let { return it }
        } catch (e: Exception) {
            first = e
        }
        try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.let { return ParcelFileDescriptor.AutoCloseInputStream(it) }
        } catch (e: Exception) {
            if (first == null) first = e
        }
        if (isMedia(uri)) {
            try {
                @Suppress("DEPRECATION")
                val path = ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                if (path != null) return File(path).inputStream()
            } catch (e: Exception) {
                if (first == null) first = e
            }
        }
        throw first ?: FileNotFoundException("ストリームを開けませんでした")
    }

    /** 読み込めるか確認する。読めれば null、読めなければ原因の文字列（文字コードの確認が必要な場合は読めた扱い） */
    fun verifyReadable(ctx: Context, uri: Uri): String? {
        val r = readText(ctx, uri)
        if (r.isSuccess || r.exceptionOrNull() is EncodingException) return null
        return r.exceptionOrNull()?.message ?: "読み込めません"
    }

    fun hasCrlf(bytes: ByteArray, charset: String): Boolean {
        val s = try {
            strictDecode(bytes, Charset.forName(charset))
        } catch (e: Exception) {
            return false
        }
        return s.contains("\r\n")
    }

    // ------------------------------------------------------------
    // 作成・保存・名前変更・削除
    // ------------------------------------------------------------

    private fun takenNames(ctx: Context): MutableSet<String> =
        listTxt(ctx).map { it.name.lowercase() }.toMutableSet()

    /** 現在の保存先に、名前を指定して空でない/空のファイルを新規作成する（名前は重複しない前提） */
    private fun createDoc(ctx: Context, name: String, bytes: ByteArray?): Saved? {
        try {
            if (usingTree(ctx)) {
                val dir = treeDoc(ctx) ?: return null
                val f = dir.createFile("text/plain", name) ?: return null
                if (bytes != null) {
                    ctx.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(bytes) } ?: return null
                }
                return Saved(f.uri, f.name ?: name)
            }
            // Documents/FusenizeTxtWidget に作る。作成直後に読めなければ破棄してアプリ内フォルダに作る
            val viaMedia = try {
                createInMediaStore(ctx, name, bytes)
            } catch (e: Exception) {
                null
            }
            return viaMedia ?: createLocal(ctx, name, bytes)
        } catch (e: Exception) {
            return null
        }
    }

    private fun createInMediaStore(ctx: Context, name: String, bytes: ByteArray?): Saved? {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, REL_PATH)
        }
        val uri = ctx.contentResolver.insert(filesUri, cv) ?: return null
        try {
            if (bytes != null) {
                ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: throw FileNotFoundException("書き込み用ストリームを開けませんでした")
            }
            openIn(ctx, uri).use { it.read() } // 作成直後に読めることを確認する
        } catch (e: Exception) {
            try {
                ctx.contentResolver.delete(uri, null, null)
            } catch (e2: Exception) {
            }
            return null
        }
        return Saved(uri, displayName(ctx, uri) ?: name)
    }

    private fun createLocal(ctx: Context, name: String, bytes: ByteArray?): Saved? = try {
        val f = File(localDir(ctx), name)
        if (f.exists()) null else {
            f.writeBytes(bytes ?: ByteArray(0))
            Saved(Uri.fromFile(f), f.name)
        }
    } catch (e: Exception) {
        null
    }

    fun createNew(ctx: Context): Saved? {
        val max = listTxt(ctx).mapNotNull { numberOf(it.name) }.maxOrNull() ?: 0L
        val saved = createDoc(ctx, "${max + 1}_無題.txt", null) ?: return null
        Store(ctx).setCreatedAt(saved.uri.toString(), System.currentTimeMillis())
        return saved
    }

    /**
     * 本文を書き込み、自動命名モードならファイル名も更新する。
     * 文字コードで表せない文字が含まれる場合は何も書かずに encodeError を返す。
     */
    fun save(ctx: Context, uri: Uri, text: String, manualName: Boolean): SaveOutcome {
        val meta = Store(ctx).meta(uri.toString())
        val bytes = encode(text, meta) ?: return SaveOutcome(null, encodeError = true)
        try {
            val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: return SaveOutcome(null)
            out.use { it.write(bytes) }
        } catch (e: Exception) {
            return SaveOutcome(null)
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
        return SaveOutcome(Saved(cur, name))
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
        val taken = takenNames(ctx)
        current?.let { taken.remove(it.lowercase()) }
        val target = uniqueName(want, taken)
        return try {
            if (isLocal(uri)) {
                val src = File(uri.path ?: return null)
                val dst = File(src.parentFile, target)
                if (!src.renameTo(dst)) return null
                val newUri = Uri.fromFile(dst)
                Store(ctx).replaceUri(uri.toString(), newUri.toString())
                Saved(newUri, dst.name)
            } else if (isMedia(uri)) {
                val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, target) }
                if (ctx.contentResolver.update(uri, cv, null, null) <= 0) return null
                Saved(uri, displayName(ctx, uri) ?: target)
            } else {
                val newUri = DocumentsContract.renameDocument(ctx.contentResolver, uri, target) ?: return null
                Store(ctx).replaceUri(uri.toString(), newUri.toString())
                Saved(newUri, displayName(ctx, newUri) ?: target)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun delete(ctx: Context, uri: Uri): Boolean = try {
        if (isLocal(uri)) File(uri.path ?: "").delete()
        else if (isMedia(uri)) ctx.contentResolver.delete(uri, null, null) > 0
        else DocumentsContract.deleteDocument(ctx.contentResolver, uri)
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------
    // UTF-8への変換（元ファイルは先に端末内へバックアップ）
    // ------------------------------------------------------------

    /**
     * 元のバイト列をバックアップしてから、本文をUTF-8で書き直す。
     * 改行コードは元のまま保つ。成功したら Store のメタ情報もUTF-8に更新する。
     */
    fun convertToUtf8(ctx: Context, uri: Uri, text: String, crlf: Boolean): Boolean {
        try {
            val original = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
            backupOriginal(ctx, displayName(ctx, uri) ?: "memo.txt", original)
            val newMeta = FileMeta("UTF-8", crlf)
            val bytes = encode(text, newMeta) ?: return false
            val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: return false
            out.use { it.write(bytes) }
            Store(ctx).setMeta(uri.toString(), newMeta)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun backupOriginal(ctx: Context, name: String, bytes: ByteArray) {
        val dir = File(ctx.filesDir, "orig_backup").apply { mkdirs() }
        File(dir, "${System.currentTimeMillis()}_${sanitizeBase(name)}.bak").writeBytes(bytes)
        val all = dir.listFiles()?.sortedBy { it.name } ?: return
        if (all.size > MAX_ORIG_BACKUPS) all.take(all.size - MAX_ORIG_BACKUPS).forEach { it.delete() }
    }

    // ------------------------------------------------------------
    // 取り込み（txtのコピー）/ 移動
    // ------------------------------------------------------------

    fun readBytes(ctx: Context, uri: Uri): ByteArray? = try {
        openIn(ctx, uri).use { it.readBytes() }.takeIf { it.size <= MAX_COPY_BYTES }
    } catch (e: Exception) {
        null
    }

    /**
     * 現在の保存先へ1ファイル取り込む。バイト列はそのまま（文字コードは変更しない）。
     * conflict が非null（同名あり）なら mode に従って上書きまたは連番名で作成。
     * [taken] は保存先の既存名（小文字）→Entry。作成後に更新する。
     */
    fun importOne(
        ctx: Context,
        bytes: ByteArray,
        name: String,
        conflict: Entry?,
        mode: OnConflict,
        taken: MutableMap<String, Entry>,
    ): Entry? {
        if (conflict != null && mode == OnConflict.OVERWRITE) {
            return try {
                ctx.contentResolver.openOutputStream(conflict.uri, "wt")?.use { it.write(bytes) } ?: return null
                Store(ctx).setMeta(conflict.uri.toString(), FileMeta()) // 中身が変わるので文字コード設定をリセット
                conflict
            } catch (e: Exception) {
                null
            }
        }
        val finalName = uniqueName(name, taken.keys)
        val saved = createDoc(ctx, finalName, bytes) ?: return null
        Store(ctx).setCreatedAt(saved.uri.toString(), System.currentTimeMillis())
        val e = Entry(saved.uri, saved.name, System.currentTimeMillis())
        taken[saved.name.lowercase()] = e
        return e
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
