package com.ghghuser.fusenizetxt

import android.app.Application
import android.content.Context
import androidx.glance.appwidget.updateAll
import com.ghghuser.fusenizetxt.widget.FusenWidget
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex

class FusenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            com.ghghuser.fusenizetxt.data.Backup.schedule(this, replace = false)
        } catch (e: Exception) {
            // 定時バックアップの登録に失敗しても本体の動作には影響させない
        }
    }

    companion object {
        /** Activityが閉じても保存処理を最後まで走らせるためのスコープ */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** ファイル書き込み・リネームを直列化するロック */
        val saveMutex = Mutex()

        /** ウィジェットの再描画。完了を待たずに実行し、保存や画面の終了を妨げない */
        fun refreshWidgets(ctx: Context) {
            val app = ctx.applicationContext
            scope.launch {
                try {
                    withTimeout(15_000) { FusenWidget().updateAll(app) }
                } catch (e: Exception) {
                    // 再描画に失敗しても保存済みの内容には影響しない
                }
            }
        }
    }
}
