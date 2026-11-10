package com.ghghuser.fusenize.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.ghghuser.fusenize.data.Importer
import com.ghghuser.fusenize.data.MemoRepo
import com.ghghuser.fusenize.data.Store
import com.ghghuser.fusenize.widget.FusenWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 保存フォルダの永続権限を取得して保存する（共通） */
fun persistTreeUri(ctx: Context, uri: Uri): Boolean {
    return try {
        ctx.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        Store(ctx).treeUri = uri.toString()
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * ウィジェット追加時（または未設定ウィジェットのタップ時）に開く設定画面。
 * 保存フォルダが未設定でも Documents/FusenizeWidget に自動作成して新規メモを作れる。
 */
class ConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var busy = false
    private val importer = ImportCoordinator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(Activity.RESULT_CANCELED)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent { FusenTheme { ConfigScreen() } }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun finishWith(name: String?, uri: Uri?) {
        if (uri == null) {
            toast("メモを用意できませんでした")
            busy = false
            return
        }
        val manual = !MemoRepo.hasNumberPrefix(name)
        Store(this).updateConfig(widgetId) { it.copy(fileUri = uri.toString(), manualName = manual, cachedText = "") }
        Store(this).addRecent(uri.toString())
        lifecycleScope.launch {
            try {
                FusenWidget().updateAll(this@ConfigActivity)
            } catch (e: Exception) {
                // 更新失敗でも設定は保存済み
            }
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }

    @Composable
    private fun ConfigScreen() {
        var tick by remember { mutableIntStateOf(0) }
        var storage by remember { mutableStateOf(MemoRepo.storageLabel(this)) }
        var files by remember { mutableStateOf<List<MemoRepo.Entry>?>(null) }
        var showFiles by remember { mutableStateOf(false) }

        val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                if (persistTreeUri(this, uri)) {
                    storage = MemoRepo.storageLabel(this)
                    lifecycleScope.launch {
                        val r = Importer.migrateDefault(applicationContext) { n, rem -> importer.askConflict(n, rem) }
                        if (r != null) toast(r.describe("移動"))
                        tick++
                    }
                } else {
                    toast("フォルダの権限を保持できませんでした")
                }
            }
        }

        val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && !busy) {
                busy = true
                lifecycleScope.launch {
                    val ctx = applicationContext
                    val item = withContext(Dispatchers.IO) { Importer.readItem(ctx, uri) }
                    if (item == null) {
                        toast("ファイルを読み込めませんでした")
                        busy = false
                        return@launch
                    }
                    val r = Importer.run(ctx, listOf(item), false) { n, rem -> importer.askConflict(n, rem) }
                    val e = r.entries.firstOrNull()
                    if (e != null) finishWith(e.name, e.uri)
                    else {
                        toast(if (r.cancelled) "取り込みを中止しました" else "取り込めませんでした")
                        busy = false
                    }
                }
            }
        }

        LaunchedEffect(showFiles, tick, storage) {
            if (showFiles) {
                files = withContext(Dispatchers.IO) {
                    MemoRepo.listTxt(this@ConfigActivity).sortedBy { it.name.lowercase() }
                }
            }
        }

        BackHandler { finish() }

        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).systemBarsPadding()
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { finish() },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(0.92f).pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("付箋の設定", style = MaterialTheme.typography.titleLarge)
                    if (!showFiles) {
                        Text("保存先: $storage", style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = {
                                if (busy) return@Button
                                busy = true
                                lifecycleScope.launch {
                                    val s = withContext(Dispatchers.IO) { MemoRepo.createNew(this@ConfigActivity) }
                                    finishWith(s?.name, s?.uri)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("新規メモを作成") }
                        OutlinedButton(onClick = { showFiles = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("保存先の既存txtを選ぶ")
                        }
                        OutlinedButton(
                            onClick = { filePicker.launch(arrayOf("text/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("別の場所のtxtを取り込んで使う") }
                        TextButton(onClick = { treePicker.launch(null) }) { Text("保存フォルダを設定・変更") }
                    } else {
                        Text("既存のtxtを選択", style = MaterialTheme.typography.titleMedium)
                        val list = files
                        if (list == null) {
                            Text("読み込み中…")
                        } else if (list.isEmpty()) {
                            Text("保存先にtxtがありません")
                        } else {
                            LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                                items(list, key = { it.uri.toString() }) { e ->
                                    Column(
                                        Modifier.fillMaxWidth().clickable {
                                            if (busy) return@clickable
                                            busy = true
                                            finishWith(e.name, e.uri)
                                        }.padding(vertical = 10.dp)
                                    ) {
                                        Text(e.name)
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { showFiles = false }) { Text("戻る") }
                    }
                }
            }
        }
        ConflictDialog(importer)
    }
}
