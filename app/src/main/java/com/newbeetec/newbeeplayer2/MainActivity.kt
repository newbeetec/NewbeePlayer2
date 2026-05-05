package com.newbeetec.newbeeplayer2.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.newbeetec.newbeeplayer2.R
import com.newbeetec.newbeeplayer2.adapter.SongAdapter
import com.newbeetec.newbeeplayer2.databinding.ActivityMainBinding
import com.newbeetec.newbeeplayer2.manager.PlaylistManager
import com.newbeetec.newbeeplayer2.model.Song
import com.newbeetec.newbeeplayer2.service.MusicService
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var playlistManager: PlaylistManager
    private var musicService: MusicService? = null
    private var bound = false
    private lateinit var adapter: SongAdapter
    private lateinit var audioManager: AudioManager

    private val playStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updatePlayPauseIcon()
            updateCurrentPlayingIndex()
        }
    }

    // 进度更新相关
    private var progressHandler = Handler(Looper.getMainLooper())
    private var isProgressTracking = false

    private val playbackErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val songTitle = intent.getStringExtra(MusicService.EXTRA_SONG_TITLE) ?: "未知歌曲"
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("播放失败")
                .setMessage("无法播放歌曲「$songTitle」，可能文件已失效。")
                .setPositiveButton("确定", null)
                .show()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            musicService = (service as MusicService.LocalBinder).getService()
            bound = true
            updatePlayModeIcon()
            updatePlayPauseIcon()
            binding.speakerSwitch.isChecked = musicService?.getSpeakerMode() ?: true
            updateVolumeSeekBar()
            updateCurrentPlayingIndex()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            musicService = null
            bound = false
        }
    }

    private val openAudioFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            // ★ 获取持久读取权限
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
            val song = uriToSong(uri)
            if (song != null) playlistManager.addSong(song)
        }
        adapter.notifyDataSetChanged()
    }

    // 权限请求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 即使拒绝也不影响基本功能 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playlistManager = PlaylistManager.getInstance(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 请求权限
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permissionsToRequest.add(android.Manifest.permission.READ_PHONE_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }

        setupRecyclerView()
        setupControls()
        bindService(Intent(this, MusicService::class.java), connection, Context.BIND_AUTO_CREATE)

        updateVolumeSeekBar()
        startProgressUpdate()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            playbackErrorReceiver,
            IntentFilter(MusicService.ACTION_PLAYBACK_ERROR)
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            playStateReceiver,
            IntentFilter(MusicService.ACTION_PLAY_STATE_CHANGED)
        )
    }

    override fun onResume() {
        super.onResume()
        updateVolumeSeekBar()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            musicService?.pause()
            updatePlayPauseIcon()
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                updateVolumeSeekBar()
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    private fun setupRecyclerView() {
        adapter = SongAdapter(
            playlistManager.playlist,
            onItemClick = { index ->
                musicService?.play(index)
                updatePlayPauseIcon()
                updateCurrentPlayingIndex()
            },
            onDragListener = { viewHolder -> itemTouchHelper.startDrag(viewHolder) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition

                // 只让 PlaylistManager 执行移动，避免重复修改数据
                playlistManager.moveSong(from, to)
                // 通知服务更新内部播放索引
                musicService?.updateIndexAfterMove(from, to)
                // 仅刷新界面
                adapter.notifyItemMoved(from, to)
                // 刷新高亮
                adapter.setCurrentPlaying(musicService?.getCurrentIndex() ?: -1)
                return true
            }

            override fun onSwiped(
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                direction: Int
            ) {
                val pos = viewHolder.bindingAdapterPosition
                // 先更新服务索引（因为需要根据当前状态判断）
                musicService?.updateIndexAfterRemove(pos)
                // 再删除数据
                playlistManager.removeSong(pos)
                // 通知适配器刷新
                adapter.notifyItemRemoved(pos)
                // 刷新高亮
                adapter.setCurrentPlaying(musicService?.getCurrentIndex() ?: -1)
            }

            override fun isLongPressDragEnabled() = false
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(binding.recyclerView)
    }

    private fun updateCurrentPlayingIndex() {
        val index = musicService?.getCurrentIndex() ?: -1
        adapter.setCurrentPlaying(index)
    }

    private lateinit var itemTouchHelper: ItemTouchHelper

    private fun setupControls() {
        binding.addButton.setOnClickListener {
            openAudioFiles.launch(arrayOf("audio/*"))
        }

        // 播放/暂停按钮
        binding.playPauseButton.setOnClickListener {
            musicService?.let {
                if (it.isPlaying()) it.pause() else it.resume()
                updatePlayPauseIcon()
            }
        }

        binding.nextButton.setOnClickListener {
            musicService?.playNext()
            updatePlayPauseIcon()
        }
        binding.previousButton.setOnClickListener {
            musicService?.playPrevious()
            updatePlayPauseIcon()
        }

        binding.speakerSwitch.setOnCheckedChangeListener { _, isChecked ->
            musicService?.setSpeakerMode(isChecked)
            updateVolumeSeekBar()
        }

        binding.playModeButton.setOnClickListener {
            musicService?.let { service ->
                val newMode = when (service.getPlayMode()) {
                    PlaylistManager.PlayMode.ORDER -> PlaylistManager.PlayMode.SINGLE_LOOP
                    PlaylistManager.PlayMode.SINGLE_LOOP -> PlaylistManager.PlayMode.RANDOM
                    PlaylistManager.PlayMode.RANDOM -> PlaylistManager.PlayMode.ORDER
                }
                service.setPlayMode(newMode)
                updatePlayModeIcon()
            }
        }

        // 音量滑块
        binding.volumeSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    if(musicService?.getSpeakerMode() == true){
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                    }else{
                        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, progress, 0)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // 进度条
        binding.progressSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.currentTimeText.text = formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                isProgressTracking = true
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                isProgressTracking = false
                seekBar?.let {
                    musicService?.seekTo(it.progress)
                }
            }
        })
    }

    private fun updatePlayModeIcon() {
        val mode = musicService?.getPlayMode() ?: PlaylistManager.PlayMode.ORDER
        binding.playModeButton.setImageResource(
            when (mode) {
                PlaylistManager.PlayMode.ORDER -> R.drawable.ic_repeat_order
                PlaylistManager.PlayMode.SINGLE_LOOP -> R.drawable.ic_repeat_one
                PlaylistManager.PlayMode.RANDOM -> R.drawable.ic_shuffle
            }
        )
    }

    private fun updatePlayPauseIcon() {
        val playing = musicService?.isPlaying() ?: false
        binding.playPauseButton.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun updateVolumeSeekBar() {
        if(musicService?.getSpeakerMode() == true) {
            binding.volumeSeekBar.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            binding.volumeSeekBar.min = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            binding.volumeSeekBar.progress = currentVolume
        }else{
            binding.volumeSeekBar.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            binding.volumeSeekBar.min = audioManager.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            binding.volumeSeekBar.progress = currentVolume
        }

    }

    // 进度更新 Runnable
    private val progressRunnable = object : Runnable {
        override fun run() {
            musicService?.let { service ->
                if (service.isPlaying() && !isProgressTracking) {
                    val pos = service.getCurrentPosition()
                    val dur = service.getDuration()
                    if (dur > 0) {
                        binding.progressSeekBar.max = dur
                        binding.progressSeekBar.progress = pos
                        binding.currentTimeText.text = formatTime(pos)
                        binding.totalTimeText.text = formatTime(dur)
                    }
                }
            }
            progressHandler.postDelayed(this, 500)
        }
    }

    private fun formatTime(millis: Int): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun startProgressUpdate() {
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressUpdate() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    private fun uriToSong(uri: Uri): Song? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val title = if (nameIndex >= 0) it.getString(nameIndex) else "未知文件"
                Song(uri.toString(), title)
            } else null
        }
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(playStateReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(playbackErrorReceiver)
        super.onDestroy()
        if (bound) unbindService(connection)
        stopProgressUpdate()
    }
}