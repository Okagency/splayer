package com.splayer.video.ui.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.splayer.video.R

data class MergeItem(
    val uri: Uri,
    val displayName: String,
    val duration: Long
)

class VideoMergeAdapter(
    private val items: MutableList<MergeItem>,
    private val onMoveUp: (Int) -> Unit,
    private val onMoveDown: (Int) -> Unit,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<VideoMergeAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val orderNumber: TextView = itemView.findViewById(R.id.mergeOrderNumber)
        val thumbnail: ImageView = itemView.findViewById(R.id.mergeThumbnail)
        val fileName: TextView = itemView.findViewById(R.id.mergeFileName)
        val duration: TextView = itemView.findViewById(R.id.mergeDuration)
        val btnMoveUp: ImageButton = itemView.findViewById(R.id.btnMergeUp)
        val btnMoveDown: ImageButton = itemView.findViewById(R.id.btnMergeDown)
        val btnRemove: ImageButton = itemView.findViewById(R.id.btnMergeRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_merge_video, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.orderNumber.text = (position + 1).toString()
        holder.fileName.text = item.displayName
        holder.duration.text = formatDuration(item.duration)

        Glide.with(holder.thumbnail.context)
            .load(item.uri)
            .override(160, 90)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .centerCrop()
            .into(holder.thumbnail)

        holder.btnMoveUp.isEnabled = position > 0
        holder.btnMoveUp.alpha = if (position > 0) 1.0f else 0.3f
        holder.btnMoveUp.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos > 0 && pos != RecyclerView.NO_POSITION) {
                onMoveUp(pos)
            }
        }

        holder.btnMoveDown.isEnabled = position < items.size - 1
        holder.btnMoveDown.alpha = if (position < items.size - 1) 1.0f else 0.3f
        holder.btnMoveDown.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos < items.size - 1 && pos != RecyclerView.NO_POSITION) {
                onMoveDown(pos)
            }
        }

        holder.btnRemove.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onRemove(pos)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }
}
