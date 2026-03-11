package com.splayer.video.ui.replace

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.splayer.video.R

data class ReplaceSegment(
    val uri: Uri,
    val displayName: String,
    val fromKfIndex: Int,
    val toKfIndex: Int
)

class ReplaceSegmentAdapter(
    private val segments: MutableList<ReplaceSegment>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<ReplaceSegmentAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.replaceSegmentNumber)
        val name: TextView = view.findViewById(R.id.replaceSegmentName)
        val keyframe: TextView = view.findViewById(R.id.replaceSegmentKeyframe)
        val btnDelete: ImageButton = view.findViewById(R.id.btnReplaceDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_replace_segment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val seg = segments[position]
        holder.number.text = (position + 1).toString()
        holder.name.text = seg.displayName
        holder.keyframe.text = "KF #${seg.fromKfIndex} ~ #${seg.toKfIndex}"
        holder.btnDelete.setOnClickListener { onDelete(holder.adapterPosition) }
    }

    override fun getItemCount() = segments.size
}
