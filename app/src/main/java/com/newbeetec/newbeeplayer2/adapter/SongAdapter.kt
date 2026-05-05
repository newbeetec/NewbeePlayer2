package com.newbeetec.newbeeplayer2.adapter

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.newbeetec.newbeeplayer2.R
import com.newbeetec.newbeeplayer2.databinding.ItemSongBinding
import com.newbeetec.newbeeplayer2.model.Song

class SongAdapter(
    private var songs: MutableList<Song>,
    private val onItemClick: (Int) -> Unit,
    private val onDragListener: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<SongAdapter.ViewHolder>() {

    var currentPlayingPosition = -1
        private set

    inner class ViewHolder(val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        holder.binding.title.text = song.title
        holder.binding.artist.text = song.artist

        // 高亮当前播放曲目
        if (position == currentPlayingPosition) {
            holder.binding.root.setCardBackgroundColor(
                holder.itemView.context.resources.getColor(R.color.blue, null)
            )
            holder.binding.title.setTextColor(
                holder.itemView.context.getColor(android.R.color.white)
            )
            holder.binding.artist.setTextColor(
                holder.itemView.context.getColor(android.R.color.white)
            )
        } else {
            holder.binding.root.setCardBackgroundColor(
                holder.itemView.context.getColor(android.R.color.transparent)
            )
            holder.binding.title.setTextColor(
                holder.itemView.context.getColor(android.R.color.black)
            )
            holder.binding.artist.setTextColor(
                holder.itemView.context.getColor(android.R.color.darker_gray)
            )
        }

        holder.binding.root.setOnClickListener { onItemClick(position) }
        holder.binding.dragHandle.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                onDragListener(holder)
            }
            false
        }
    }

    override fun getItemCount(): Int = songs.size

    fun setCurrentPlaying(position: Int) {
        val old = currentPlayingPosition
        if (old != position) {
            currentPlayingPosition = position
            if (old in 0 until itemCount) notifyItemChanged(old)
            if (position in 0 until itemCount) notifyItemChanged(position)
        }
    }
}