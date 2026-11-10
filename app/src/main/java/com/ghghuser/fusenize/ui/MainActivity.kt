@file:OptIn(ExperimentalMaterial3Api::class)

package com.ghghuser.fusenize.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.ghghuser.fusenize.data.MemoRepo
import com.ghghuser.fusenize.data.Store
import com.ghghuser.fusenize.data.rgbToArgb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class MemoRow(
    val uri: Uri,
    val name: String,
    val modified: Long,
    val created: Long,
    val fav: Boolean,
)

/** アプリアイコンから起動した時の一覧画面（開いたファイル / フォルダ内） */
class MainActivity : ComponentActivity() {
    private val dateFmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FusenTheme { MainScreen() } }
    }

    private fun openEditor(uri: Uri) {
        startActivity(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_URI, uri.toString()))
    }

    private fun loadRows(store: Store, tab: Int): List<MemoRow> {
        val fav = store.favorites()
        fun make(uri: Uri, name: String, mod: Long): MemoRow {
            val key = uri.toString()
            return MemoRow(uri, name, mod, store.createdAt(key) ?: mod, key in fav)
        }
        return if (tab == 1) {
            MemoRepo.listTxt(this).map { make(it.uri, it.name, it.lastModified) }
        } else {
            store.recents().mapNotNull { u ->
                val uri = Uri.parse(u)
                val name = MemoRepo.displayName(this, uri) ?: return@mapNotNull null
                make(uri, name, MemoRepo.lastModified(this, uri))
            }
        }
    }

    @Composable
    private fun MainScreen() {
        val store = remember { Store(this) }
        var tab by remember { mutableIntStateOf(0) }
        var tick by remember { mutableIntStateOf(0) }
        var sort by remember { mutableIntStateOf(store.sortMode) }
        var rows by remember { mutableStateOf<List<MemoRow>>(emptyList()) }
        var showSettings by remember { mutableStateOf(false) }
        var listBg by remember { mutableStateOf(store.listBgColor) }
        var listAlpha by remember { mutableIntStateOf(store.listBgAlpha) }
        var hasFolder by remember { mutableStateOf(MemoRepo.hasFolderPermission(this)) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                if (persistTreeUri(this, uri)) {
                    hasFolder = true
                    tick++
                } else {
                    Toast.makeText(this, "フォルダの権限を保持できませんでした", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) {
                    hasFolder = MemoRepo.hasFolderPermission(this@MainActivity)
                    tick++
                }
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }

        LaunchedEffect(tab, tick, hasFolder) {
            rows = withContext(Dispatchers.IO) { loadRows(store, tab) }
        }

        val sorted = remember(rows, sort) {
            val base = when (sort) {
                Store.SORT_CREATED -> rows.sortedByDescending { it.created }
                Store.SORT_NAME -> rows.sortedBy { it.name.lowercase() }
                else -> rows.sortedByDescending { it.modified }
            }
            base.sortedByDescending { it.fav } // sortedBy は安定ソート → お気に入りが最上段
        }

        val cs = MaterialTheme.colorScheme
        val bg = listBg?.let { Color(rgbToArgb(it, listAlpha)) } ?: cs.background.copy(alpha = listAlpha / 255f)
        val onBg = if (listBg == null) cs.onBackground else cs.onSurface

        Scaffold(
            containerColor = Color.Transparent,
            modifier = Modifier.fillMaxSize().background(bg),
            floatingActionButton = {
                if (hasFolder) {
                    FloatingActionButton(onClick = {
                        lifecycleScope.launch {
                            val s = withContext(Dispatchers.IO) { MemoRepo.createNew(this@MainActivity) }
                            if (s != null) openEditor(s.uri)
                            else Toast.makeText(this@MainActivity, "作成できませんでした", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("＋") }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Fusenize", modifier = Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.titleLarge, color = onBg)
                    IconButton(onClick = { showSettings = true }) { Text("⚙", color = onBg) }
                }
                TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("開いたファイル") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("フォルダ内") })
                }
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("更新日時" to Store.SORT_UPDATED, "作成日時" to Store.SORT_CREATED, "名前" to Store.SORT_NAME).forEach { (l, m) ->
                        FilterChip(selected = sort == m, onClick = { sort = m; store.sortMode = m }, label = { Text(l) })
                    }
                }
                if (tab == 1 && !hasFolder) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("保存フォルダが未設定です。", color = onBg)
                        Button(onClick = { picker.launch(null) }) { Text("保存フォルダを選ぶ") }
                    }
                } else if (sorted.isEmpty()) {
                    Text(
                        if (tab == 0) "まだ開いたメモがありません。\n「フォルダ内」タブか＋から開けます。" else "txtがありません。＋で作成できます。",
                        modifier = Modifier.padding(16.dp), color = onBg,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(sorted, key = { it.uri.toString() }) { r ->
                            Row(
                                Modifier.fillMaxWidth().clickable { openEditor(r.uri) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(MemoRepo.displayTitle(r.name), maxLines = 1, overflow = TextOverflow.Ellipsis, color = onBg, style = MaterialTheme.typography.bodyLarge)
                                    if (r.modified > 0) {
                                        Text(
                                            dateFmt.format(Date(r.modified)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = onBg.copy(alpha = 0.5f),
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    store.setFavorite(r.uri.toString(), !r.fav)
                                    rows = rows.map { if (it.uri == r.uri) it.copy(fav = !r.fav) else it }
                                }) { Text(if (r.fav) "★" else "☆", color = if (r.fav) cs.primary else onBg.copy(alpha = 0.6f)) }
                            }
                            HorizontalDivider(color = onBg.copy(alpha = 0.12f))
                        }
                    }
                }
            }
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("一覧の設定") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picker.launch(null) }) { Text("保存フォルダを変更") }
                        ColorPickerRow("一覧の背景色", listBg ?: 0x000000, BG_PRESETS) {
                            listBg = it
                            store.listBgColor = it
                        }
                        SliderRow("背景の透過度（255=不透明）", listAlpha, 0, 255) {
                            listAlpha = it
                            store.listBgAlpha = it
                        }
                        TextButton(onClick = {
                            listBg = null
                            listAlpha = 255
                            store.listBgColor = null
                            store.listBgAlpha = 255
                        }) { Text("テーマ既定に戻す") }
                    }
                },
                confirmButton = { TextButton(onClick = { showSettings = false }) { Text("閉じる") } },
            )
        }
    }
}
