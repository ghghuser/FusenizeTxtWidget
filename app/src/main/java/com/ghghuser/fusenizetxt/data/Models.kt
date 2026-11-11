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
    val bgColor: Int = 0xFFF59D,
    val bgAlpha: Int = 200,
    val autoText: Boolean = true, // 背景色に合わせて文字色を自動で決める
    val textColor: Int = 0x212121,
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

/** ウィジェット1つ分の設定（ウィジェットID単位） */
data class WidgetConfig(
    val widgetId: Int,
    val fileUri: String? = null,
    val manualName: Boolean = false,
    val style: WidgetStyle = WidgetStyle(),
    val cachedText: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("uri", fileUri ?: JSONObject.NULL)
        .put("manual", manualName)
        .put("style", style.toJson())
        .put("cache", cachedText)

    companion object {
        fun fromJson(id: Int, o: JSONObject): WidgetConfig = WidgetConfig(
            widgetId = id,
            fileUri = if (o.isNull("uri")) null else o.optString("uri").ifEmpty { null },
            manualName = o.optBoolean("manual", false),
            style = WidgetStyle.fromJson(o.optJSONObject("style")),
            cachedText = o.optString("cache", ""),
        )
    }
}
