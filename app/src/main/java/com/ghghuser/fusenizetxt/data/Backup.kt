package com.ghghuser.fusenizetxt.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** zipによる定時バックアップ。古いものは指定した個数（最大100）を超えたら自動で削除する */
object Backup {
    const val DEFAULT_LABEL = "Documents/FusenizeTxtWidget-Backup"
    private const val REL_PATH = "Documents/FusenizeTxtWidget-Backup/"
    private const val PREFIX = "FusenizeTxt-backup-"
    private const val WORK = "fusenize_backup"

    data class Outcome(val ok: Boolean, val message: String)

    private val filesUri: Uri get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun destLabel(ctx: Context): String {
        val t = usableTree(ctx)
        return if (t != null) (DocumentFile.fromTreeUri(ctx, Uri.parse(t))?.name ?: "選択したフォルダ") else DEFAULT_LABEL
    }

    private fun usableTree(ctx: Context): String? {
        val t = Store(ctx).backupTree ?: return null
        val ok = ctx.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == t && it.isReadPermission && it.isWritePermission
        }
        return if (ok) t else null
    }

    /** 設定に合わせて定期実行を登録/解除する。replace=true なら間隔の変更を反映する */
    fun schedule(ctx: Context, replace: Boolean) {
        val store = Store(ctx)
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (!store.backupEnabled) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<BackupWorker>(store.backupHours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(
            WORK,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            req,
        )
    }

    /** バックアップを1回作る。force=false の場合、前回から内容が変わっていなければ作らない */
    fun runBackup(ctx: Context, force: Boolean): Outcome {
        val store = Store(ctx)
        val tmp = File(ctx.cacheDir, "backup.tmp")
        val outcome = try {
            val digest = MessageDigest.getInstance("SHA-256")
            val count = tmp.outputStream().use { ZipTools.export(ctx, it, digest) }
            val sig = digest.digest().joinToString("") { "%02x".format(it) }
            when {
                count == 0 -> Outcome(true, "バックアップするメモがありません")
                !force && sig == store.backupSig -> Outcome(true, "前回から変更がないためスキップしました")
                else -> {
                    val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
                    val usedTree = usableTree(ctx)
                    if (!write(ctx, name, tmp, usedTree)) {
                        Outcome(false, "バックアップ先に書き込めませんでした")
                    } else {
                        store.backupSig = sig
                        prune(ctx, store.backupKeep, usedTree)
                        val note = if (store.backupTree != null && usedTree == null) "（指定フォルダが使えないため既定の場所に保存）" else ""
                        Outcome(true, "${count}件をバックアップしました$note")
                    }
                }
            }
        } catch (e: Exception) {
            Outcome(false, "バックアップに失敗しました")
        } finally {
            tmp.delete()
        }
        val stamp = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())
        store.backupStatus = "$stamp ${outcome.message}"
        return outcome
    }

    private fun write(ctx: Context, name: String, src: File, tree: String?): Boolean {
        return try {
            if (tree != null) {
                val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(tree)) ?: return false
                val f = dir.createFile("application/zip", name) ?: return false
                ctx.contentResolver.openOutputStream(f.uri, "wt")?.use { o -> src.inputStream().use { it.copyTo(o) } } ?: return false
                true
            } else {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, REL_PATH)
                }
                val uri = ctx.contentResolver.insert(filesUri, cv) ?: return false
                ctx.contentResolver.openOutputStream(uri, "wt")?.use { o -> src.inputStream().use { it.copyTo(o) } } ?: return false
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 新しい順に [keep] 個だけ残し、それより古いバックアップを削除する */
    private fun prune(ctx: Context, keep: Int, tree: String?) {
        try {
            if (tree != null) {
                val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(tree)) ?: return
                dir.listFiles()
                    .filter { (it.name ?: "").let { n -> n.startsWith(PREFIX) && n.endsWith(".zip") } }
                    .sortedByDescending { it.name }
                    .drop(keep)
                    .forEach { it.delete() }
            } else {
                val old = ArrayList<Uri>()
                ctx.contentResolver.query(
                    filesUri,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf(REL_PATH, "$PREFIX%.zip"),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} DESC",
                )?.use { c ->
                    var i = 0
                    while (c.moveToNext()) {
                        if (i++ >= keep) old.add(android.content.ContentUris.withAppendedId(filesUri, c.getLong(0)))
                    }
                }
                old.forEach { ctx.contentResolver.delete(it, null, null) }
            }
        } catch (e: Exception) {
            // 削除に失敗しても次回また試す
        }
    }
}

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        withContext(Dispatchers.IO) { Backup.runBackup(applicationContext, force = false) }
        return Result.success()
    }
}
