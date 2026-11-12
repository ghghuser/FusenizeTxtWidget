package com.ghghuser.fusenizetxt.data

import android.app.Activity
import android.view.WindowManager

/** アプリの画面を開いている間だけ、指定した明るさに固定する（システム設定は変更しない） */
object Brightness {
    fun apply(act: Activity) {
        val s = Store(act)
        val lp = act.window.attributes
        lp.screenBrightness = if (s.brightnessLock) s.brightnessLevel.coerceIn(5, 100) / 100f
        else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        act.window.attributes = lp
    }
}
