package com.ghghuser.fusenizetxt.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.ghghuser.fusenizetxt.FusenApp
import com.ghghuser.fusenizetxt.R
import com.ghghuser.fusenizetxt.data.MemoRepo
import com.ghghuser.fusenizetxt.data.Store
import com.ghghuser.fusenizetxt.data.WidgetConfig
import com.ghghuser.fusenizetxt.data.contrastTextRgb
import com.ghghuser.fusenizetxt.data.rgbToArgb
import com.ghghuser.fusenizetxt.data.softenedTextRgb
import com.ghghuser.fusenizetxt.ui.ConfigActivity
import com.ghghuser.fusenizetxt.ui.EditActivity
import kotlinx.coroutines.launch

/**
 * ウィジェットの描画（標準のRemoteViews）。
 * 保存した瞬間に AppWidgetManager.updateAppWidget で反映するため、待ち時間がない。
 */
object WidgetRender {
    private const val MAX_CHARS = 3000
    internal const val MAX_LIST_CHARS = 30000
    private const val MAX_LIST_LINES = 3000
    const val ACTION_PAGE = "com.ghghuser.fusenizetxt.action.PAGE"
    const val EXTRA_ID = "w_id"
    const val EXTRA_TO = "w_to"

    /** タブに出すタイトル（そのページのメモのファイル名）。未設定のページは「−」 */
    private fun tabTitle(ctx: Context, cfg: WidgetConfig, i: Int): String {
        val pg = cfg.pages[i]
        val uri = pg.fileUri ?: return "−"
        val nm = runCatching { MemoRepo.displayName(ctx, Uri.parse(uri)) }.getOrNull() ?: return "−"
        return MemoRepo.shownTitle(nm, pg.manualName || Store(ctx).isManual(uri)).ifBlank { "−" }
    }

    private val TAB_IDS = intArrayOf(R.id.w_tab0, R.id.w_tab1, R.id.w_tab2, R.id.w_tab3, R.id.w_tab4)
    private val TABT_IDS = intArrayOf(R.id.w_tabt0, R.id.w_tabt1, R.id.w_tabt2, R.id.w_tabt3, R.id.w_tabt4)
    private val TABB_IDS = intArrayOf(R.id.w_tabb0, R.id.w_tabb1, R.id.w_tabb2, R.id.w_tabb3, R.id.w_tabb4)

    fun updateAll(ctx: Context) {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        for (id in mgr.getAppWidgetIds(ComponentName(app, FusenReceiver::class.java))) update(app, mgr, id)
    }

    // 更新が同時に走ると、古い状態で作った表示が後から上書きされる（背景色・タップ先が前のページのまま残る）ため、
    // 1つずつ順番に、実行時点の最新の設定から作る
    private val updateLock = Any()

    fun update(ctx: Context, mgr: AppWidgetManager, id: Int) = synchronized(updateLock) {
        try {
            mgr.updateAppWidget(id, build(ctx, id))
        } catch (e: Throwable) {
            // 描画に失敗すると古い表示が残る（背景色やタップ先が前のページのまま）ため、簡易表示で必ず更新する
            try {
                mgr.updateAppWidget(id, build(ctx, id, safe = true))
            } catch (_: Throwable) {
            }
        }

    }

    /** (表示テキスト, プレースホルダかどうか) */
    internal fun resolveText(ctx: Context, cfg: WidgetConfig, limit: Int = MAX_CHARS): Pair<String, Boolean> {
        val u = cfg.fileUri ?: return "タップでメモを選択" to true
        val uri = Uri.parse(u)
        val read = MemoRepo.readText(ctx, uri)
        val text = read.getOrNull()?.text
        return when {
            text != null -> if (text.isEmpty()) {
                // 中身が空で、自分で付けたタイトルがある時はタイトルを表示（書かれたら中身を表示）
                val manual = cfg.manualName || Store(ctx).isManual(u)
                val title = if (manual) MemoRepo.displayName(ctx, uri)?.removeSuffix(".txt")?.removeSuffix(".TXT") else null
                if (!title.isNullOrBlank() && !cfg.styleFor(cfg.cur).showTitle) title to false else "（タップで入力）" to true
            } else text.take(limit) to false
            !MemoRepo.exists(ctx, uri) -> "ファイルが見つからない\nタップで再設定" to true
            read.exceptionOrNull() is MemoRepo.EncodingException -> "文字コードの確認が必要\nタップで開く" to true
            cfg.cachedText.isNotEmpty() -> cfg.cachedText.take(limit) to false
            else -> ("読み込みに失敗\n" + (read.exceptionOrNull()?.message ?: "")) to true
        }
    }

    /** 表示領域に収まらなさそうか（文字数と大きさからの概算） */
    private fun likelyOverflows(ctx: Context, id: Int, cfg: WidgetConfig, s: com.ghghuser.fusenizetxt.data.WidgetStyle, text: String): Boolean {
        val o = try {
            AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
        } catch (e: Exception) {
            return text.length > 200
        }
        val w = minOf(o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0), o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, Int.MAX_VALUE))
        val h = minOf(o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0), o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, Int.MAX_VALUE))
        if (w <= 0 || h <= 0) return text.length > 200
        val ts = s.textSizeSp.toFloat()
        var availH = h - (s.outer.t + s.outer.b + s.inner.t + s.inner.b).toFloat()
        if (cfg.pages.size > 1) availH -= s.tabHeightDp
        if (s.showTitle) availH -= s.titleSizeSp * 1.5f
        val availW = (w - (s.outer.l + s.outer.r + s.inner.l + s.inner.r)).toFloat().coerceAtLeast(ts)
        val visibleLines = (availH / (ts * 1.4f)).coerceAtLeast(1f)
        var lines = 0
        for (ln in text.split('\n')) {
            var width = 0f
            for (ch in ln) width += if (ch.code < 0x80) ts * 0.55f else ts
            lines += maxOf(1, Math.ceil((width / availW).toDouble()).toInt())
            if (lines > visibleLines * 2) return true
        }
        return lines >= visibleLines * 0.85f
    }

    private fun build(ctx: Context, id: Int, safe: Boolean = false): RemoteViews {
        val cfg = Store(ctx).getConfig(id)
        val page = cfg.cur
        val s = cfg.styleFor(page)
        val (text, placeholder) = if (safe) ("表示に失敗\nタップで開く" to true)
        else runCatching { resolveText(ctx, cfg) }.getOrElse { "読み込みに失敗\nタップで開く" to true }
        val d = ctx.resources.displayMetrics.density
        fun px(dp: Int) = (dp * d + 0.5f).toInt()

        val useList = !safe && s.scroll && !placeholder && runCatching { likelyOverflows(ctx, id, cfg, s, text) }.getOrDefault(false)
        val rv = RemoteViews(
            ctx.packageName,
            // 一覧（ListView）は、長文をスクロール表示するページだけに含める（一覧があるとランチャーの表示更新が滞ることがあるため）
            if (useList) R.layout.widget_note_list else R.layout.widget_note,
        )

        // 余白（外側: 枠と背景の間、内側: 背景と文字の間）
        rv.setViewPadding(R.id.w_root, px(s.outer.l), px(s.outer.t), px(s.outer.r), px(s.outer.b))
        rv.setViewPadding(R.id.w_text_box, px(s.inner.l), px(s.inner.t), px(s.inner.r), px(s.inner.b))

        // 背景色と透過度（形は角丸のdrawable、色はフィルタ、透過度はimageAlpha）
        val hasTabs = cfg.pages.size > 1
        rv.setImageViewResource(R.id.w_paper, if (hasTabs) R.drawable.widget_note_paper_top else R.drawable.widget_note_paper)
        rv.setImageViewResource(R.id.w_bg, if (hasTabs) R.drawable.widget_note_bg_top else R.drawable.widget_note_bg)
        rv.setInt(R.id.w_bg, "setColorFilter", rgbToArgb(s.bgColor))
        rv.setInt(R.id.w_bg, "setImageAlpha", s.bgAlpha.coerceIn(0, 255))
        rv.setViewVisibility(R.id.w_paper, if (s.paper) android.view.View.VISIBLE else android.view.View.GONE)
        rv.setInt(R.id.w_paper, "setImageAlpha", (s.bgAlpha.coerceIn(0, 255) * s.paperStrength / 100).coerceIn(0, 255))

        // 文字
        val tid = when (s.hAlign) {
            1 -> R.id.w_t_c
            2 -> R.id.w_t_r
            else -> R.id.w_t_l
        }
        for (t in intArrayOf(R.id.w_t_l, R.id.w_t_c, R.id.w_t_r)) {
            rv.setViewVisibility(t, if (t == tid) View.VISIBLE else View.GONE)
        }
        val styled = SpannableString(text)
        val face = when {
            s.bold && s.italic -> Typeface.BOLD_ITALIC
            s.bold -> Typeface.BOLD
            s.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        if (face != Typeface.NORMAL && text.isNotEmpty()) {
            styled.setSpan(StyleSpan(face), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val fgRgb = s.effectiveTextRgb()
        val fg = if (placeholder) (fgRgb and 0xFFFFFF) or (0x99 shl 24) else rgbToArgb(fgRgb)
        rv.setTextViewText(tid, if (useList) "" else styled)
        rv.setTextViewTextSize(tid, TypedValue.COMPLEX_UNIT_SP, s.textSizeSp.toFloat())
        rv.setTextColor(tid, fg)

        // 長文はリスト表示に切り替えてスクロールできるようにする（短文は従来どおり配置指定を反映）
        if (useList) {
            for (t in intArrayOf(R.id.w_t_l, R.id.w_t_c, R.id.w_t_r)) rv.setViewVisibility(t, View.GONE)
            rv.setViewVisibility(R.id.w_list, View.VISIBLE)
            rv.setRemoteAdapter(
                R.id.w_list,
                Intent(ctx, NoteListService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    // 更新のたびに別のつなぎ先として扱わせ、ランチャーに一覧ごと作り直させる（古い一覧が残って表示更新を邪魔するのを避ける）
                    data = Uri.parse("fusen://list/$id/${System.currentTimeMillis()}")
                },
            )
        }

        // 上部のタイトル（ファイル名）。ウィジェットの設定でオン/オフ・文字サイズを指定
        val titleStr = if (!safe && s.showTitle && cfg.fileUri != null) {
            val uu = Uri.parse(cfg.fileUri)
            val nm = MemoRepo.displayName(ctx, uu)
            nm?.let { MemoRepo.shownTitle(it, cfg.manualName || Store(ctx).isManual(cfg.fileUri!!)) }
        } else null
        if (!titleStr.isNullOrBlank()) {
            val ts = SpannableString(titleStr)
            ts.setSpan(StyleSpan(Typeface.BOLD), 0, ts.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            rv.setViewVisibility(R.id.w_title, View.VISIBLE)
            rv.setTextViewText(R.id.w_title, ts)
            rv.setTextViewTextSize(R.id.w_title, TypedValue.COMPLEX_UNIT_SP, s.titleSizeSp.toFloat())
            rv.setTextColor(R.id.w_title, rgbToArgb(softenedTextRgb(fgRgb, s.bgColor))) // 背景に少し寄せた色（読める濃さは確保）
            rv.setInt(R.id.w_title, "setGravity", when (s.hAlign) { 1 -> android.view.Gravity.CENTER_HORIZONTAL; 2 -> android.view.Gravity.END; else -> android.view.Gravity.START })
        } else {
            rv.setViewVisibility(R.id.w_title, View.GONE)
        }

        // 垂直配置: 上下のスペーサー（重み付き）の表示/非表示で上・中央・下を作る
        rv.setViewVisibility(R.id.w_v_top, if (s.vAlign == 0) View.GONE else View.VISIBLE)
        rv.setViewVisibility(R.id.w_v_bottom, if (s.vAlign == 2) View.GONE else View.VISIBLE)

        // タップで編集画面（未設定なら設定画面）
        val linked = cfg.fileUri != null
        val intent = Intent(ctx, if (linked) EditActivity::class.java else ConfigActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(EditActivity.EXTRA_PAGE, page)
            data = Uri.parse("fusen://widget/$id/$page")
            // 以前の編集画面が残っていると別ページの内容が開くため、毎回作り直す
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        if (useList) {
            rv.setPendingIntentTemplate(
                R.id.w_list,
                PendingIntent.getActivity(
                    ctx, 200000 + id * 10 + page, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
                ),
            )
        }
        rv.setOnClickPendingIntent(
            R.id.w_card,
            PendingIntent.getActivity(ctx, id * 10 + page, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )

        // 最下段: ページ切り替え（ページごとの背景色）
        val n = cfg.pages.size
        rv.setViewVisibility(R.id.w_tabs, if (n > 1) View.VISIBLE else View.GONE)
        for (i in TAB_IDS.indices) {
            val tab = TAB_IDS[i]
            if (i >= n) {
                rv.setViewVisibility(tab, View.GONE)
                continue
            }
            val on = i == page
            val tabBg = cfg.styleFor(i).bgColor
            val tabStyle = cfg.styleFor(i)
            val padPx = px(((s.tabHeightDp - 15) / 2).coerceAtLeast(0))
            rv.setViewPadding(TABT_IDS[i], 0, padPx, 0, padPx)
            val label = SpannableString(
                when (s.tabLabel) {
                    1 -> tabTitle(ctx, cfg, i)
                    2 -> ""
                    else -> "${i + 1}"
                },
            )
            if (on && label.isNotEmpty()) label.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            rv.setViewVisibility(tab, View.VISIBLE)
            rv.setTextViewText(TABT_IDS[i], label)
            // タブの背景は、対応するページのウィジェット背景（色・透過度）と同じ。選択中は太字で示す
            rv.setTextColor(TABT_IDS[i], rgbToArgb(contrastTextRgb(tabBg), if (on) 255 else 170))
            // 最初のタブは左下、最後のタブは右下だけ丸く（メモ本体の形とつなげる）
            rv.setImageViewResource(
                TABB_IDS[i],
                when {
                    n == 1 -> R.drawable.tab_single
                    i == 0 -> R.drawable.tab_left
                    i == n - 1 -> R.drawable.tab_right
                    else -> R.drawable.tab_mid
                },
            )
            rv.setInt(TABB_IDS[i], "setColorFilter", rgbToArgb(tabBg))
            rv.setInt(TABB_IDS[i], "setImageAlpha", tabStyle.bgAlpha.coerceIn(0, 255))
            val sw = Intent(ctx, PageSwitchReceiver::class.java).apply {
                action = ACTION_PAGE
                data = Uri.parse("fusen://page/$id/$i")
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_TO, i)
            }
            rv.setOnClickPendingIntent(
                tab,
                PendingIntent.getBroadcast(ctx, 100000 + id * 10 + i, sw, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }
        return rv
    }
}

class FusenReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        FusenApp.scope.launch {
            try {
                for (id in appWidgetIds) WidgetRender.update(app, appWidgetManager, id)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        // ウィジェットの設定だけ消す。txtは残す。
        val store = Store(context)
        appWidgetIds.forEach { store.removeConfig(it) }
    }
}

/** ウィジェット最下段のページ切り替えボタンのタップを受け取る */
class PageSwitchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(WidgetRender.EXTRA_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val to = intent.getIntExtra(WidgetRender.EXTRA_TO, 0)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val app = context.applicationContext
        val st = Store(app)
        st.updateConfig(id) { it.copy(current = to.coerceIn(0, it.pages.size - 1)) }
        val pending = goAsync()
        FusenApp.scope.launch {
            try {
                WidgetRender.update(app, AppWidgetManager.getInstance(app), id)
            } finally {
                pending.finish()
            }
        }
    }
}
