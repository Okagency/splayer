package com.splayer.video.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.splayer.video.R
import com.splayer.video.data.model.Video
import com.splayer.video.databinding.ItemVideoBinding

class VideoAdapter(
    private val onVideoClick: (Video) -> Unit,
    private val onVideoLongClick: (Video) -> Unit = {}
) : ListAdapter<Video, VideoAdapter.VideoViewHolder>(VideoDiffCallback()) {

    private val selectedIds = mutableSetOf<Long>()
    var isSelectionMode = false
        private set

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VideoViewHolder(binding, onVideoClick, onVideoLongClick)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(getItem(position), isSelectionMode, selectedIds.contains(getItem(position).id))
    }

    fun toggleSelection(videoId: Long) {
        if (selectedIds.contains(videoId)) {
            selectedIds.remove(videoId)
        } else {
            selectedIds.add(videoId)
        }
        if (selectedIds.isEmpty()) {
            isSelectionMode = false
        }
        notifyDataSetChanged()
    }

    fun enterSelectionMode(videoId: Long) {
        isSelectionMode = true
        selectedIds.add(videoId)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedIds.clear()
        isSelectionMode = false
        notifyDataSetChanged()
    }

    fun getSelectedCount(): Int = selectedIds.size

    fun getSelectedVideos(): List<Video> {
        return currentList.filter { selectedIds.contains(it.id) }
    }

    class VideoViewHolder(
        private val binding: ItemVideoBinding,
        private val onVideoClick: (Video) -> Unit,
        private val onVideoLongClick: (Video) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(video: Video, isSelectionMode: Boolean, isSelected: Boolean) {
            binding.apply {
                videoName.text = video.displayName
                videoInfo.text = "${video.getResolution()}  ·  ${video.getFormattedSize()}"
                videoPath.text = video.path
                durationOverlay.text = video.getFormattedDuration()

                // 파일 경로 기반으로 썸네일 로드 (MKV 등 모든 포맷 지원)
                Glide.with(thumbnail.context)
                    .asBitmap()
                    .load(video.path)
                    .apply(RequestOptions()
                        .override(260, 148)
                        .frame(1000000) // 1초 지점 프레임
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .error(R.color.thumbnail_background)
                    )
                    .into(thumbnail)

                // 체크 오버레이
                checkOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE

                // 선택 모드에서 미선택 항목은 반투명
                root.alpha = if (isSelectionMode && !isSelected) 0.5f else 1.0f

                root.setOnClickListener {
                    onVideoClick(video)
                }
                root.setOnLongClickListener {
                    onVideoLongClick(video)
                    true
                }
            }
        }
    }

    private class VideoDiffCallback : DiffUtil.ItemCallback<Video>() {
        override fun areItemsTheSame(oldItem: Video, newItem: Video): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Video, newItem: Video): Boolean {
            return oldItem == newItem
        }
    }
}
