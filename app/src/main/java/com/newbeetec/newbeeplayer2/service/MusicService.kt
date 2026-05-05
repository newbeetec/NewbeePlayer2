package com.newbeetec.newbeeplayer2.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.newbeetec.newbeeplayer2.manager.PlaylistManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo

class MusicService : Service() {
    private val binder = LocalBinder()
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var playlistManager: PlaylistManager
    private lateinit var audioManager: AudioManager
    private var currentIndex = -1
    private var isSpeakerOn = true // 将在 onCreate 中覆盖
    private var playMode: PlaylistManager.PlayMode = PlaylistManager.PlayMode.ORDER
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var isApplyingAudioOutput = false   // 防止循环触发

    companion object {
        const val ACTION_PLAYBACK_ERROR = "com.newbeetec.newbeeplayer2.PLAYBACK_ERROR"
        const val EXTRA_SONG_TITLE = "extra_song_title"
        const val ACTION_PLAY_STATE_CHANGED = "com.newbeetec.newbeeplayer2.PLAY_STATE_CHANGED"
    }

    private fun sendPlayStateChanged() {
        val intent = Intent(ACTION_PLAY_STATE_CHANGED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // 音频焦点
    private var audioFocusRequest: AudioFocusRequest? = null

    // 电话状态监听
    private lateinit var telephonyManager: TelephonyManager
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING,
                TelephonyManager.CALL_STATE_OFFHOOK -> pause()
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            checkAndForceEarpiece()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            checkAndForceEarpiece()
        }
    }

    private fun checkAndForceEarpiece() {
        // 只在听筒模式且未被自身修改时检查
        if (isSpeakerOn || isApplyingAudioOutput) return

        // 获取当前输出设备
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var hasSpeaker = false
        var hasEarpiece = false

        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> hasSpeaker = true
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> hasEarpiece = true
            }
        }

        // 如果激活的设备是扬声器而不是听筒，说明被其他应用切换，强制恢复
        if (hasSpeaker && !hasEarpiece) {
            // 先暂时注销回调，防止递归
            unregisterAudioDeviceCallback()
            // 重新应用听筒设置
            applyAudioOutput()
            // 重新注册回调
            registerAudioDeviceCallback()
        }
    }

    private fun registerAudioDeviceCallback() {
        if (audioDeviceCallback == null) {
            audioDeviceCallback = deviceCallback
            audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        }
    }

    private fun unregisterAudioDeviceCallback() {
        audioDeviceCallback?.let {
            audioManager.unregisterAudioDeviceCallback(it)
            audioDeviceCallback = null
        }
    }

    private fun updateScreenWakeLock() {
        val shouldKeepScreenOn = mediaPlayer?.isPlaying == true
        if (shouldKeepScreenOn) {
            if (screenWakeLock == null) {
                screenWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "NewbeePlayer:screen"
                )
            }
            if (screenWakeLock?.isHeld == false) {
                screenWakeLock?.acquire(10 * 60 * 1000L) // 最长10分钟
            }
        } else {
            screenWakeLock?.let {
                if (it.isHeld) it.release()
            }
            screenWakeLock = null
        }
    }

    // 接近传感器与WakeLock（仅听筒模式且播放时激活）
    private lateinit var powerManager: PowerManager
    private var proximityWakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        playlistManager = PlaylistManager.getInstance(this)
        playMode = playlistManager.playMode
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        startForegroundWithInitialNotification()
        // 恢复上次的输出模式
        isSpeakerOn = playlistManager.speakerMode
        applyAudioOutput()        // ★ 关键：立即应用到系统音频
    }

    private fun startForegroundWithInitialNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "music_channel", "音乐播放", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, "music_channel")
            .setContentTitle("NewbeePlayer2")
            .setContentText("准备播放")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(1, notification)
    }

    override fun onBind(intent: Intent): IBinder = binder

    fun setPlayMode(mode: PlaylistManager.PlayMode) {
        playMode = mode
        playlistManager.playMode = mode
    }

    private fun sendPlaybackError(songTitle: String) {
        val intent = Intent(ACTION_PLAYBACK_ERROR).apply {
            putExtra(EXTRA_SONG_TITLE, songTitle)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    fun getPlayMode(): PlaylistManager.PlayMode = playMode

    // 播放指定索引的歌曲
    fun play(index: Int) {
        val playlist = playlistManager.playlist
        if (playlist.isEmpty()) return
        if (index !in playlist.indices) return

        currentIndex = index
        releasePlayer()
        applyAudioOutput()
        val song = playlist[index]
        requestAudioFocus()
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(if(isSpeakerOn)(AudioAttributes.USAGE_MEDIA)else(AudioAttributes.USAGE_VOICE_COMMUNICATION))
                        .build()
                )
                // ★ 在这里进行异常捕获
                try {
                    setDataSource(this@MusicService, Uri.parse(song.uri))
                } catch (e: Exception) {
                    e.printStackTrace()
                    sendPlaybackError(song.title)
                    releasePlayer()
                    playNext()
                    return
                }
                setOnPreparedListener { mp ->
                    mp.start()
                    updateScreenWakeLock()
                    sendPlayStateChanged()   // 添加这一行
                    updateNotification()
                    updateProximityWakeLock()
                }
                setOnErrorListener { _, _, _ ->
                    sendPlaybackError(song.title)   // 新增这一行
                    releasePlayer()
                    true
                }
                setOnCompletionListener { onSongComplete() }
                prepareAsync()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sendPlaybackError(song.title)   // 新增
            releasePlayer()
        }
    }

    private fun onSongComplete() {
        when (playMode) {
            PlaylistManager.PlayMode.ORDER -> playNext()
            PlaylistManager.PlayMode.SINGLE_LOOP -> {
                mediaPlayer?.seekTo(0)
                mediaPlayer?.start()
                sendPlayStateChanged()   // 单曲循环重新开始也发送
            }

            PlaylistManager.PlayMode.RANDOM -> playRandom()
        }
    }

    fun playNext() {
        val playlist = playlistManager.playlist
        if (playlist.isEmpty()) return
        if (playlist.size == 1 && isPlaying()) return  // 单曲且正在播放，忽略下一曲
        val nextIndex = (currentIndex + 1) % playlist.size
        play(nextIndex)
    }

    fun playPrevious() {
        val playlist = playlistManager.playlist
        if (playlist.isEmpty()) return
        if (playlist.size == 1 && isPlaying()) return  // 单曲且正在播放，忽略上一曲
        val prevIndex = if (currentIndex <= 0) playlist.size - 1 else currentIndex - 1
        play(prevIndex)
    }

    private fun playRandom() {
        val playlist = playlistManager.playlist
        if (playlist.isEmpty()) return

        // 只有一首歌，直接从头播放
        if (playlist.size == 1) {
            mediaPlayer?.seekTo(0)
            mediaPlayer?.start()
            sendPlayStateChanged()
            return
        }

        // 生成与当前索引不同的随机索引
        var newIndex: Int
        do {
            newIndex = playlist.indices.random()
        } while (newIndex == currentIndex)

        currentIndex = newIndex
        play(currentIndex)
    }

    fun pause() {
        if(mediaPlayer == null){
            play(currentIndex)
            return
        }
        mediaPlayer?.pause()
        updateScreenWakeLock()
        sendPlayStateChanged()   // 添加
        updateProximityWakeLock()
    }

    fun resume() {
        if(mediaPlayer == null){
            play(currentIndex)
            return
        }
        mediaPlayer?.start()
        updateScreenWakeLock()
        sendPlayStateChanged()   // 添加
        updateProximityWakeLock()
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false
    fun getCurrentIndex(): Int = currentIndex
    fun getDuration(): Int = mediaPlayer?.duration ?: 0
    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0
    fun seekTo(msec: Int) = mediaPlayer?.seekTo(msec)

    fun setSpeakerMode(speaker: Boolean) {
        if (isSpeakerOn != speaker) {
            if (isPlaying()) {
                pause()                     // ★ 强制暂停
            }
            isSpeakerOn = speaker
            playlistManager.speakerMode = speaker   // ★ 持久化
            applyAudioOutput()
            updateProximityWakeLock()
            releasePlayer()
        }
    }

    fun updateIndexAfterMove(from: Int, to: Int) {
        if (currentIndex == from) {
            currentIndex = to
        } else if (currentIndex in minOf(from, to)..maxOf(from, to)) {
            if (from < to) currentIndex-- else currentIndex++
        }
    }

    fun updateIndexAfterRemove(position: Int) {
        when {
            position < currentIndex -> currentIndex--
            position == currentIndex -> {
                releasePlayer()
                currentIndex = -1
                updateNotification()
            }
        }
    }

    fun getSpeakerMode(): Boolean = isSpeakerOn

    private fun applyAudioOutput() {
        isApplyingAudioOutput = true
        if (isSpeakerOn) {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = true
            unregisterAudioDeviceCallback()
        } else {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
            registerAudioDeviceCallback()
        }
        isApplyingAudioOutput = false
    }

    // 接近传感器控制屏幕（听筒靠近黑屏）
    private fun updateProximityWakeLock() {
        if (!isSpeakerOn && mediaPlayer?.isPlaying == true) {
            if (proximityWakeLock == null) {
                proximityWakeLock = powerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "NewbeePlayer:proximity"
                )
            }
            if (proximityWakeLock?.isHeld == false) {
                proximityWakeLock?.acquire(10 * 60 * 1000L /* 10min */)
            }
        } else {
            proximityWakeLock?.let {
                if (it.isHeld) it.release()
            }
            proximityWakeLock = null
        }
    }

    // 音频焦点请求
    private fun requestAudioFocus() {
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        pause() // 简化：直接暂停
                    }

                    AudioManager.AUDIOFOCUS_GAIN -> {
                        applyAudioOutput()   // 重新获得焦点时强制恢复输出模式
                    }
                }
            }
            .build()
        audioFocusRequest = focusRequest
        audioManager.requestAudioFocus(focusRequest)
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    // 通知（前台服务必需）
    private fun updateNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "music_channel", "音乐播放", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val song = playlistManager.playlist.getOrNull(currentIndex)
        val notification = NotificationCompat.Builder(this, "music_channel")
            .setContentTitle(song?.title ?: "未在播放")
            .setContentText(song?.artist ?: "未知艺术家")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(1, notification)
    }

    private fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        abandonAudioFocus()
        updateProximityWakeLock()
        updateScreenWakeLock()
    }

    override fun onDestroy() {
        releasePlayer()
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        screenWakeLock?.let { if (it.isHeld) it.release() }
        unregisterAudioDeviceCallback()
        super.onDestroy()
    }
}