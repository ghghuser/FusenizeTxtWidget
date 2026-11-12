package com.ghghuser.fusenizetxt.ui

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ghghuser.fusenizetxt.FusenApp
import com.ghghuser.fusenizetxt.data.Drafts
import com.ghghuser.fusenizetxt.data.FileMeta
import com.ghghuser.fusenizetxt.data.History
import com.ghghuser.fusenizetxt.data.MemoRepo
import com.ghghuser.fusenizetxt.data.Store
import com.ghghuser.fusenizetxt.data.WidgetStyle
import com.ghghuser.fusenizetxt.data.rgbToArgb
import com.ghghuser.fusenizetxt.data.contrastTextRgb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 元に戻す/やり直し。短時間の連続入力はひとまとめにする */
class UndoManager {
    private val undoStack = ArrayList<String>()
    private val redoStack = ArrayList<String>()
    private var lastTime = 0L
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    fun onChange(old: String, new: String) {
        if (old == new) return
        val now = System.currentTimeMillis()
        if (undoStack.isEmpty() || now - lastTime > 800 || abs(new.length - old.length) > 20) {
            undoStack.add(old)
            if (undoStack.size > 300) undoStack.removeAt(0)
        }
        lastTime = now
        redoStack.clear()
        refresh()
    }

    fun undo(current: String): String? {
        if (undoStack.isEmpty()) return null
        redoStack.add(current)
        val r = undoStack.removeAt(undoStack.size - 1)
        lastTime = 0L
        refresh()
        return r
    }

    fun redo(current: String): String? {
        if (redoStack.isEmpty()) return null
        undoStack.add(current)
        val r = redoStack.removeAt(redoStack.size - 1)
        lastTime = 0L
        refresh()
        return r
    }

    private fun refresh() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }
}

private enum class Dlg { NONE, DELETE, CLEAR, RENAME, HISTORY, REMIND }

private data class HistItem(val v: History.Version, val preview: String)

/**
 * 透明ダイアログ風の編集画面。
 * ウィジェットから開いた場合は widgetId、アプリの一覧から開いた場合は EXTRA_URI を使う。
 */
class EditActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URI = "fusen_uri"
        const val EXTRA_PAGE = "fusen_page"
        private const val FORCE_RESAVE = "\u0000"
    }

    private lateinit var store: Store
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val uriRef = AtomicReference<Uri?>(null)

    private var manualName by mutableStateOf(false)
    private var loaded by mutableStateOf(false)
    private var deleted = false
    private var lastSaved = ""
    private var text by mutableStateOf(TextFieldValue(""))
    private var style by mutableStateOf(WidgetStyle())
    private var fileName by mutableStateOf("")
    private var keepOn by mutableStateOf(false)
    private var charsetName by mutableStateOf("UTF-8")
    private var pendingEnc by mutableStateOf<ByteArray?>(null)
    private var page = 0
    private var pageCount by mutableStateOf(1)
    private var pageBg by mutableStateOf<Int?>(null)
    private var histItems by mutableStateOf<List<HistItem>?>(null)
    private var histPick by mutableStateOf<HistItem?>(null)
    private val histFmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
    private val undoMgr = UndoManager()

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            saveNow()
        }
    }
    private var receiverRegistered = false

    private val widgetMode get() = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = Store(this)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val extraUri = intent.getStringExtra(EXTRA_URI)

        if (widgetMode) {
            val cfg = store.getConfig(widgetId)
            page = intent.getIntExtra(EXTRA_PAGE, cfg.cur).coerceIn(0, cfg.pages.size - 1)
            style = cfg.style
            pageCount = cfg.pages.size
            pageBg = cfg.pages[page].bgColor
            manualName = cfg.pages[page].manualName || (cfg.pages[page].fileUri?.let { store.isManual(it) } ?: false)
            val u = cfg.pages[page].fileUri
            if (u == null) {
                startActivity(
                    Intent(this, ConfigActivity::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        .putExtra(EXTRA_PAGE, page)
                )
                finish()
                return
            }
            uriRef.set(Uri.parse(u))
        } else if (extraUri != null) {
            uriRef.set(Uri.parse(extraUri))
        } else {
            finish()
            return
        }

        loadContent()
        setContent { FusenTheme { EditScreen() } }
    }

    private fun loadContent() {
        val u = uriRef.get() ?: return
        lifecycleScope.launch {
            val ctx = applicationContext
            val loadedData = withContext(Dispatchers.IO) {
                val r = MemoRepo.readText(ctx, u)
                val name = MemoRepo.displayName(ctx, u) ?: ""
                val mod = MemoRepo.lastModified(ctx, u)
                val draft = Drafts.read(ctx, u)
                Triple(r, name, Pair(mod, draft))
            }
            val (result, name, md) = loadedData
            val content = result.getOrNull()?.text
            if (content == null) {
                val ex = result.exceptionOrNull()
                if (ex is MemoRepo.EncodingException) {
                    // UTF-8以外: どう開くか尋ねる（確認が済むまで何も書き込まない）
                    fileName = name
                    pendingEnc = ex.bytes
                    return@launch
                }
                val msg = result.exceptionOrNull()?.message ?: "開けませんでした"
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                if (widgetMode && !MemoRepo.exists(ctx, u)) {
                    startActivity(
                        Intent(this@EditActivity, ConfigActivity::class.java)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                            .putExtra(EXTRA_PAGE, page)
                    )
                }
                finish()
                return@launch
            }
            fileName = name
            if (!widgetMode) manualName = store.isManual(u.toString()) || !MemoRepo.hasNumberPrefix(name)
            else if (store.isManual(u.toString())) manualName = true
            result.getOrNull()?.let { ft ->
                withContext(Dispatchers.IO) {
                    val m = store.meta(u.toString())
                    if (m.crlf != ft.crlf) store.setMeta(u.toString(), m.copy(crlf = ft.crlf))
                    History.add(applicationContext, u, ft.text) // 開いた時点の内容も履歴に残す
                }
            }
            charsetName = store.meta(u.toString()).charset
            lastSaved = content
            val (mod, draft) = md
            var shown = content
            if (draft != null && draft.text != content && draft.time > mod) {
                shown = draft.text
                Toast.makeText(ctx, "保存されていなかった内容を復元しました", Toast.LENGTH_LONG).show()
            }
            text = TextFieldValue(shown, TextRange(shown.length))
            store.addRecent(u.toString())
            loaded = true
        }
    }

    private val notifPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, "通知が許可されていないため、リマインダーは表示されません（端末の設定で許可できます）", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        com.ghghuser.fusenizetxt.data.Brightness.apply(this)
    }

    private fun setReminder(at: Long, repeat: Int) {
        val u = uriRef.get() ?: return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        com.ghghuser.fusenizetxt.data.Reminders.set(applicationContext, u.toString(), com.ghghuser.fusenizetxt.data.Reminder(at, repeat))
        Toast.makeText(this, "リマインダーを設定しました", Toast.LENGTH_SHORT).show()
    }

    private fun clearReminder() {
        val u = uriRef.get() ?: return
        com.ghghuser.fusenizetxt.data.Reminders.clear(applicationContext, u.toString())
        Toast.makeText(this, "リマインダーを解除しました", Toast.LENGTH_SHORT).show()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        saveNow()
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenOffReceiver)
            } catch (e: Exception) {
            }
            receiverRegistered = false
        }
        super.onStop()
    }

    // ---------- 保存 ----------

    private fun saveNow() {
        if (!loaded || deleted) return
        val t = text.text
        if (t == lastSaved) return
        lastSaved = t
        val ctx = applicationContext
        val wid = widgetId
        val pg = page
        val man = manualName
        FusenApp.scope.launch {
            FusenApp.saveMutex.withLock {
                val u = uriRef.get() ?: return@withLock
                val outcome = MemoRepo.save(ctx, u, t, man)
                val res = outcome.saved
                if (res == null) {
                    // 失敗: 次回また保存を試みる。下書きは残す。
                    lastSaved = FORCE_RESAVE
                    Drafts.write(ctx, u, t)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            ctx,
                            if (outcome.encodeError) "この文字コードで表せない文字があるため保存できません（メニューの「UTF-8に変換して保存」を使ってください。内容は端末内に退避済み）"
                            else "保存に失敗しました（内容は端末内に退避済み）",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                } else {
                    uriRef.set(res.uri)
                    Drafts.delete(ctx, u)
                    Drafts.delete(ctx, res.uri)
                    History.add(ctx, res.uri, t)
                    if (wid != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        Store(ctx).updateConfig(wid) { it.updatePage(pg) { p -> p.copy(cachedText = t.take(4000)) } }
                    }
                    withContext(Dispatchers.Main) { fileName = res.name }
                    try {
                        FusenApp.refreshWidgets(ctx)
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    private fun closeAndSave() {
        saveNow()
        finish()
    }

    private fun renameManually(base: String) {
        saveNow()
        val ctx = applicationContext
        val wid = widgetId
        val pg = page
        FusenApp.scope.launch {
            FusenApp.saveMutex.withLock {
                val u = uriRef.get() ?: return@withLock
                val r = MemoRepo.renameTo(ctx, u, base)
                if (r == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "ファイル名を変更できませんでした", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Drafts.delete(ctx, u)
                    uriRef.set(r.uri)
                    Store(ctx).setManual(r.uri.toString(), true)
                    if (wid != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        Store(ctx).updateConfig(wid) { it.updatePage(pg) { p -> p.copy(manualName = true) } }
                    }
                    withContext(Dispatchers.Main) {
                        fileName = r.name
                        manualName = true
                    }
                    try {
                        FusenApp.refreshWidgets(ctx)
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    private fun backToAutoName() {
        manualName = false
        uriRef.get()?.let { store.setManual(it.toString(), false) }
        if (widgetMode) store.updateConfig(widgetId) { it.updatePage(page) { p -> p.copy(manualName = false) } }
        lastSaved = FORCE_RESAVE
        saveNow()
    }

    private fun deleteMemo() {
        if (deleted) return
        deleted = true
        val ctx = applicationContext
        FusenApp.scope.launch {
            FusenApp.saveMutex.withLock {
                val u = uriRef.get()
                if (u != null) {
                    val ok = MemoRepo.delete(ctx, u)
                    if (ok) {
                        Drafts.delete(ctx, u)
                        Store(ctx).removeUri(u.toString())
                    } else {
                        deleted = false
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "削除できませんでした", Toast.LENGTH_SHORT).show()
                        }
                        return@withLock
                    }
                }
                try {
                    FusenApp.refreshWidgets(ctx)
                } catch (e: Exception) {
                }
                withContext(Dispatchers.Main) { finish() }
            }
        }
    }

    // ---------- 編集操作 ----------

    private fun applyText(nv: TextFieldValue) {
        if (nv.text != text.text) undoMgr.onChange(text.text, nv.text)
        text = nv
    }

    private fun doUndo() {
        undoMgr.undo(text.text)?.let { text = TextFieldValue(it, TextRange(it.length)) }
    }

    private fun doRedo() {
        undoMgr.redo(text.text)?.let { text = TextFieldValue(it, TextRange(it.length)) }
    }

    /** カーソル行の ☐/☑ を切り替える（なければ先頭に ☐ を付ける） */
    private fun toggleCheckbox() {
        val s = text.text
        val cur = text.selection.min.coerceIn(0, s.length)
        val ls = (if (cur == 0) -1 else s.lastIndexOf('\n', cur - 1)) + 1
        val leRaw = s.indexOf('\n', ls)
        val le = if (leRaw < 0) s.length else leRaw
        val line = s.substring(ls, le)
        val newLine: String
        var delta = 0
        when {
            line.startsWith("☐") -> newLine = "☑" + line.drop(1)
            line.startsWith("☑") -> {
                // 3段目: チェックボックスを削除する
                val rest = line.drop(1)
                newLine = if (rest.startsWith(" ")) rest.drop(1) else rest
                delta = newLine.length - line.length
            }
            else -> {
                newLine = "☐ $line"
                delta = 2
            }
        }
        val nt = s.substring(0, ls) + newLine + s.substring(le)
        applyText(TextFieldValue(nt, TextRange((cur + delta).coerceIn(ls, nt.length))))
    }

    /** タップ後のカーソルが行頭の ☐/☑ の位置なら、チェックとオフを切り替える */
    private fun tapToggleCheckbox() {
        val v = text
        if (!v.selection.collapsed) return
        val s = v.text
        val cur = v.selection.start.coerceIn(0, s.length)
        val ls = (if (cur == 0) -1 else s.lastIndexOf('\n', cur - 1)) + 1
        if (ls >= s.length || cur > ls + 1) return
        val mark = s[ls]
        if (mark != '☐' && mark != '☑') return
        val nt = s.substring(0, ls) + (if (mark == '☐') '☑' else '☐') + s.substring(ls + 1)
        applyText(TextFieldValue(nt, v.selection))
    }

    private fun applyKeepOn(on: Boolean) {
        keepOn = on
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** このページで実際に使う見た目（ページ別の背景色を反映） */
    private fun shownStyle(): WidgetStyle = style.copy(bgColor = pageBg ?: style.bgColor)

    // ---------- 履歴 ----------

    private fun openHistory() {
        val u = uriRef.get() ?: return
        histItems = null
        histPick = null
        lifecycleScope.launch {
            val ctx = applicationContext
            histItems = withContext(Dispatchers.IO) {
                History.list(ctx, u).map { v ->
                    HistItem(v, (History.read(v) ?: "").lineSequence().firstOrNull()?.trim()?.take(40) ?: "")
                }
            }
        }
    }

    private fun restoreVersion(item: HistItem) {
        val u = uriRef.get() ?: return
        val cur = text.text
        val ctx = applicationContext
        lifecycleScope.launch {
            val t = withContext(Dispatchers.IO) {
                History.add(ctx, u, cur, force = true) // 戻す前の内容も履歴に残す
                History.read(item.v)
            }
            if (t == null) Toast.makeText(ctx, "この版を読み込めませんでした", Toast.LENGTH_SHORT).show()
            else applyText(TextFieldValue(t, TextRange(t.length)))
        }
    }

    // ---------- 文字コード ----------

    /** 元の文字コードのまま開く（ファイルは変更しない。保存時も同じ文字コードで書く） */
    private fun encOpenAs(cs: String) {
        val b = pendingEnc ?: return
        val u = uriRef.get() ?: return
        store.setMeta(u.toString(), FileMeta(cs, MemoRepo.hasCrlf(b, cs)))
        pendingEnc = null
        loadContent()
    }

    /** 元のバイト列を端末内にバックアップしてから、UTF-8で書き直して開く */
    private fun encConvert(cs: String) {
        val b = pendingEnc ?: return
        val u = uriRef.get() ?: return
        val ctx = applicationContext
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val t = MemoRepo.tryDecode(b, cs) ?: return@withContext false
                FusenApp.saveMutex.withLock { MemoRepo.convertToUtf8(ctx, u, t, MemoRepo.hasCrlf(b, cs)) }
            }
            if (ok) {
                pendingEnc = null
                loadContent()
            } else {
                Toast.makeText(ctx, "UTF-8への変換に失敗しました", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 開いている非UTF-8のメモを、現在の内容でUTF-8に変換して保存する */
    private fun convertCurrent() {
        val u = uriRef.get() ?: return
        val t = text.text
        val ctx = applicationContext
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                FusenApp.saveMutex.withLock {
                    MemoRepo.convertToUtf8(ctx, u, t, store.meta(u.toString()).crlf)
                }
            }
            if (ok) {
                charsetName = "UTF-8"
                lastSaved = t
                Drafts.delete(ctx, u)
                Toast.makeText(ctx, "UTF-8に変換しました（元のファイルは端末内にバックアップ済み）", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(ctx, "UTF-8への変換に失敗しました", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- UI ----------

    @Composable
    private fun EditScreen() {
        var showSettings by remember { mutableStateOf(false) }
        var lineNumbers by remember { mutableStateOf(store.lineNumbers) }
        var tl by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
        val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val fm = androidx.compose.ui.platform.LocalFocusManager.current
        var menuOpen by remember { mutableStateOf(false) }
        var dlg by remember { mutableStateOf(Dlg.NONE) }
        var renameText by remember { mutableStateOf("") }
        var remAt by remember { mutableStateOf(0L) }
        var remRepeat by remember { mutableStateOf(0) }
        var remExists by remember { mutableStateOf(false) }
        val focus = remember { FocusRequester() }
        val cs = MaterialTheme.colorScheme

        val appEd = remember { store.editorBgColor ?: store.listBgColor }
        val editorBg = if (widgetMode) Color(rgbToArgb(shownStyle().bgColor)) else appEd?.let { Color(rgbToArgb(it)) } ?: cs.surfaceVariant
        // 設定・ツールバー部分の色は入力欄に近い色で、少し濃いめにする
        val panelBg = androidx.compose.ui.graphics.lerp(editorBg, Color.Black, 0.18f)
        val panelDark = panelBg.luminance() < 0.5f
        val panelFg = if (panelDark) Color(0xFFF2F2F2) else Color(0xFF212121)
        val panelScheme = tintedScheme(panelBg)
        val editorFg = if (widgetMode) Color(rgbToArgb(shownStyle().effectiveTextRgb())) else if (appEd != null) Color(rgbToArgb(contrastTextRgb(appEd))) else cs.onSurfaceVariant

        LaunchedEffect(loaded) {
            if (loaded) {
                delay(150)
                try {
                    focus.requestFocus()
                } catch (e: Exception) {
                }
            }
        }
        // 下書きの自動退避（保存前にプロセスが落ちても復元できるように）
        LaunchedEffect(text.text, loaded) {
            if (loaded && text.text != lastSaved) {
                delay(800)
                val u = uriRef.get()
                if (u != null && !deleted) {
                    withContext(Dispatchers.IO) { Drafts.write(applicationContext, u, text.text) }
                }
            }
        }
        // 見た目設定は即ウィジェットへ反映
        LaunchedEffect(style, pageBg, pageCount, loaded) {
            if (loaded && widgetMode && !deleted) {
                val pg = page
                val bgc = pageBg
                store.updateConfig(widgetId) {
                    it.copy(style = style).withPageCount(pageCount).updatePage(pg) { p -> p.copy(bgColor = bgc) }
                }
                delay(200)
                try {
                    FusenApp.refreshWidgets(applicationContext)
                } catch (e: Exception) {
                }
            }
        }

        // 設定を開いている間はキーボードを出さない（閉じたら入力欄へ戻る）
        LaunchedEffect(showSettings) {
            if (showSettings) {
                fm.clearFocus()
                kb?.hide()
            } else if (loaded) {
                delay(100)
                try {
                    focus.requestFocus()
                } catch (e: Exception) {
                }
            }
        }

        // 設定が開いていれば、戻る操作でまず設定を閉じる
        BackHandler {
            if (showSettings) showSettings = false else closeAndSave()
        }

        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .systemBarsPadding()
                .imePadding()
                // clickable だと Enter キーのイベントが「タップ」扱いで閉じてしまうため、ポインタ入力のみで判定する
                .pointerInput(Unit) { detectTapGestures { closeAndSave() } },
            contentAlignment = Alignment.Center,
        ) {
            MaterialTheme(colorScheme = panelScheme) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = panelBg,
                contentColor = panelFg,
                modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f)
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(Modifier.fillMaxSize().padding(8.dp)) {
                    // 1段目: ファイル名 / 画面オン / メニュー / 閉じる
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (if (manualName) "🐾 " else "🌱 ") + fileName.ifEmpty { "…" } +
                                (if (charsetName != "UTF-8") "  [" + (MemoRepo.ENCODINGS.firstOrNull { it.first == charsetName }?.second ?: charsetName) + "]" else ""),
                            modifier = Modifier.weight(1f).clickable {
                                renameText = fileName.removeSuffix(".txt")
                                dlg = Dlg.RENAME
                            }.padding(horizontal = 8.dp, vertical = 6.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (keepOn) {
                            // 点灯したままの間は🔆を表示。タップで解除
                            IconButton(onClick = {
                                applyKeepOn(false)
                                Toast.makeText(this@EditActivity, "画面の自動消灯を元に戻しました", Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.size(40.dp)) { Text("🔆") }
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) { Text("⋮") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text((if (keepOn) "🌙 画面を自動消灯に戻す" else "🔆 画面を点灯したままにする")) }, onClick = {
                                    menuOpen = false
                                    applyKeepOn(!keepOn)
                                    Toast.makeText(this@EditActivity, if (keepOn) "編集中は画面を消さないようにしました" else "画面の自動消灯を元に戻しました", Toast.LENGTH_SHORT).show()
                                })
                                DropdownMenuItem(text = { Text("📋 一覧を開く") }, onClick = {
                                    menuOpen = false
                                    closeAndSave()
                                    startActivity(Intent(this@EditActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                })
                                if (widgetMode) {
                                    DropdownMenuItem(text = { Text(if (showSettings) "⚙ 設定を閉じる" else "⚙ ウィジェットの設定") }, onClick = {
                                        menuOpen = false
                                        showSettings = !showSettings
                                    })
                                }
                                DropdownMenuItem(text = { Text("ファイル名を変更") }, onClick = {
                                    menuOpen = false
                                    renameText = fileName.removeSuffix(".txt")
                                    dlg = Dlg.RENAME
                                })
                                if (manualName) {
                                    DropdownMenuItem(text = { Text("ファイル名を自動に戻す") }, onClick = {
                                        menuOpen = false
                                        backToAutoName()
                                    })
                                }
                                DropdownMenuItem(text = { Text("リマインダー") }, onClick = {
                                    menuOpen = false
                                    val cur = uriRef.get()?.let { com.ghghuser.fusenizetxt.data.Reminders.get(applicationContext, it.toString()) }
                                    remAt = cur?.time?.takeIf { it > System.currentTimeMillis() } ?: (System.currentTimeMillis() / 3600000 + 1) * 3600000
                                    remRepeat = cur?.repeat ?: 0
                                    remExists = cur != null
                                    dlg = Dlg.REMIND
                                })
                                DropdownMenuItem(text = { Text("履歴") }, onClick = {
                                    menuOpen = false
                                    openHistory()
                                    dlg = Dlg.HISTORY
                                })
                                if (charsetName != "UTF-8") {
                                    DropdownMenuItem(text = { Text("UTF-8に変換して保存") }, onClick = {
                                        menuOpen = false
                                        convertCurrent()
                                    })
                                }
                                DropdownMenuItem(text = { Text("メモをクリア") }, onClick = {
                                    menuOpen = false
                                    dlg = Dlg.CLEAR
                                })
                                DropdownMenuItem(text = { Text("このメモを削除") }, onClick = {
                                    menuOpen = false
                                    dlg = Dlg.DELETE
                                })
                            }
                        }
                    }
                    // 2段目: ツールバー（右端に閉じる）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { doUndo() }, enabled = undoMgr.canUndo) { Text("↺ 戻す") }
                            TextButton(onClick = { doRedo() }, enabled = undoMgr.canRedo) { Text("↻ やり直し") }
                            TextButton(onClick = { toggleCheckbox() }) { Text("☑ チェック") }
                            TextButton(onClick = {
                                lineNumbers = !lineNumbers
                                store.lineNumbers = lineNumbers
                            }) { Text("# 行番号", color = if (lineNumbers) cs.primary else Color.Unspecified) }
                        }
                        IconButton(onClick = { closeAndSave() }, modifier = Modifier.size(40.dp)) { Text("✓") }
                    }
                    HorizontalDivider()
                    // 本文
                    Box(
                        (if (showSettings && widgetMode) Modifier.fillMaxWidth().height(((if (widgetMode) style.editSizeSp else 16) * 1.5f * 5 + 28).dp)
                        else Modifier.weight(1f).fillMaxWidth())
                            .padding(top = 6.dp)
                            .background(editorBg, RoundedCornerShape(8.dp))
                            .paperTexture(if (widgetMode) (if (style.paper) style.paperStrength else 0) else store.appPaper, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        if (loaded) {
                            val tf: @Composable (Modifier) -> Unit = { m ->
                                BasicTextField(
                                    value = text,
                                    onValueChange = { applyText(it) },
                                    enabled = !showSettings,
                                    onTextLayout = { tl = it },
                                    textStyle = TextStyle(
                                        color = editorFg,
                                        fontSize = (if (widgetMode) style.editSizeSp else 16).sp,
                                        fontWeight = if (widgetMode && style.bold) FontWeight.Bold else FontWeight.Normal,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                                        fontSynthesis = androidx.compose.ui.text.font.FontSynthesis.All,
                                        fontStyle = if (widgetMode && style.italic) FontStyle.Italic else FontStyle.Normal,
                                        textAlign = if (!widgetMode) TextAlign.Start else when (style.hAlign) {
                                            1 -> TextAlign.Center
                                            2 -> TextAlign.End
                                            else -> TextAlign.Start
                                        },
                                    ),
                                    cursorBrush = SolidColor(editorFg),
                                    modifier = m.focusRequester(focus).pointerInput(Unit) {
                                        // チェックボックス(☐/☑)をタップしたら、チェックとオフを切り替える
                                        while (true) {
                                            val tapped = awaitPointerEventScope {
                                                val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                                val up = waitForUpOrCancellation(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                                up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop &&
                                                    up.uptimeMillis - down.uptimeMillis < 300
                                            }
                                            if (tapped) {
                                                delay(60) // 文字入力欄がカーソルを動かすのを待つ
                                                tapToggleCheckbox()
                                            }
                                        }
                                    },
                                )
                            }
                            if (lineNumbers) {
                                Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                    Box(Modifier.width(34.dp)) {
                                        val lay = tl
                                        val str = text.text
                                        if (lay != null) {
                                            val starts = ArrayList<Int>()
                                            starts.add(0)
                                            for (i in str.indices) if (str[i] == '\n') starts.add(i + 1)
                                            if (starts.size <= 3000) {
                                                starts.forEachIndexed { idx, st ->
                                                    val ln = lay.getLineForOffset(st.coerceAtMost(str.length))
                                                    val top = lay.getLineTop(ln)
                                                    Text(
                                                        "${idx + 1}",
                                                        modifier = Modifier.width(28.dp).offset { androidx.compose.ui.unit.IntOffset(0, top.toInt()) },
                                                        textAlign = TextAlign.End,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        color = editorFg.copy(alpha = 0.45f),
                                                        fontSize = (if (widgetMode) style.editSizeSp else 16).sp,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    tf(Modifier.weight(1f).heightIn(min = 420.dp))
                                }
                            } else {
                                tf(Modifier.fillMaxSize())
                            }
                        } else {
                            Text("読み込み中…", color = editorFg)
                        }
                    }
                    // 設定パネル
                    if (showSettings && widgetMode) {
                        Column(
                            Modifier.fillMaxWidth().weight(1f)
                                .padding(top = 6.dp).verticalScroll(rememberScrollState())
                        ) {
                            StylePanel(
                                style = shownStyle(),
                                pageCount = pageCount,
                                pageNo = page,
                                onPageCount = { pageCount = it },
                            ) { ns ->
                                if (ns.bgColor != (pageBg ?: style.bgColor)) pageBg = ns.bgColor
                                style = ns.copy(bgColor = style.bgColor)
                            }
                        }
                    }
                }
            }
            }
        }

        histPick?.let { h ->
            AlertDialog(
                onDismissRequest = { histPick = null },
                title = { Text("この版に戻しますか？") },
                text = { Text("${histFmt.format(Date(h.v.time))} の内容を読み込みます。今の内容も履歴に残り、「↺ 戻す」でも元に戻せます。") },
                confirmButton = {
                    TextButton(onClick = {
                        histPick = null
                        dlg = Dlg.NONE
                        restoreVersion(h)
                    }) { Text("戻す") }
                },
                dismissButton = { TextButton(onClick = { histPick = null }) { Text("キャンセル") } },
            )
        }

        pendingEnc?.let { b ->
            EncodingDialog(
                bytes = b,
                onOpenAs = { encOpenAs(it) },
                onConvert = { encConvert(it) },
                onCancel = { finish() },
            )
        }

        when (dlg) {
            Dlg.NONE -> {}
            Dlg.HISTORY -> AlertDialog(
                onDismissRequest = { dlg = Dlg.NONE },
                title = { Text("履歴") },
                text = {
                    val list = histItems
                    if (list == null) {
                        Text("読み込み中…")
                    } else if (list.isEmpty()) {
                        Text("履歴はまだありません")
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                            items(list, key = { it.v.time }) { h ->
                                Column(Modifier.fillMaxWidth().clickable { histPick = h }.padding(vertical = 8.dp)) {
                                    Text(histFmt.format(Date(h.v.time)), style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        h.preview.ifEmpty { "（空）" },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dlg = Dlg.NONE }) { Text("閉じる") } },
            )
            Dlg.DELETE -> AlertDialog(
                onDismissRequest = { dlg = Dlg.NONE },
                title = { Text("このメモを削除しますか？") },
                text = { Text("txtファイルも削除されます。元に戻せません。") },
                confirmButton = { TextButton(onClick = { dlg = Dlg.NONE; deleteMemo() }) { Text("削除") } },
                dismissButton = { TextButton(onClick = { dlg = Dlg.NONE }) { Text("キャンセル") } },
            )
            Dlg.CLEAR -> AlertDialog(
                onDismissRequest = { dlg = Dlg.NONE },
                title = { Text("メモの内容をすべて消しますか？") },
                text = { Text("「↺ 戻す」で元に戻せます（画面を閉じるまで）。") },
                confirmButton = {
                    TextButton(onClick = {
                        dlg = Dlg.NONE
                        applyText(TextFieldValue("", TextRange(0)))
                    }) { Text("クリア") }
                },
                dismissButton = { TextButton(onClick = { dlg = Dlg.NONE }) { Text("キャンセル") } },
            )
            Dlg.REMIND -> AlertDialog(
                onDismissRequest = { dlg = Dlg.NONE },
                title = { Text("リマインダー") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("このメモを指定の日時に通知します。通知を押すとこのメモが開きます。", style = MaterialTheme.typography.bodySmall)
                        Text(
                            java.text.SimpleDateFormat("yyyy/MM/dd (E) HH:mm", java.util.Locale.getDefault()).format(Date(remAt)),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val c = java.util.Calendar.getInstance().apply { timeInMillis = remAt }
                                android.app.DatePickerDialog(this@EditActivity, { _, y, m, d ->
                                    val n = java.util.Calendar.getInstance().apply { timeInMillis = remAt; set(y, m, d) }
                                    remAt = n.timeInMillis
                                }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show()
                            }) { Text("日付") }
                            OutlinedButton(onClick = {
                                val c = java.util.Calendar.getInstance().apply { timeInMillis = remAt }
                                android.app.TimePickerDialog(this@EditActivity, { _, h, mi ->
                                    val n = java.util.Calendar.getInstance().apply { timeInMillis = remAt; set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, mi); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
                                    remAt = n.timeInMillis
                                }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), true).show()
                            }) { Text("時刻") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("1回だけ", "毎日", "毎週").forEachIndexed { i, l ->
                                androidx.compose.material3.FilterChip(selected = remRepeat == i, onClick = { remRepeat = i }, label = { Text(l) })
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (remAt <= System.currentTimeMillis()) {
                            Toast.makeText(this@EditActivity, "未来の日時を指定してください", Toast.LENGTH_SHORT).show()
                        } else {
                            dlg = Dlg.NONE
                            setReminder(remAt, remRepeat)
                        }
                    }) { Text("設定") }
                },
                dismissButton = {
                    Row {
                        if (remExists) TextButton(onClick = { dlg = Dlg.NONE; clearReminder() }) { Text("解除") }
                        TextButton(onClick = { dlg = Dlg.NONE }) { Text("キャンセル") }
                    }
                },
            )
            Dlg.RENAME -> AlertDialog(
                onDismissRequest = { dlg = Dlg.NONE },
                title = { Text("ファイル名を決める") },
                text = {
                    Column {
                        Text("手動で決めると、1行目を書き換えてもファイル名は変わりません。", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            singleLine = true,
                            label = { Text("ファイル名（.txtは自動）") },
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        dlg = Dlg.NONE
                        if (renameText.isNotBlank()) renameManually(renameText)
                    }) { Text("変更") }
                },
                dismissButton = { TextButton(onClick = { dlg = Dlg.NONE }) { Text("キャンセル") } },
            )
        }
    }
}
