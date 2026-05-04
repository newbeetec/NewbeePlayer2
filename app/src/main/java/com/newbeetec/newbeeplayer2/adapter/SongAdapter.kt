package com.newbeetec.newbeeplayer2.adapter

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.newbeetec.newbeeplayer2.databinding.ItemSongBinding
import com.newbeetec.newbeeplayer2.model.Song
import java.util.Collections

class SongAdapter(
    private var songs: MutableList<Song>,
    private val onItemClick: (Int) -> Unit,
    private val onDragListener: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<SongAdapter.ViewHolder>() {

    // 当前播放的索引，-1 表示无
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
            // 示例：橙色背景 + 白色文字
            holder.binding.root.setCardBackgroundColor(
                holder.itemView.context.getColor(android.R.color.holo_blue_light)
            )
            holder.binding.title.setTextColor(
                holder.itemView.context.getColor(android.R.color.white)
            )
            holder.binding.artist.setTextColor(
                holder.itemView.context.getColor(android.R.color.white)
            )
        } else {
            // 恢复默认
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

    /**
     * 设置当前正在播放的索引，并刷新旧/新位置
     */
    fun setCurrentPlaying(position: Int) {
        val old = currentPlayingPosition
        if (old != position) {
            currentPlayingPosition = position
            if (old in 0 until itemCount) notifyItemChanged(old)
            if (position in 0 until itemCount) notifyItemChanged(position)
        }
    }

    /**
     * 拖拽移动
     */
    fun moveItem(from: Int, to: Int) {
        if (from < songs.size && to < songs.size) {
            Collections.swap(songs, from, to)
            notifyItemMoved(from, to)
            // 如果移动涉及当前播放项，更新高亮索引
            if (from == currentPlayingPosition || to == currentPlayingPosition) {
                val newPos = if (from == currentPlayingPosition) to else from
                currentPlayingPosition = newPos
            }
        }
    }

    /**
     * 移除某项（右滑删除或外部调用）
     */
    fun removeItem(position: Int) {
        if (position in 0 until songs.size) {
            songs.removeAt(position)
            notifyItemRemoved(position)
            // 调整当前播放索引
            when {
                position < currentPlayingPosition -> currentPlayingPosition--
                position == currentPlayingPosition -> currentPlayingPosition = -1
            }
        }
    }
}