package com.newbeetec.newbeeplayer2.manager

import android.content.Context
import android.content.SharedPreferences
import com.newbeetec.newbeeplayer2.model.Song
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PlaylistManager private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("playlist_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    var playlist: MutableList<Song> = loadPlaylist()
        private set

    enum class PlayMode { ORDER, SINGLE_LOOP, RANDOM }

    var playMode: PlayMode
        get() = PlayMode.valueOf(prefs.getString("play_mode", PlayMode.ORDER.name)!!)
        set(mode) = prefs.edit().putString("play_mode", mode.name).apply()

    fun addSong(song: Song) {
        playlist.add(song)
        savePlaylist()
    }

    fun addSongs(songs: List<Song>) {
        playlist.addAll(songs)
        savePlaylist()
    }

    fun removeSong(position: Int) {
        if (position in 0 until playlist.size) {
            playlist.removeAt(position)
            savePlaylist()
        }
    }

    fun moveSong(from: Int, to: Int) {
        if (from in 0 until playlist.size && to in 0 until playlist.size) {
            val song = playlist.removeAt(from)
            playlist.add(to, song)
            savePlaylist()
        }
    }

    private fun savePlaylist() {
        prefs.edit().putString("playlist", gson.toJson(playlist)).apply()
    }

    private fun loadPlaylist(): MutableList<Song> {
        val json = prefs.getString("playlist", null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<List<Song>>() {}.type
            gson.fromJson<List<Song>>(json, type).toMutableList()
        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
    }

    companion object {
        @Volatile private var instance: PlaylistManager? = null
        fun getInstance(context: Context): PlaylistManager {
            return instance ?: synchronized(this) {
                instance ?: PlaylistManager(context.applicationContext).also { instance = it }
            }
        }
    }
}