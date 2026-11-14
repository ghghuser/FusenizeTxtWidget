package com.ghghuser.fusenizetxt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ghghuser.fusenizetxt.data.Insets
import com.ghghuser.fusenizetxt.data.MAX_PAGES
import com.ghghuser.fusenizetxt.data.WidgetStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StylePanel(
    style: WidgetStyle,
    pageCount: Int,
    pageNo: Int,
    onPageCount: (Int) -> Unit,
    onChange: (WidgetStyle) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (pageNo + 1 < MAX_PAGES) {
            SliderRow("ページ数（2以上で最下段にページ切替）", pageCount, pageNo + 1, MAX_PAGES) { onPageCount(it) }
        } else {
            Text("ページ数: $MAX_PAGES", style = MaterialTheme.typography.labelMedium)
        }
        HorizontalDivider()
        ColorPickerRow(
            if (pageCount > 1) "背景色（このページ ${pageNo + 1}）" else "背景色",
            style.bgColor, BG_PRESETS,
        ) { onChange(style.copy(bgColor = it)) }
        SliderRow("背景の透過度（255=不透明、初期値200）", style.bgAlpha, 0, 255) { onChange(style.copy(bgAlpha = it)) }
        HorizontalDivider()
        Text("文字色", style = MaterialTheme.typography.labelLarge)
        FilterChip(
            selected = style.autoText,
            onClick = { onChange(style.copy(autoText = !style.autoText)) },
            label = { Text("背景に合わせて自動で決める") },
        )
        if (!style.autoText) {
            ColorPickerRow("文字色", style.textColor, TEXT_PRESETS) { onChange(style.copy(textColor = it)) }
        }
        SliderRow("ウィジェットの文字サイズ (sp)", style.textSizeSp, 12, 32) { onChange(style.copy(textSizeSp = it)) }
        SliderRow("編集画面の文字サイズ (sp)", style.editSizeSp, 12, 32) { onChange(style.copy(editSizeSp = it)) }
        FilterChip(
            selected = style.showTitle,
            onClick = { onChange(style.copy(showTitle = !style.showTitle)) },
            label = { Text("ウィジェット上部にタイトルを表示") },
        )
        if (style.showTitle) {
            SliderRow("タイトルの文字サイズ (sp)", style.titleSizeSp, 8, 32) { onChange(style.copy(titleSizeSp = it)) }
        }
        if (pageCount > 1) {
            SliderRow("ページ切替バーの高さ (dp)", style.tabHeightDp, 14, 48) { onChange(style.copy(tabHeightDp = it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = style.bold, onClick = { onChange(style.copy(bold = !style.bold)) }, label = { Text("太字") })
            FilterChip(selected = style.paper, onClick = { onChange(style.copy(paper = !style.paper)) }, label = { Text("背景の質感") })
            FilterChip(selected = style.italic, onClick = { onChange(style.copy(italic = !style.italic)) }, label = { Text("斜体") })
        }
        if (style.paper) {
            SliderRow("質感の強さ（ノイズ・グラデーション）", style.paperStrength, 0, 100) { onChange(style.copy(paperStrength = it)) }
        }
        HorizontalDivider()
        Text("水平配置", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("左", "中央", "右").forEachIndexed { i, l ->
                FilterChip(selected = style.hAlign == i, onClick = { onChange(style.copy(hAlign = i)) }, label = { Text(l) })
            }
        }
        Text("垂直配置", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("上", "中央", "下").forEachIndexed { i, l ->
                FilterChip(selected = style.vAlign == i, onClick = { onChange(style.copy(vAlign = i)) }, label = { Text(l) })
            }
        }
        HorizontalDivider()
        Text("外側の余白（枠と背景の間・透明）dp", style = MaterialTheme.typography.labelLarge)
        InsetSliders(style.outer) { onChange(style.copy(outer = it)) }
        HorizontalDivider()
        Text("内側の余白（背景と文字の間）dp", style = MaterialTheme.typography.labelLarge)
        InsetSliders(style.inner) { onChange(style.copy(inner = it)) }
    }
}

@Composable
private fun InsetSliders(v: Insets, onChange: (Insets) -> Unit) {
    SliderRow("上", v.t, 0, 48) { onChange(v.copy(t = it)) }
    SliderRow("下", v.b, 0, 48) { onChange(v.copy(b = it)) }
    SliderRow("左", v.l, 0, 48) { onChange(v.copy(l = it)) }
    SliderRow("右", v.r, 0, 48) { onChange(v.copy(r = it)) }
}
