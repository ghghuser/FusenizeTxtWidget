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
import com.ghghuser.fusenizetxt.ui.ConfigActivity
import com.ghghuser.fusenizetxt.ui.EditActivity
import kotlinx.coroutines.launch

/**
 * ウィジェットの描画（標準のRemoteViews）。
 * 保存した瞬間に AppWidgetManager.updateAppWidget で反映するため、待ち時間がない。
 */
object WidgetRender {
    private const val MAX_CHARS = 3000
    const val ACTION_PAGE = "com.ghghuser.fusenizetxt.action.PAGE"
    const val EXTRA_ID = "w_id"
    const val EXTRA_TO = "w_to"

    private val TAB_IDS = intArrayOf(R.id.w_tab0, R.id.w_tab1, R.id.w_tab2, R.id.w_tab3, R.id.w_tab4)

    fun updateAll(ctx: Context) {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        for (id in mgr.getAppWidgetIds(ComponentName(app, FusenReceiver::class.java))) update(app, mgr, id)
    }

    fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
        try {
            mgr.updateAppWidget(id, build(ctx, id))
        } catch (e: Exception) {
            // 1つのウィジェットの描画失敗で他の更新を止めない
        }
    }

    /** (表示テキスト, プレースホルダかどうか) */
    private fun resolveText(ctx: Context, cfg: WidgetConfig): Pair<String, Boolean> {
        val u = cfg.fileUri ?: return "タップしてメモを選択" to true
        val uri = Uri.parse(u)
        val read = MemoRepo.readText(ctx, uri)
        val text = read.getOrNull()?.text
        return when {
            text != null -> if (text.isEmpty()) "（タップして入力）" to true else text.take(MAX_CHARS) to false
            !MemoRepo.exists(ctx, uri) -> "ファイルが見つかりません\nタップして再設定" to true
            read.exceptionOrNull() is MemoRepo.EncodingException -> "文字コードの確認が必要です\nタップして開く" to true
            cfg.cachedText.isNotEmpty() -> cfg.cachedText.take(MAX_CHARS) to false
            else -> ("読み込めません\n" + (read.exceptionOrNull()?.message ?: "")) to true
        }
    }

    private fun build(ctx: Context, id: Int): RemoteViews {
        val cfg = Store(ctx).getConfig(id)
        val page = cfg.cur
        val s = cfg.styleFor(page)
        val (text, placeholder) = resolveText(ctx, cfg)
        val d = ctx.resources.displayMetrics.density
        fun px(dp: Int) = (dp * d + 0.5f).toInt()

        val rv = RemoteViews(ctx.packageName, R.layout.widget_note)

        // 余白（外側: 枠と背景の間、内側: 背景と文字の間）
        rv.setViewPadding(R.id.w_root, px(s.outer.l), px(s.outer.t), px(s.outer.r), px(s.outer.b))
        rv.setViewPadding(R.id.w_text_box, px(s.inner.l), px(s.inner.t), px(s.inner.r), px(s.inner.b))

        // 背景色と透過度（形は角丸のdrawable、色はフィルタ、透過度はimageAlpha）
        rv.setInt(R.id.w_bg, "setColorFilter", rgbToArgb(s.bgColor))
        rv.setInt(R.id.w_bg, "setImageAlpha", s.bgAlpha.coerceIn(0, 255))

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
        rv.setTextViewText(tid, styled)
        rv.setTextViewTextSize(tid, TypedValue.COMPLEX_UNIT_SP, s.textSizeSp.toFloat())
        rv.setTextColor(tid, fg)

        // 垂直配置: 上下のスペーサー（重み付き）の表示/非表示で上・中央・下を作る
        rv.setViewVisibility(R.id.w_v_top, if (s.vAlign == 0) View.GONE else View.VISIBLE)
        rv.setViewVisibility(R.id.w_v_bottom, if (s.vAlign == 2) View.GONE else View.VISIBLE)

        // タップで編集画面（未設定なら設定画面）
        val linked = cfg.fileUri != null
        val intent = Intent(ctx, if (linked) EditActivity::class.java else ConfigActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(EditActivity.EXTRA_PAGE, page)
            data = Uri.parse("fusen://widget/$id/$page")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
            val label = SpannableString("${i + 1}")
            if (on) label.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            rv.setViewVisibility(tab, View.VISIBLE)
            rv.setTextViewText(tab, label)
            rv.setTextColor(tab, rgbToArgb(contrastTextRgb(tabBg), if (on) 255 else 170))
            rv.setInt(tab, "setBackgroundColor", rgbToArgb(tabBg, if (on) 255 else 120))
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
        Store(app).updateConfig(id) { it.copy(current = to.coerceIn(0, it.pages.size - 1)) }
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
