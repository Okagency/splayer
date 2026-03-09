package com.splayer.video.data.local.dao

import androidx.room.*
import com.splayer.video.data.local.entity.PlaybackPosition
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackPositionDao {

    @Query("SELECT * FROM playback_positions WHERE videoId = :videoId")
    suspend fun getPosition(videoId: Long): PlaybackPosition?

    @Query("SELECT * FROM playback_positions WHERE videoId = :videoId")
    fun getPositionFlow(videoId: Long): Flow<PlaybackPosition?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePosition(position: PlaybackPosition)

    @Query("DELETE FROM playback_positions WHERE videoId = :videoId")
    suspend fun deletePosition(videoId: Long)

    @Query("SELECT * FROM playback_positions ORDER BY lastPlayed DESC LIMIT :limit")
    fun getRecentlyPlayed(limit: Int = 20): Flow<List<PlaybackPosition>>
}
