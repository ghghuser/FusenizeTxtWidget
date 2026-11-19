package com.ghghuser.fusenizetxt.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.ghghuser.fusenizetxt.R
import com.ghghuser.fusenizetxt.data.Store
import com.ghghuser.fusenizetxt.data.rgbToArgb

/** ウィジェットの長文をスクロールして読めるようにするための、行ごとのリスト */
class NoteListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))

    private class Factory(private val ctx: Context, private val id: Int) : RemoteViewsFactory {
        private var lines: List<String> = emptyList()

        override fun onCreate() {}

        override fun onDataSetChanged() {
            lines = try {
                val cfg = Store(ctx).getConfig(id)
                val (text, placeholder) = WidgetRender.resolveText(ctx, cfg, WidgetRender.MAX_LIST_CHARS)
                if (placeholder) emptyList() else text.split('\n').take(3000)
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun onDestroy() {
            lines = emptyList()
        }

        override fun getCount(): Int = lines.size

        override fun getViewAt(position: Int): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_note_item)
            val cfg = Store(ctx).getConfig(id)
            val s = cfg.styleFor(cfg.cur)
            val line = lines.getOrNull(position)?.ifEmpty { " " } ?: " "
            val sp = SpannableString(line)
            val face = when {
                s.bold && s.italic -> Typeface.BOLD_ITALIC
                s.bold -> Typeface.BOLD
                s.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            if (face != Typeface.NORMAL) sp.setSpan(StyleSpan(face), 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            rv.setTextViewText(R.id.w_item, sp)
            rv.setTextViewTextSize(R.id.w_item, TypedValue.COMPLEX_UNIT_SP, s.textSizeSp.toFloat())
            rv.setTextColor(R.id.w_item, rgbToArgb(s.effectiveTextRgb()))
            rv.setInt(R.id.w_item, "setGravity", when (s.hAlign) { 1 -> Gravity.CENTER_HORIZONTAL; 2 -> Gravity.END; else -> Gravity.START })
            rv.setOnClickFillInIntent(R.id.w_item, Intent())
            return rv
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = false
    }
}
