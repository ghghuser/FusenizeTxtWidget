package com.ghghuser.fusenize.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontStyle
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ghghuser.fusenize.data.MemoRepo
import com.ghghuser.fusenize.data.Store
import com.ghghuser.fusenize.data.WidgetConfig
import com.ghghuser.fusenize.data.rgbToArgb
import com.ghghuser.fusenize.ui.ConfigActivity
import com.ghghuser.fusenize.ui.EditActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_WIDGET_CHARS = 3000

class FusenWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val store = Store(context)
        val cfg = store.getConfig(appWidgetId)
        val state = withContext(Dispatchers.IO) { resolveText(context, cfg) }
        provideContent {
            FusenContent(context, appWidgetId, cfg, state.first, state.second)
        }
    }

    /** (表示テキスト, プレースホルダかどうか) */
    private fun resolveText(context: Context, cfg: WidgetConfig): Pair<String, Boolean> {
        val u = cfg.fileUri ?: return "タップしてメモを選択" to true
        val uri = Uri.parse(u)
        val read = MemoRepo.readText(context, uri)
        val text = read.getOrNull()?.text
        return when {
            text != null -> if (text.isEmpty()) "（空のメモ）" to true else text.take(MAX_WIDGET_CHARS) to false
            !MemoRepo.exists(context, uri) -> "ファイルが見つかりません\nタップして再設定" to true
            read.exceptionOrNull() is MemoRepo.EncodingException -> "文字コードの確認が必要です\nタップして開く" to true
            cfg.cachedText.isNotEmpty() -> cfg.cachedText.take(MAX_WIDGET_CHARS) to false
            else -> "読み込めません" to true
        }
    }
}

@androidx.compose.runtime.Composable
private fun FusenContent(
    context: Context,
    appWidgetId: Int,
    cfg: WidgetConfig,
    text: String,
    placeholder: Boolean,
) {
    val s = cfg.style
    val linked = cfg.fileUri != null
    val intent = Intent(context, if (linked) EditActivity::class.java else ConfigActivity::class.java).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        data = Uri.parse("fusen://widget/$appWidgetId")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    val horizontal = when (s.hAlign) {
        1 -> Alignment.CenterHorizontally
        2 -> Alignment.End
        else -> Alignment.Start
    }
    val vertical = when (s.vAlign) {
        1 -> Alignment.CenterVertically
        2 -> Alignment.Bottom
        else -> Alignment.Top
    }
    val textAlign = when (s.hAlign) {
        1 -> TextAlign.Center
        2 -> TextAlign.End
        else -> TextAlign.Start
    }

    val bg = Color(rgbToArgb(s.bgColor, s.bgAlpha))
    var fg = Color(rgbToArgb(s.effectiveTextRgb()))
    if (placeholder) fg = fg.copy(alpha = 0.6f)

    Box(
        modifier = GlanceModifier.fillMaxSize()
            .padding(start = s.outer.l.dp, top = s.outer.t.dp, end = s.outer.r.dp, bottom = s.outer.b.dp)
    ) {
        Box(
            modifier = GlanceModifier.fillMaxSize()
                .background(ColorProvider(bg))
                .cornerRadius(8.dp)
                .padding(start = s.inner.l.dp, top = s.inner.t.dp, end = s.inner.r.dp, bottom = s.inner.b.dp)
                .clickable(actionStartActivity(intent)),
            contentAlignment = Alignment(horizontal, vertical),
        ) {
            Text(
                text = text,
                modifier = GlanceModifier.fillMaxWidth(),
                style = TextStyle(
                    color = ColorProvider(fg),
                    fontSize = s.textSizeSp.sp,
                    fontWeight = if (s.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (s.italic) FontStyle.Italic else FontStyle.Normal,
                    textAlign = textAlign,
                ),
            )
        }
    }
}

class FusenReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FusenWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        // ウィジェットの設定だけ消す。txtは残す。
        val store = Store(context)
        appWidgetIds.forEach { store.removeConfig(it) }
    }
}
