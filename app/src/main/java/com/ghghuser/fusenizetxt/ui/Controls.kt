package com.ghghuser.fusenizetxt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ghghuser.fusenizetxt.data.rgbToArgb
import kotlin.math.roundToInt

@Composable
fun SliderRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text("$value", style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.toFloat().coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(),
        )
    }
}

/** プリセット + 自由入力（#RRGGBB / RGBスライダー）による色選択 */
@Composable
fun ColorPickerRow(label: String, rgb: Int, presets: List<Int>, onChange: (Int) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    var hex by remember(rgb) { mutableStateOf("%06X".format(rgb and 0xFFFFFF)) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            presets.forEach { p ->
                val selected = (p and 0xFFFFFF) == (rgb and 0xFFFFFF)
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .background(Color(rgbToArgb(p)))
                        .border(
                            if (selected) 3.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        )
                        .clickable { onChange(p) }
                )
            }
            TextButton(onClick = { custom = !custom }) { Text(if (custom) "閉じる" else "自由に選ぶ") }
        }
        if (custom) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(Color(rgbToArgb(rgb)))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                )
                OutlinedTextField(
                    value = hex,
                    onValueChange = { v ->
                        val t = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                        hex = t
                        if (t.length == 6) onChange(t.toInt(16))
                    },
                    prefix = { Text("#") },
                    label = { Text("RRGGBB") },
                    singleLine = true,
                    modifier = Modifier.width(150.dp),
                )
            }
            SliderRow("R", (rgb shr 16) and 0xFF, 0, 255) { onChange((rgb and 0x00FFFF) or (it shl 16)) }
            SliderRow("G", (rgb shr 8) and 0xFF, 0, 255) { onChange((rgb and 0xFF00FF) or (it shl 8)) }
            SliderRow("B", rgb and 0xFF, 0, 255) { onChange((rgb and 0xFFFF00) or it) }
        }
    }
}

val BG_PRESETS = listOf(0x1B2A5E, 0xFFF59D, 0xFFCCBC, 0xC8E6C9, 0xBBDEFB, 0xE1BEE7, 0xFFFFFF, 0x424242, 0x000000)
val TEXT_PRESETS = listOf(0x212121, 0xFFFFFF, 0xB71C1C, 0x0D47A1, 0x1B5E20, 0x4E342E)
