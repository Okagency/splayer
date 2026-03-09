package com.splayer.video.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.splayer.video.data.model.Video
import com.splayer.video.data.repository.PlaybackRepository
import com.splayer.video.data.repository.VideoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository
) : ViewModel() {

    private val _currentVideo = MutableStateFlow<Video?>(null)
    val currentVideo: StateFlow<Video?> = _currentVideo.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    fun loadVideo(videoId: Long) {
        viewModelScope.launch {
            try {
                val video = videoRepository.getVideoById(videoId)
                _currentVideo.value = video

                // 이전 재생 위치 불러오기
                val savedPosition = playbackRepository.getPlaybackPosition(videoId)
                _playbackPosition.value = savedPosition?.position ?: 0L
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun savePlaybackPosition(videoId: Long, position: Long, duration: Long) {
        viewModelScope.launch {
            try {
                // 재생이 90% 이상 진행되었으면 처음부터 다시 시작하도록 위치를 0으로 저장
                val positionToSave = if (position > duration * 0.9) 0L else position
                playbackRepository.savePlaybackPosition(videoId, positionToSave, duration)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setPlaybackPosition(position: Long) {
        _playbackPosition.value = position
    }

    fun setPlayingState(playing: Boolean) {
        _isPlaying.value = playing
    }
}

class PlayerViewModelFactory(
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
            return PlayerViewModel(videoRepository, playbackRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
