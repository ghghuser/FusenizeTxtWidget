package com.ghghuser.fusenizetxt.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.ghghuser.fusenizetxt.R

/** 紙のような質感（ごく薄い斜めグラデーション＋細かなノイズ）。strength は 0〜100。背景の上・文字の下に描く */
fun Modifier.paperTexture(strength: Int, shape: Shape = RectangleShape): Modifier = composed {
    if (strength <= 0) {
        this
    } else {
        val ctx = LocalContext.current
        val noise = remember {
            try {
                BitmapFactory.decodeResource(ctx.resources, R.drawable.paper_noise)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
        val a = (strength / 100f).coerceIn(0f, 1f)
        this.drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(
                outline,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.18f), Color.Black.copy(alpha = 0.2f)),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
                alpha = a,
            )
            if (noise != null) {
                drawOutline(outline, ShaderBrush(ImageShader(noise, TileMode.Repeated, TileMode.Repeated)), alpha = a)
            }
        }
    }
}
