package com.ghghuser.fusenizetxt.ui

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ghghuser.fusenizetxt.data.Backup
import com.ghghuser.fusenizetxt.data.Importer
import com.ghghuser.fusenizetxt.data.MemoRepo
import com.ghghuser.fusenizetxt.data.Store
import com.ghghuser.fusenizetxt.data.rgbToArgb
import androidx.compose.ui.graphics.Color
import com.ghghuser.fusenizetxt.data.ZipTools
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 保存先・取り込み・zipバックアップ・一覧の見た目をまとめた設定画面 */
@OptIn(ExperimentalMaterial3Api::class)
class SettingsActivity : ComponentActivity() {
    private val importer = ImportCoordinator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FusenTheme { SettingsScreen() } }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private suspend fun importTxt(uris: List<Uri>) {
        val ctx = applicationContext
        val items = withContext(Dispatchers.IO) { uris.mapNotNull { Importer.readItem(ctx, it) } }
        if (items.isEmpty()) {
            toast("取り込めるtxtがありませんでした")
            return
        }
        val r = Importer.run(ctx, items, false) { n, rem -> importer.askConflict(n, rem) }
        toast(r.describe("取り込み"))
    }

    private suspend fun importZip(uri: Uri) {
        val ctx = applicationContext
        val content = withContext(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { ZipTools.readZip(it) }
            } catch (e: Exception) {
                null
            }
        }
        if (content == null || content.items.isEmpty()) {
            toast("zipからtxtを取り込めませんでした")
            return
        }
        val r = Importer.run(ctx, content.items, false) { n, rem -> importer.askConflict(n, rem) }
        withContext(Dispatchers.IO) { ZipTools.applyMeta(ctx, r.pairs, content) }
        toast(r.describe("復元") + if (content.skipped > 0) "（サイズ超過などで${content.skipped}件は除外）" else "")
    }

    private suspend fun exportZip(uri: Uri) {
        val ctx = applicationContext
        val n = withContext(Dispatchers.IO) {
            try {
                val c = contentResolver.openOutputStream(uri, "wt")?.use { ZipTools.export(ctx, it) } ?: -1
                if (c < 0) runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                c
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                -1
            }
        }
        toast(if (n >= 0) "${n}件のメモをzipに書き出しました" else "zipの書き出しに失敗しました")
    }

    override fun onResume() {
        super.onResume()
        com.ghghuser.fusenizetxt.data.Brightness.apply(this)
    }

    @Composable
    private fun SettingsScreen() {
        val store = remember { Store(this) }
        var storage by remember { mutableStateOf(MemoRepo.storageLabel(this)) }
        var bkOn by remember { mutableStateOf(store.backupEnabled) }
        var bkHours by remember { mutableIntStateOf(store.backupHours) }
        var bkKeep by remember { mutableIntStateOf(store.backupKeep) }
        var bkDest by remember { mutableStateOf(Backup.destLabel(this)) }
        var bkStatus by remember { mutableStateOf(store.backupStatus) }
        var listBg by remember { mutableStateOf(store.listBgColor) }
        var listAlpha by remember { mutableIntStateOf(store.listBgAlpha) }
        var busy by remember { mutableStateOf(false) }

        var moveAsk by remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<Boolean>?>(null) }
        var moveCount by remember { mutableIntStateOf(0) }
        val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    val app = applicationContext
                    val wasTree = MemoRepo.usingTree(app)
                    // 変更前の保存先にあるメモ（フォルダを切り替えた後も、元のフォルダの権限は残るので読み書きできる）
                    val oldList = if (wasTree) withContext(Dispatchers.IO) { MemoRepo.listTxt(app) } else emptyList()
                    if (persistTreeUri(this@SettingsActivity, uri)) {
                        storage = MemoRepo.storageLabel(this@SettingsActivity)
                        if (wasTree) {
                            if (oldList.isNotEmpty()) {
                                moveCount = oldList.size
                                val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
                                moveAsk = d
                                if (d.await()) {
                                    val items = withContext(Dispatchers.IO) { oldList.mapNotNull { Importer.readItem(app, it.uri) } }
                                    val r = Importer.run(app, items, true) { n, rem -> importer.askConflict(n, rem) }
                                    toast(r.describe("移動"))
                                }
                            }
                        } else {
                            val r = Importer.migrateDefault(app) { n, rem -> importer.askConflict(n, rem) }
                            if (r != null) toast(r.describe("移動"))
                        }
                    } else {
                        toast("フォルダの権限を保持できませんでした")
                    }
                }
            }
        }
        val txtPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) lifecycleScope.launch { importTxt(uris) }
        }
        val zipOutPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) lifecycleScope.launch { exportZip(uri) }
        }
        val zipInPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) lifecycleScope.launch { importZip(uri) }
        }
        val bkTreePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    store.backupTree = uri.toString()
                    bkDest = Backup.destLabel(this)
                } catch (e: Exception) {
                    toast("フォルダの権限を保持できませんでした")
                }
            }
        }

        val scheme = listBg?.let { tintedScheme(Color(rgbToArgb(it))) } ?: MaterialTheme.colorScheme
        MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            Column(
                Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { finish() }) { Text("← 戻る") }
                    Text("設定", style = MaterialTheme.typography.titleLarge)
                }

                Text("保存先", style = MaterialTheme.typography.titleMedium)
                Text(storage, style = MaterialTheme.typography.bodySmall)
                var folderInput by remember { mutableStateOf(store.folderName) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = folderInput,
                        onValueChange = { folderInput = it.take(60) },
                        label = { Text("保存フォルダ名（既定 FusenizeTxt）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        val c = Store.sanitizeFolder(folderInput)
                        if (c == null) {
                            toast("使えない名前です")
                        } else {
                            store.folderName = c
                            folderInput = c
                            MemoRepo.resetCache()
                            storage = MemoRepo.storageLabel(this@SettingsActivity)
                            toast("保存フォルダ名を「$c」にしました")
                        }
                    }) { Text("適用") }
                }
                Text(
                    "選んだフォルダの名前がこれと同じならそのフォルダを、違えばその中にこの名前のフォルダを作って保存先にします。名前を変えても、以前のフォルダのメモは移動しません。",
                    style = MaterialTheme.typography.labelSmall,
                )
                Button(onClick = { treePicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("保存フォルダを設定・変更")
                }
                OutlinedButton(onClick = { txtPicker.launch(arrayOf("text/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("txtファイルを取り込む")
                }
                HorizontalDivider()

                Text("一覧の見た目", style = MaterialTheme.typography.titleMedium)
                ColorPickerRow("一覧の背景色", listBg ?: 0x000000, BG_PRESETS) {
                    listBg = it
                    store.listBgColor = it
                }
                var appPaper by remember { mutableIntStateOf(store.appPaper) }
                SliderRow("質感の強さ（ノイズ。一覧・編集欄。0でなし）", appPaper, 0, 100) {
                    appPaper = it
                    store.appPaper = it
                }
                SliderRow("背景の透過度（255=不透明、初期値200）", listAlpha, 0, 255) {
                    listAlpha = it
                    store.listBgAlpha = it
                }
                TextButton(onClick = {
                    listBg = null
                    listAlpha = 200
                    store.listBgColor = null
                    store.listBgAlpha = 200
                }) { Text("テーマ既定に戻す") }
                HorizontalDivider()
                Text("画面の明るさ", style = MaterialTheme.typography.titleMedium)
                var edBg by remember { mutableStateOf(store.editorBgColor) }
                ColorPickerRow("アプリで開いたときの編集欄の色", edBg ?: (listBg ?: 0xF0F0F0), BG_PRESETS) {
                    edBg = it
                    store.editorBgColor = it
                }
                TextButton(onClick = {
                    edBg = null
                    store.editorBgColor = null
                }) { Text("編集欄の色を既定に戻す") }
                var brOn by remember { mutableStateOf(store.brightnessLock) }
                var brLv by remember { mutableIntStateOf(store.brightnessLevel) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("アプリ画面を指定の明るさに固定", Modifier.weight(1f))
                    Switch(checked = brOn, onCheckedChange = {
                        brOn = it
                        store.brightnessLock = it
                        com.ghghuser.fusenizetxt.data.Brightness.apply(this@SettingsActivity)
                    })
                }
                if (brOn) {
                    SliderRow("明るさ（%）", brLv, 5, 100) {
                        brLv = it
                        store.brightnessLevel = it
                        com.ghghuser.fusenizetxt.data.Brightness.apply(this@SettingsActivity)
                    }
                    Text("このアプリを開いている間だけ有効です（端末全体の設定は変えません）。", style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
                Text("zipバックアップ", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = {
                        zipOutPicker.launch(
                            "FusenizeTxt-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip"
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存先のメモをzipにエクスポート") }
                OutlinedButton(
                    onClick = { zipInPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("zipからインポート（復元）") }
                Text(
                    "インポート時にファイル名は変更しません。同名があれば上書きか連番を選べます。",
                    style = MaterialTheme.typography.labelSmall,
                )
                HorizontalDivider()

                Text("定時バックアップ", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("自動でzipを作る", Modifier.weight(1f))
                    Switch(checked = bkOn, onCheckedChange = {
                        bkOn = it
                        store.backupEnabled = it
                        Backup.schedule(this@SettingsActivity, replace = true)
                    })
                }
                if (bkOn) {
                    Text("間隔", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 3, 6, 12, 24, 168, 720).forEach { h ->
                            FilterChip(selected = bkHours == h, onClick = {
                                bkHours = h
                                store.backupHours = h
                                Backup.schedule(this@SettingsActivity, replace = true)
                            }, label = { Text(when (h) { 168 -> "1週間"; 720 -> "1か月"; else -> "${h}時間" }) })
                        }
                    }
                    SliderRow("残す個数（古いものから自動削除・最大100）", bkKeep, 1, 100) {
                        bkKeep = it
                        store.backupKeep = it
                    }
                    Text("バックアップ先: $bkDest", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { bkTreePicker.launch(null) }) { Text("フォルダを選ぶ") }
                        if (store.backupTree != null) {
                            TextButton(onClick = {
                                store.backupTree = null
                                bkDest = Backup.destLabel(this@SettingsActivity)
                            }) { Text("既定に戻す") }
                        }
                    }
                    Text(
                        "内容が前回から変わっていないときは作りません。端末の省電力設定により、実行時刻は前後することがあります。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        lifecycleScope.launch {
                            val o = withContext(Dispatchers.IO) { Backup.runBackup(applicationContext, force = true) }
                            bkStatus = store.backupStatus
                            toast(o.message)
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "バックアップ中…" else "今すぐバックアップ") }
                if (bkStatus.isNotEmpty()) {
                    Text("前回: $bkStatus", style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()

                Text("バージョン ${versionName()}", style = MaterialTheme.typography.labelSmall)
            }
        }
        }
        moveAsk?.let { d ->
            AlertDialog(
                onDismissRequest = { moveAsk = null; d.complete(false) },
                title = { Text("元の保存先のメモも移動しますか？") },
                text = { Text("これまでの保存先にある ${moveCount} 件のtxtを、新しい保存先へ移動します（元の場所からは削除され、ウィジェットや履歴などの紐付けは引き継がれます）。「移動しない」を選ぶと、元のファイルはそのまま残ります。") },
                confirmButton = { TextButton(onClick = { moveAsk = null; d.complete(true) }) { Text("移動する") } },
                dismissButton = { TextButton(onClick = { moveAsk = null; d.complete(false) }) { Text("移動しない") } },
            )
        }
        ConflictDialog(importer)
    }
}
