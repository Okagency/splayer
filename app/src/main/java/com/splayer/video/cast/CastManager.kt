package com.splayer.video.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*

class CastManager(private val context: Context) {

    companion object {
        private const val TAG = "CastManager"
        private const val POSITION_POLL_INTERVAL_MS = 1000L
    }

    private val discovery = DlnaDiscovery()
    private val controller = DlnaController()
    private var mediaServer: MediaHttpServer? = null

    var isCasting: Boolean = false
        private set
    var currentDevice: DlnaDevice? = null
        private set

    var onPositionUpdate: ((positionMs: Long, durationMs: Long) -> Unit)? = null
    var onPlaybackStateChanged: ((state: String) -> Unit)? = null
    var onCastEnded: (() -> Unit)? = null

    private var positionPollingJob: Job? = null

    suspend fun discoverDevices(): List<DlnaDevice> {
        return discovery.discover()
    }

    suspend fun startCasting(
        videoPath: String,
        title: String,
        device: DlnaDevice,
        startPositionMs: Long = 0
    ): Boolean {
        try {
            stopCasting()

            val mimeType = getMimeType(videoPath)
            val server = MediaHttpServer(context, 0)
            val uri = Uri.parse(videoPath)

            if (uri.scheme == "content") {
                server.serveContentUri(uri, mimeType)
            } else {
                val filePath = if (uri.scheme == "file") uri.path ?: videoPath else videoPath
                server.serveFile(filePath, mimeType)
            }

            server.start()
            mediaServer = server

            val servingUrl = server.getServingUrl()
            Log.d(TAG, "미디어 서빙: $servingUrl")

            val setResult = controller.setAVTransportURI(device.avTransportControlUrl, servingUrl, title, mimeType)
            if (!setResult) {
                Log.e(TAG, "SetAVTransportURI 실패")
                server.stop()
                mediaServer = null
                return false
            }

            delay(500)
            val playResult = controller.play(device.avTransportControlUrl)
            if (!playResult) {
                Log.e(TAG, "Play 실패")
                server.stop()
                mediaServer = null
                return false
            }

            if (startPositionMs > 0) {
                delay(1000)
                controller.seek(device.avTransportControlUrl, startPositionMs)
            }

            currentDevice = device
            isCasting = true
            startPositionPolling(device.avTransportControlUrl)

            return true
        } catch (e: Exception) {
            Log.e(TAG, "캐스팅 시작 실패", e)
            stopCasting()
            return false
        }
    }

    suspend fun pause(): Boolean {
        val device = currentDevice ?: return false
        return controller.pause(device.avTransportControlUrl)
    }

    suspend fun resume(): Boolean {
        val device = currentDevice ?: return false
        return controller.play(device.avTransportControlUrl)
    }

    suspend fun stopCasting() {
        positionPollingJob?.cancel()
        positionPollingJob = null

        currentDevice?.let { device ->
            try {
                controller.stop(device.avTransportControlUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Stop 실패", e)
            }
        }

        mediaServer?.stop()
        mediaServer = null
        currentDevice = null

        if (isCasting) {
            isCasting = false
            withContext(Dispatchers.Main) { onCastEnded?.invoke() }
        }
    }

    suspend fun seekTo(positionMs: Long): Boolean {
        val device = currentDevice ?: return false
        return controller.seek(device.avTransportControlUrl, positionMs)
    }

    private fun startPositionPolling(controlUrl: String) {
        positionPollingJob?.cancel()
        positionPollingJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (isActive) {
                try {
                    controller.getPositionInfo(controlUrl)?.let { (pos, dur) ->
                        withContext(Dispatchers.Main) { onPositionUpdate?.invoke(pos, dur) }
                    }

                    controller.getTransportInfo(controlUrl)?.let { state ->
                        withContext(Dispatchers.Main) {
                            onPlaybackStateChanged?.invoke(state)
                            if (state == "STOPPED" || state == "NO_MEDIA_PRESENT") {
                                stopCasting()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "폴링 실패", e)
                }
                delay(POSITION_POLL_INTERVAL_MS)
            }
        }
    }

    private fun getMimeType(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".wmv") -> "video/x-ms-wmv"
            lower.endsWith(".flv") -> "video/x-flv"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".ts") -> "video/mp2t"
            lower.endsWith(".3gp") -> "video/3gpp"
            lower.endsWith(".mpg") || lower.endsWith(".mpeg") -> "video/mpeg"
            lower.endsWith(".asf") -> "video/x-ms-asf"
            else -> "video/mp4"
        }
    }

    fun release() {
        positionPollingJob?.cancel()
        positionPollingJob = null
        mediaServer?.stop()
        mediaServer = null
        currentDevice = null
        isCasting = false
    }
}
