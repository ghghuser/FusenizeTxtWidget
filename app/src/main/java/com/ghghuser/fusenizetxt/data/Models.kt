package com.ghghuser.fusenizetxt.data

import org.json.JSONObject

/** RGB(24bit) とアルファから ARGB Int を作る */
fun rgbToArgb(rgb: Int, alpha: Int = 255): Int =
    ((alpha and 0xFF) shl 24) or (rgb and 0xFFFFFF)

/** 背景色に対して読みやすい文字色(RGB)を返す。明るい背景なら濃い色、暗い背景なら白 */
fun contrastTextRgb(bgRgb: Int): Int {
    val r = (bgRgb shr 16) and 0xFF
    val g = (bgRgb shr 8) and 0xFF
    val b = bgRgb and 0xFF
    val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    return if (lum > 0.55) 0x212121 else 0xFFFFFF
}

data class Insets(val l: Int = 0, val t: Int = 0, val r: Int = 0, val b: Int = 0) {
    fun toJson(): JSONObject = JSONObject().put("l", l).put("t", t).put("r", r).put("b", b)

    companion object {
        fun fromJson(o: JSONObject?, d: Insets): Insets =
            if (o == null) d else Insets(
                o.optInt("l", d.l), o.optInt("t", d.t), o.optInt("r", d.r), o.optInt("b", d.b)
            )
    }
}

data class WidgetStyle(
    val bgColor: Int = 0x1B2A5E, // 紺色
    val bgAlpha: Int = 200,
    val autoText: Boolean = true, // 背景色に合わせて文字色を自動で決める
    val textColor: Int = 0xF5F5F5, // 明るい文字（autoText=trueなら背景に合わせて自動）
    val textSizeSp: Int = 16,
    val hAlign: Int = 0, // 0=左 1=中央 2=右
    val vAlign: Int = 0, // 0=上 1=中央 2=下
    val bold: Boolean = false,
    val italic: Boolean = false,
    val outer: Insets = Insets(4, 4, 4, 4),
    val inner: Insets = Insets(10, 10, 10, 10),
) {
    /** 実際に使う文字色(RGB) */
    fun effectiveTextRgb(): Int = if (autoText) contrastTextRgb(bgColor) else textColor

    fun toJson(): JSONObject = JSONObject()
        .put("bg", bgColor).put("bgA", bgAlpha).put("auto", autoText).put("fg", textColor)
        .put("size", textSizeSp).put("h", hAlign).put("v", vAlign)
        .put("bold", bold).put("italic", italic)
        .put("outer", outer.toJson()).put("inner", inner.toJson())

    companion object {
        fun fromJson(o: JSONObject?): WidgetStyle {
            val d = WidgetStyle()
            if (o == null) return d
            return WidgetStyle(
                bgColor = o.optInt("bg", d.bgColor) and 0xFFFFFF,
                bgAlpha = o.optInt("bgA", d.bgAlpha).coerceIn(0, 255),
                autoText = o.optBoolean("auto", d.autoText),
                textColor = o.optInt("fg", d.textColor) and 0xFFFFFF,
                textSizeSp = o.optInt("size", d.textSizeSp).coerceIn(12, 32),
                hAlign = o.optInt("h", d.hAlign).coerceIn(0, 2),
                vAlign = o.optInt("v", d.vAlign).coerceIn(0, 2),
                bold = o.optBoolean("bold", d.bold),
                italic = o.optBoolean("italic", d.italic),
                outer = Insets.fromJson(o.optJSONObject("outer"), d.outer),
                inner = Insets.fromJson(o.optJSONObject("inner"), d.inner),
            )
        }
    }
}

const val MAX_PAGES = 5

/** 追加したページの初期背景色（ページごとに色が違う） */
val PAGE_COLORS = listOf(0x1B2A5E, 0x1E4D3A, 0x4A2D6B, 0x6B3A1E, 0x1E5560)

/** ウィジェットの1ページ分（1ページ = 1つのtxt） */
data class PageCfg(
    val fileUri: String? = null,
    val manualName: Boolean = false,
    val cachedText: String = "",
    val bgColor: Int? = null, // ページ別の背景色（null = ウィジェット共通の色）
) {
    fun toJson(): JSONObject = JSONObject()
        .put("uri", fileUri ?: JSONObject.NULL)
        .put("manual", manualName)
        .put("cache", cachedText)
        .put("bg", bgColor ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): PageCfg = PageCfg(
            fileUri = if (o.isNull("uri")) null else o.optString("uri").ifEmpty { null },
            manualName = o.optBoolean("manual", false),
            cachedText = o.optString("cache", ""),
            bgColor = if (o.isNull("bg") || !o.has("bg")) null else o.optInt("bg") and 0xFFFFFF,
        )
    }
}

/** ウィジェット1つ分の設定（ウィジェットID単位）。最大5ページ。 */
data class WidgetConfig(
    val widgetId: Int,
    val pages: List<PageCfg> = listOf(PageCfg()),
    val current: Int = 0,
    val style: WidgetStyle = WidgetStyle(),
) {
    /** 現在のページ番号（0始まり、範囲内に丸める） */
    val cur: Int get() = current.coerceIn(0, pages.size - 1)
    val page: PageCfg get() = pages[cur]
    val fileUri: String? get() = page.fileUri
    val manualName: Boolean get() = page.manualName
    val cachedText: String get() = page.cachedText

    fun updatePage(idx: Int = cur, f: (PageCfg) -> PageCfg): WidgetConfig {
        if (idx !in pages.indices) return this
        return copy(pages = pages.toMutableList().also { it[idx] = f(it[idx]) })
    }

    fun withPageCount(n: Int): WidgetConfig {
        val c = n.coerceIn(1, MAX_PAGES)
        val np = if (c <= pages.size) pages.take(c) else pages + List(c - pages.size) { i -> PageCfg(bgColor = PAGE_COLORS[(pages.size + i) % PAGE_COLORS.size]) }
        return copy(pages = np, current = current.coerceIn(0, c - 1))
    }

    /** そのページで実際に使う見た目（ページ別の背景色があれば上書き） */
    fun styleFor(idx: Int = cur): WidgetStyle {
        val b = pages.getOrNull(idx)?.bgColor
        return if (b != null) style.copy(bgColor = b) else style
    }

    fun toJson(): JSONObject = JSONObject()
        .put("pages", org.json.JSONArray(pages.map { it.toJson() }))
        .put("current", cur)
        .put("style", style.toJson())

    companion object {
        fun fromJson(id: Int, o: JSONObject): WidgetConfig {
            val arr = o.optJSONArray("pages")
            val pages: List<PageCfg> = if (arr != null && arr.length() > 0) {
                (0 until minOf(arr.length(), MAX_PAGES)).mapNotNull { arr.optJSONObject(it)?.let { j -> PageCfg.fromJson(j) } }
                    .ifEmpty { listOf(PageCfg()) }
            } else {
                // 旧形式（1ページのみ）からの読み替え
                listOf(PageCfg.fromJson(o))
            }
            return WidgetConfig(
                widgetId = id,
                pages = pages,
                current = o.optInt("current", 0).coerceIn(0, pages.size - 1),
                style = WidgetStyle.fromJson(o.optJSONObject("style")),
            )
        }
    }
}
