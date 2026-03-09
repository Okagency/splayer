package com.splayer.video.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.splayer.video.data.model.Video
import com.splayer.video.data.repository.VideoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val videoRepository: VideoRepository
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.DATE_MODIFIED)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    init {
        Log.d(TAG, "MainViewModel initialized")
        loadVideos()
    }

    fun loadVideos() {
        viewModelScope.launch {
            Log.d(TAG, "loadVideos() started")
            _isLoading.value = true
            try {
                val videoList = videoRepository.getAllVideos()
                Log.d(TAG, "Repository returned ${videoList.size} videos")

                if (videoList.isEmpty()) {
                    Log.w(TAG, "⚠️ NO VIDEOS FOUND - Repository returned empty list")
                    Log.w(TAG, "Possible reasons:")
                    Log.w(TAG, "  1. No video files in device storage")
                    Log.w(TAG, "  2. Permission not granted")
                    Log.w(TAG, "  3. MediaStore query failed")
                }

                _videos.value = sortVideos(videoList, _sortMode.value)
                Log.d(TAG, "Videos sorted and emitted: ${_videos.value.size}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ ERROR loading videos", e)
                e.printStackTrace()
                _videos.value = emptyList()
            } finally {
                _isLoading.value = false
                Log.d(TAG, "loadVideos() completed")
            }
        }
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        _videos.value = sortVideos(_videos.value, mode)
    }

    private fun sortVideos(videos: List<Video>, mode: SortMode): List<Video> {
        return when (mode) {
            SortMode.NAME -> videos.sortedBy { it.displayName }
            SortMode.DATE_ADDED -> videos.sortedByDescending { it.dateAdded }
            SortMode.DATE_MODIFIED -> videos.sortedByDescending { it.dateModified }
            SortMode.SIZE -> videos.sortedByDescending { it.size }
            SortMode.DURATION -> videos.sortedByDescending { it.duration }
        }
    }

    enum class SortMode {
        NAME, DATE_ADDED, DATE_MODIFIED, SIZE, DURATION
    }
}

class MainViewModelFactory(
    private val videoRepository: VideoRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(videoRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
