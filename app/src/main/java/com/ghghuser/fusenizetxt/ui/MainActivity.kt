@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.ghghuser.fusenizetxt.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.ghghuser.fusenizetxt.FusenApp
import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.sync.withLock
import com.ghghuser.fusenizetxt.data.Importer
import com.ghghuser.fusenizetxt.data.MemoRepo
import com.ghghuser.fusenizetxt.data.Store
import com.ghghuser.fusenizetxt.data.rgbToArgb
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
    val manual: Boolean,
)

/** アプリアイコンから起動した時の一覧画面（開いたファイル / フォルダ内） */
class MainActivity : ComponentActivity() {
    private val dateFmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FusenTheme { MainScreen() } }
    }

    override fun onResume() {
        super.onResume()
        com.ghghuser.fusenizetxt.data.Brightness.apply(this)
    }

    private fun openEditor(uri: Uri, isNew: Boolean = false) {
        startActivity(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_URI, uri.toString()).putExtra(EditActivity.EXTRA_NEW, isNew))
    }

    private fun loadRows(store: Store, tab: Int): List<MemoRow> {
        val fav = store.favorites()
        val man = store.manualSet()
        fun make(uri: Uri, name: String, mod: Long): MemoRow {
            val key = uri.toString()
            return MemoRow(uri, name, mod, store.createdAt(key) ?: mod, key in fav, key in man || !MemoRepo.hasNumberPrefix(name))
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
        var sortRev by remember { mutableStateOf(store.sortReverse) }
        var creating by remember { mutableStateOf(false) }
        var rows by remember { mutableStateOf<List<MemoRow>>(emptyList()) }
        var listBg by remember { mutableStateOf(store.listBgColor) }
        var listAlpha by remember { mutableIntStateOf(store.listBgAlpha) }
        var storage by remember { mutableStateOf(MemoRepo.storageLabel(this)) }
        var paper by remember { mutableIntStateOf(store.appPaper) }
        var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
        var confirmDelete by remember { mutableStateOf(false) }
        androidx.activity.compose.BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) {
                    storage = MemoRepo.storageLabel(this@MainActivity)
                    listBg = store.listBgColor
                    listAlpha = store.listBgAlpha
                    paper = store.appPaper
                    tick++
                }
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }

        LaunchedEffect(tab, tick, storage) {
            rows = withContext(Dispatchers.IO) { loadRows(store, tab) }
        }

        val sorted = remember(rows, sort, sortRev) {
            val asc = (sort == Store.SORT_NAME) != sortRev
            val cmp: Comparator<MemoRow> = when (sort) {
                Store.SORT_CREATED -> compareBy { it.created }
                Store.SORT_NAME -> Comparator { x, y -> MemoRepo.naturalCompare(x.name.lowercase(), y.name.lowercase()) }
                else -> compareBy { it.modified }
            }
            val base = rows.sortedWith(if (asc) cmp else cmp.reversed())
            base.sortedByDescending { it.fav } // sortedBy は安定ソート → お気に入りが最上段
        }

        val cs = listBg?.let { tintedScheme(Color(rgbToArgb(it))) } ?: MaterialTheme.colorScheme
        val bg = listBg?.let { Color(rgbToArgb(it, listAlpha)) } ?: cs.background.copy(alpha = listAlpha / 255f)
        val onBg = if (listBg == null) cs.onBackground else cs.onSurface

        MaterialTheme(colorScheme = cs) {
        Scaffold(
            containerColor = Color.Transparent,
            modifier = Modifier.fillMaxSize().background(bg).paperTexture(paper * listAlpha / 255),
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    if (creating) return@FloatingActionButton // 連打でファイルが連続作成されるのを防ぐ
                    creating = true
                    lifecycleScope.launch {
                        val s = withContext(Dispatchers.IO) { MemoRepo.createNew(this@MainActivity) }
                        kotlinx.coroutines.delay(600)
                        creating = false
                        if (s != null) openEditor(s.uri, isNew = true)
                        else toast("作成できませんでした")
                    }
                }) { Text("＋", fontSize = 24.sp) }
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (selected.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selected = emptySet() }) { Text("✕ 解除", color = onBg) }
                        Text("${selected.size}件選択", modifier = Modifier.weight(1f), color = onBg)
                        TextButton(onClick = { selected = sorted.map { it.uri.toString() }.toSet() }) { Text("全選択", color = onBg) }
                        TextButton(onClick = { confirmDelete = true }) { Text("🗑 削除", color = Color(0xFFE57373)) }
                    }
                } else
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("FusenizeTxt", modifier = Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.titleLarge, color = onBg)
                    var sortMenu by remember { mutableStateOf(false) }
                    val sortLabels = listOf(Store.SORT_UPDATED to "更新日時", Store.SORT_CREATED to "作成日時", Store.SORT_NAME to "名前")
                    Box {
                        OutlinedButton(onClick = { sortMenu = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(34.dp)) {
                            Text((sortLabels.firstOrNull { it.first == sort }?.second ?: "更新日時") + (if ((sort == Store.SORT_NAME) != sortRev) " ▲" else " ▼"), color = onBg, style = MaterialTheme.typography.labelMedium)
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            sortLabels.forEach { (m, l) ->
                                DropdownMenuItem(
                                    text = { Text((if (sort == m) (if ((m == Store.SORT_NAME) != sortRev) "▲ " else "▼ ") else "　") + l) },
                                    onClick = {
                                        // 選択中の項目をもう一度押すと昇順/降順を入れ替える
                                        if (sort == m) sortRev = !sortRev else { sort = m; sortRev = false }
                                        store.sortMode = sort
                                        store.sortReverse = sortRev
                                        sortMenu = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }, modifier = Modifier.size(52.dp)) { Text("⚙", fontSize = 28.sp, color = onBg) }
                }
                TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = onBg) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("開いたファイル") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("フォルダ内") })
                }
                if (tab == 1) {
                    Text(
                        "保存先: $storage",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = onBg.copy(alpha = 0.6f),
                    )
                }
                if (tab == 1 && !MemoRepo.usingTree(this@MainActivity)) {
                    val note = Store(this@MainActivity).storageNote
                    if (note.isNotEmpty()) {
                        Text(
                            "保存先の作成について: $note",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = onBg.copy(alpha = 0.6f),
                        )
                    }
                }
                if (sorted.isEmpty()) {
                    Text(
                        if (tab == 0) "まだ開いたメモがありません。\n「フォルダ内」タブか＋から開けます。" else "txtがありません。＋で作成できます。",
                        modifier = Modifier.padding(16.dp), color = onBg,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(sorted, key = { it.uri.toString() }) { r ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (r.uri.toString() in selected) onBg.copy(alpha = 0.18f) else Color.Transparent)
                                    .combinedClickable(
                                        onClick = {
                                            val k = r.uri.toString()
                                            if (selected.isNotEmpty()) selected = if (k in selected) selected - k else selected + k
                                            else openEditor(r.uri)
                                        },
                                        onLongClick = {
                                            val k = r.uri.toString()
                                            selected = if (k in selected) selected - k else selected + k
                                        },
                                    )
                                    .padding(start = 16.dp, top = 3.dp, bottom = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        (if (r.uri.toString() in selected) "☑ " else if (selected.isNotEmpty()) "☐ " else "") +
                                            (if (r.manual) "🐾 " else "🌱 ") +
                                            (if (r.manual && MemoRepo.hasNumberPrefix(r.name)) r.name.removeSuffix(".txt").removeSuffix(".TXT") else MemoRepo.displayTitle(r.name)),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = onBg, style = MaterialTheme.typography.bodyLarge)
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
            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("${selected.size}件のメモを削除しますか？") },
                    text = { Text("選んだメモのtxtファイルを削除します。元に戻せません。ウィジェットに設定されているメモは未設定に戻ります。") },
                    confirmButton = {
                        TextButton(onClick = {
                            val targets = selected.toList()
                            confirmDelete = false
                            selected = emptySet()
                            lifecycleScope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    var n = 0
                                    FusenApp.saveMutex.withLock {
                                        for (k in targets) {
                                            val u = Uri.parse(k)
                                            if (MemoRepo.delete(applicationContext, u)) {
                                                com.ghghuser.fusenizetxt.data.Drafts.delete(applicationContext, u)
                                                store.removeUri(k)
                                                n++
                                            }
                                        }
                                    }
                                    n
                                }
                                toast(if (ok == targets.size) "${ok}件削除しました" else "${ok}件削除しました（${targets.size - ok}件は削除できませんでした）")
                                FusenApp.refreshWidgets(applicationContext)
                                tick++
                            }
                        }) { Text("削除", color = Color(0xFFE57373)) }
                    },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
                )
            }
        }
    }
}
