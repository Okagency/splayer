package com.splayer.video

import android.app.Application
import android.util.Log
import com.splayer.video.utils.CrashLogger

class SPlayerApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 크래시 로거 초기화
        CrashLogger.init(this)

        val logPath = CrashLogger.getLogFilePath(this)
        Log.d("SPlayerApplication", "App started - Log file: $logPath")
        CrashLogger.logInfo(this, "SPlayerApplication", "==================== APP STARTED ====================")
        CrashLogger.logInfo(this, "SPlayerApplication", "Log file location: $logPath")
    }
}
