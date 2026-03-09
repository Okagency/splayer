package com.splayer.video.data.repository

import com.splayer.video.data.local.dao.PlaybackPositionDao
import com.splayer.video.data.local.entity.PlaybackPosition
import kotlinx.coroutines.flow.Flow

class PlaybackRepository(private val dao: PlaybackPositionDao) {

    suspend fun savePlaybackPosition(
        videoId: Long,
        position: Long,
        duration: Long
    ) {
        dao.savePosition(
            PlaybackPosition(
                videoId = videoId,
                position = position,
                duration = duration,
                lastPlayed = System.currentTimeMillis()
            )
        )
    }

    suspend fun getPlaybackPosition(videoId: Long): PlaybackPosition? {
        return dao.getPosition(videoId)
    }

    fun getPlaybackPositionFlow(videoId: Long): Flow<PlaybackPosition?> {
        return dao.getPositionFlow(videoId)
    }

    suspend fun deletePlaybackPosition(videoId: Long) {
        dao.deletePosition(videoId)
    }

    fun getRecentlyPlayed(limit: Int = 20): Flow<List<PlaybackPosition>> {
        return dao.getRecentlyPlayed(limit)
    }
}
