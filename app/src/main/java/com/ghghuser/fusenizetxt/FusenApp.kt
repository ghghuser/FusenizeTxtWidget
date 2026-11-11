package com.ghghuser.fusenizetxt

import android.app.Application
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
    }
}
