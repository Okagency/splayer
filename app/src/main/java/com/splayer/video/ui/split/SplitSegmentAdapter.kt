package com.splayer.video.ui.split

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.splayer.video.R

class SplitSegmentAdapter(
    private val segments: MutableList<VideoSplitActivity.SplitSegment>,
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<SplitSegmentAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.splitSegmentNumber)
        val time: TextView = view.findViewById(R.id.splitSegmentTime)
        val keyframe: TextView = view.findViewById(R.id.splitSegmentKeyframe)
        val btnEdit: ImageButton = view.findViewById(R.id.btnSplitEdit)
        val btnDelete: ImageButton = view.findViewById(R.id.btnSplitDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_split_segment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val seg = segments[position]
        holder.number.text = (position + 1).toString()
        holder.time.text = "${formatTime(seg.fromMs)} ~ ${formatTime(seg.toMs)}"
        holder.keyframe.text = "KF #${seg.fromKfIndex} ~ #${seg.toKfIndex}"
        holder.btnEdit.setOnClickListener { onEdit(holder.adapterPosition) }
        holder.btnDelete.setOnClickListener { onDelete(holder.adapterPosition) }
    }

    override fun getItemCount() = segments.size

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
