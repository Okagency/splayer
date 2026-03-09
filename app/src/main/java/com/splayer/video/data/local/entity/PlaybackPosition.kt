package com.splayer.video.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_positions")
data class PlaybackPosition(
    @PrimaryKey
    val videoId: Long,
    val position: Long,
    val duration: Long,
    val lastPlayed: Long
)
