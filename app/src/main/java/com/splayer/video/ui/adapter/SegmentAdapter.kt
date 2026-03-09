package com.splayer.video.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.splayer.video.R
import com.splayer.video.data.model.PlaybackSegment

class SegmentAdapter(
    private var segments: MutableList<PlaybackSegment>,
    private val onMoveUp: (Int) -> Unit,
    private val onMoveDown: (Int) -> Unit,
    private val onEdit: (Int) -> Unit,
    private val onSave: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<SegmentAdapter.SegmentViewHolder>() {

    class SegmentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val segmentNumber: TextView = itemView.findViewById(R.id.segmentNumber)
        val segmentTime: TextView = itemView.findViewById(R.id.segmentTime)
        val segmentDuration: TextView = itemView.findViewById(R.id.segmentDuration)
        val btnMoveUp: ImageButton = itemView.findViewById(R.id.btnMoveUp)
        val btnMoveDown: ImageButton = itemView.findViewById(R.id.btnMoveDown)
        val btnEdit: ImageButton = itemView.findViewById(R.id.btnEdit)
        val btnSave: ImageButton = itemView.findViewById(R.id.btnSave)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SegmentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_segment, parent, false)
        return SegmentViewHolder(view)
    }

    override fun onBindViewHolder(holder: SegmentViewHolder, position: Int) {
        val segment = segments[position]

        holder.segmentNumber.text = segment.sequence.toString()
        holder.segmentTime.text = "${formatTime(segment.startTime)} - ${formatTime(segment.endTime)}"

        val duration = segment.endTime - segment.startTime
        holder.segmentDuration.text = "길이: ${formatTime(duration)}"

        // 번호와 시간 영역 클릭 시 해당 구간 재생
        holder.segmentNumber.setOnClickListener {
            val currentPosition = holder.adapterPosition
            if (currentPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                onItemClick(currentPosition)
            }
        }
        holder.segmentTime.setOnClickListener {
            val currentPosition = holder.adapterPosition
            if (currentPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                onItemClick(currentPosition)
            }
        }
        holder.segmentDuration.setOnClickListener {
            val currentPosition = holder.adapterPosition
            if (currentPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                onItemClick(currentPosition)
            }
        }

        // 위로 이동 버튼
        holder.btnMoveUp.isEnabled = position > 0
        holder.btnMoveUp.alpha = if (position > 0) 1.0f else 0.3f
        holder.btnMoveUp.setOnClickListener {
            val currentPosition = holder.adapterPosition
            if (currentPosition > 0 && currentPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                onMoveUp(currentPosition)
            }
        }

        // 아래로 이동 버튼
        holder.btnMoveDown.isEnabled = position < segments.size - 1
        holder.btnMoveDown.alpha = if (position < segments.size - 1) 1.0f else 0.3f
        holder.btnMoveDown.setOnClickListener {
            val currentPosition = holder.adapterPosition
            if (currentPosition < segments.size - 1 && currentPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                onMoveDown(currentPosition)
            }
        }

        // 수정 버튼
        holder.btnEdit.setOnClickListener {
            val currentPosition = holder.adapterPosition
            if (currentPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                onEdit(currentPosition)
            }
        }

        // 저장 버튼
        holder.btnSave.setOnClickListener {
            val currentPosition = holder.adapterPosition
            if (currentPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                onSave(currentPosition)
            }
        }

        // 삭제 버튼
        holder.btnDelete.setOnClickListener {
            val currentPosition = holder.adapterPosition
            if (currentPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                onDelete(currentPosition)
            }
        }
    }

    override fun getItemCount(): Int = segments.size

    fun updateSegments(newSegments: List<PlaybackSegment>) {
        segments.clear()
        segments.addAll(newSegments)
        notifyDataSetChanged()
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
