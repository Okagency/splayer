package com.splayer.video.utils

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val LOG_FILE_NAME = "splayer_crash_log.txt"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5MB

    fun init(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logCrash(context, thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Log.d(TAG, "CrashLogger initialized")
    }

    private fun logCrash(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val logFile = getLogFile(context)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())

            val crashReport = buildString {
                appendLine("=" .repeat(80))
                appendLine("CRASH REPORT - $timestamp")
                appendLine("=" .repeat(80))
                appendLine("Thread: ${thread.name}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("App Version: 1.0.0")
                appendLine()
                appendLine("Exception:")
                appendLine(throwable.toString())
                appendLine()
                appendLine("Stack Trace:")
                appendLine(getStackTraceString(throwable))
                appendLine("=" .repeat(80))
                appendLine()
            }

            logFile.appendText(crashReport)
            Log.e(TAG, "Crash logged to file: ${logFile.absolutePath}")

            // 파일 크기 제한
            if (logFile.length() > MAX_LOG_SIZE) {
                rotateLogFile(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log crash", e)
        }
    }

    fun logError(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        try {
            val logFile = getLogFile(context)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())

            val errorReport = buildString {
                appendLine()
                appendLine("-" .repeat(80))
                appendLine("ERROR - $timestamp")
                appendLine("Tag: $tag")
                appendLine("Message: $message")
                if (throwable != null) {
                    appendLine("Exception: ${throwable.message}")
                    appendLine("Stack Trace:")
                    appendLine(getStackTraceString(throwable))
                }
                appendLine("-" .repeat(80))
            }

            logFile.appendText(errorReport)
            Log.e(tag, message, throwable)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log error", e)
        }
    }

    fun logInfo(context: Context, tag: String, message: String) {
        try {
            val logFile = getLogFile(context)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())

            logFile.appendText("[$timestamp] INFO/$tag: $message\n")
            Log.d(tag, message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log info", e)
        }
    }

    private fun getLogFile(context: Context): File {
        val logDir = File(context.getExternalFilesDir(null), "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        return File(logDir, LOG_FILE_NAME)
    }

    private fun rotateLogFile(context: Context) {
        try {
            val logFile = getLogFile(context)
            val backupFile = File(logFile.parent, "splayer_crash_log_old.txt")

            if (backupFile.exists()) {
                backupFile.delete()
            }

            logFile.renameTo(backupFile)
            Log.d(TAG, "Log file rotated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log file", e)
        }
    }

    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }

    fun getLogFilePath(context: Context): String {
        return getLogFile(context).absolutePath
    }

    fun clearLogs(context: Context) {
        try {
            val logFile = getLogFile(context)
            if (logFile.exists()) {
                logFile.delete()
            }
            Log.d(TAG, "Logs cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
        }
    }
}
