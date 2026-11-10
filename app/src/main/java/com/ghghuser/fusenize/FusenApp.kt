package com.ghghuser.fusenize

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex

class FusenApp : Application() {
    companion object {
        /** Activityが閉じても保存処理を最後まで走らせるためのスコープ */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** ファイル書き込み・リネームを直列化するロック */
        val saveMutex = Mutex()
    }
}
