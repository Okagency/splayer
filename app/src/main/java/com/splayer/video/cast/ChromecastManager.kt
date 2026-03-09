package com.splayer.video.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.*

class ChromecastManager(private val context: Context) {

    companion object {
        private const val TAG = "ChromecastManager"
        private const val POSITION_POLL_INTERVAL_MS = 1000L
    }

    private var castContext: CastContext? = null
    private var sessionManager: SessionManager? = null
    private var castSession: CastSession? = null
    private var mediaServer: MediaHttpServer? = null
    private var positionPollingJob: Job? = null

    var isCasting: Boolean = false
        private set

    var onPositionUpdate: ((positionMs: Long, durationMs: Long) -> Unit)? = null
    var onCastEnded: (() -> Unit)? = null

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.d(TAG, "세션 시작: ${session.castDevice?.friendlyName}")
            castSession = session
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            Log.d(TAG, "세션 종료")
            castSession = null
            if (isCasting) {
                isCasting = false
                positionPollingJob?.cancel()
                mediaServer?.stop()
                mediaServer = null
                onCastEnded?.invoke()
            }
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            Log.d(TAG, "세션 재개: ${session.castDevice?.friendlyName}")
            castSession = session
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.e(TAG, "세션 시작 실패: $error")
        }

        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
        override fun onSessionStarting(session: CastSession) {}
    }

    fun initialize(): Boolean {
        return try {
            castContext = CastContext.getSharedInstance(context)
            sessionManager = castContext?.sessionManager
            sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
            // 기존 활성 세션이 있으면 가져오기
            val existingSession = sessionManager?.currentCastSession
            if (existingSession?.isConnected == true) {
                Log.d(TAG, "기존 세션 발견: ${existingSession.castDevice?.friendlyName}")
                castSession = existingSession
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Cast 초기화 실패 (Play Services 없음?)", e)
            false
        }
    }

    fun getDeviceName(): String? {
        return castSession?.castDevice?.friendlyName
    }

    fun isConnected(): Boolean {
        return castSession?.isConnected == true
    }

    /**
     * 캐스팅 시작 — 이미 Cast 세션이 연결된 상태에서 호출
     * @param subtitleVtt 자막 내용 (WebVTT 형식), null이면 자막 없음
     */
    suspend fun startCasting(
        videoPath: String,
        title: String,
        mimeType: String,
        startPositionMs: Long = 0,
        subtitleVtt: String? = null
    ): Boolean = withContext(Dispatchers.Main) {
        val session = castSession ?: return@withContext false
        val client = session.remoteMediaClient ?: return@withContext false

        try {
            // 미디어 서버 시작
            val server = MediaHttpServer(context, 0)
            val uri = Uri.parse(videoPath)

            if (uri.scheme == "content") {
                server.serveContentUri(uri, mimeType)
            } else {
                val filePath = if (uri.scheme == "file") uri.path ?: videoPath else videoPath
                server.serveFile(filePath, mimeType)
            }

            // 자막 설정
            if (subtitleVtt != null) {
                server.setSubtitleData(subtitleVtt)
            }

            server.start()
            mediaServer = server

            val servingUrl = server.getServingUrl()
            Log.d(TAG, "미디어 서빙: $servingUrl")

            // MediaInfo 구성
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, title)
            }

            // 자막 트랙 구성
            val mediaTracks = mutableListOf<MediaTrack>()
            val activeTrackIds = mutableListOf<Long>()
            val subtitleUrl = server.getSubtitleUrl()

            if (subtitleVtt != null && subtitleUrl != null) {
                val subtitleTrack = MediaTrack.Builder(1, MediaTrack.TYPE_TEXT)
                    .setName("자막")
                    .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    .setContentId(subtitleUrl)
                    .setContentType("text/vtt")
                    .setLanguage("ko")
                    .build()
                mediaTracks.add(subtitleTrack)
                activeTrackIds.add(1L)
                Log.d(TAG, "자막 URL: $subtitleUrl")
            }

            val mediaInfoBuilder = MediaInfo.Builder(servingUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(mimeType)
                .setMetadata(metadata)

            if (mediaTracks.isNotEmpty()) {
                mediaInfoBuilder.setMediaTracks(mediaTracks)
            }

            val mediaInfo = mediaInfoBuilder.build()

            val loadRequestBuilder = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setCurrentTime(startPositionMs)
                .setAutoplay(true)

            if (activeTrackIds.isNotEmpty()) {
                loadRequestBuilder.setActiveTrackIds(activeTrackIds.toLongArray())
            }

            client.load(loadRequestBuilder.build())

            isCasting = true
            startPositionPolling(client)
            true
        } catch (e: Exception) {
            Log.e(TAG, "캐스팅 시작 실패", e)
            mediaServer?.stop()
            mediaServer = null
            false
        }
    }

    suspend fun pause() {
        withContext(Dispatchers.Main) {
            castSession?.remoteMediaClient?.pause()
        }
    }

    suspend fun resume() {
        withContext(Dispatchers.Main) {
            castSession?.remoteMediaClient?.play()
        }
    }

    suspend fun stopCasting() {
        positionPollingJob?.cancel()
        positionPollingJob = null

        withContext(Dispatchers.Main) {
            try {
                castSession?.remoteMediaClient?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Stop 실패", e)
            }
        }

        mediaServer?.stop()
        mediaServer = null

        if (isCasting) {
            isCasting = false
            withContext(Dispatchers.Main) { onCastEnded?.invoke() }
        }
    }

    suspend fun seekTo(positionMs: Long) {
        withContext(Dispatchers.Main) {
            castSession?.remoteMediaClient?.seek(positionMs)
        }
    }

    fun isPlaying(): Boolean {
        val client = castSession?.remoteMediaClient ?: return false
        return client.playerState == MediaStatus.PLAYER_STATE_PLAYING
    }

    private fun startPositionPolling(client: RemoteMediaClient) {
        positionPollingJob?.cancel()
        positionPollingJob = CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            while (isActive) {
                try {
                    val position = client.approximateStreamPosition
                    val duration = client.streamDuration
                    if (duration > 0) {
                        onPositionUpdate?.invoke(position, duration)
                    }

                    val state = client.playerState
                    if (state == MediaStatus.PLAYER_STATE_IDLE && client.idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                        stopCasting()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "폴링 실패", e)
                }
                delay(POSITION_POLL_INTERVAL_MS)
            }
        }
    }

    /** 동기 stop — onDestroy에서 메인스레드 직접 호출용 */
    fun stopCastingSync() {
        positionPollingJob?.cancel()
        positionPollingJob = null
        try {
            castSession?.remoteMediaClient?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Stop 실패", e)
        }
        mediaServer?.stop()
        mediaServer = null
        isCasting = false
    }

    fun release() {
        positionPollingJob?.cancel()
        positionPollingJob = null
        mediaServer?.stop()
        mediaServer = null
        try {
            sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        } catch (_: Exception) {}
        // 세션은 종료하지 않음 — 다음 액티비티에서 재사용 가능
        castSession = null
        isCasting = false
    }
}
