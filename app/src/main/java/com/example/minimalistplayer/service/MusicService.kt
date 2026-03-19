package com.example.minimalistplayer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.minimalistplayer.R
import com.example.minimalistplayer.data.Track
import com.example.minimalistplayer.ui.main.MainActivity
import com.example.minimalistplayer.ui.nowplaying.NowPlayingActivity
import java.io.IOException
import kotlin.random.Random
import android.content.SharedPreferences
import androidx.core.content.edit

class MusicService : Service() {

    companion object {
        private const val TAG = "MusicService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "music_channel"
    }

    // Enum для режимов повтора
    enum class RepeatMode {
        NONE,       // без повтора
        ALL,        // повтор всех
        ONE         // повтор одного
    }

    // Интерфейс для слушателей изменений
    interface OnTrackChangeListener {
        fun onTrackChanged(track: Track)
        fun onPlayStateChanged(isPlaying: Boolean)
    }

    // Binder для связи с Activity
    private val binder = MusicBinder()

    // MediaPlayer для воспроизведения
    private var mediaPlayer: MediaPlayer? = null

    // Текущий трек
    private var currentTrack: Track? = null

    // Список треков для плеера
    private var playlist: List<Track> = emptyList()
    private var currentPosition = 0

    // Состояние воспроизведения
    var isPlaying = false
        private set

    var isPrepared = false
        private set

    // Режимы воспроизведения
    var repeatMode: RepeatMode = RepeatMode.NONE
        private set

    var shuffleMode = false
        private set

    // Слушатели
    private var onPreparedListener: (() -> Unit)? = null
    private var onCompletionListener: (() -> Unit)? = null
    private var onErrorListener: ((String) -> Unit)? = null
    private var trackChangeListener: OnTrackChangeListener? = null

    // AudioManager для аудиофокуса
    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mediaPlayer?.setVolume(0.3f, 0.3f)
            AudioManager.AUDIOFOCUS_GAIN -> mediaPlayer?.setVolume(1.0f, 1.0f)
        }
    }

    // Receiver для отключения наушников
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.d(TAG, "Наушники отключены - ставим на паузу")
                pause()
            }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isServiceInitialized = false

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    // Добавьте переменные:
    private lateinit var prefs: SharedPreferences
    private val PREFS_NAME = "music_player_prefs"
    private val KEY_LAST_TRACK_ID = "last_track_id"
    private val KEY_LAST_POSITION = "last_position"
    private val KEY_WAS_PLAYING = "was_playing"

    // В onCreate():
    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.d(TAG, "MusicService создан")
        initMediaPlayer()
        createNotificationChannel()
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    // Сохранение состояния
// Добавим ключи для сохранения
    private val KEY_REPEAT_MODE = "repeat_mode"
    private val KEY_SHUFFLE_MODE = "shuffle_mode"

    // В метод savePlaybackState() добавим сохранение режимов
    fun savePlaybackState() {
        prefs.edit {
            putLong(KEY_LAST_TRACK_ID, currentTrack?.id ?: -1)
            putInt(KEY_LAST_POSITION, getCurrentPosition())
            putBoolean(KEY_WAS_PLAYING, isPlaying)
            putInt(KEY_REPEAT_MODE, repeatMode.ordinal)  // Сохраняем repeatMode
            putBoolean(KEY_SHUFFLE_MODE, shuffleMode)     // Сохраняем shuffleMode
        }
        Log.d(TAG, "Saved: track=${currentTrack?.id}, pos=${getCurrentPosition()}, " +
                "playing=$isPlaying, repeat=$repeatMode, shuffle=$shuffleMode")
    }

    // В метод loadPlaybackState() добавим загрузку режимов
    fun loadPlaybackState(): PlaybackState {
        val trackId = prefs.getLong(KEY_LAST_TRACK_ID, -1)
        val position = prefs.getInt(KEY_LAST_POSITION, 0)
        val wasPlaying = prefs.getBoolean(KEY_WAS_PLAYING, false)

        // Загружаем режимы
        val repeatOrdinal = prefs.getInt(KEY_REPEAT_MODE, RepeatMode.NONE.ordinal)
        val shuffle = prefs.getBoolean(KEY_SHUFFLE_MODE, false)

        // Преобразуем ordinal обратно в enum
        val repeat = when(repeatOrdinal) {
            0 -> RepeatMode.NONE
            1 -> RepeatMode.ALL
            2 -> RepeatMode.ONE
            else -> RepeatMode.NONE
        }

        return PlaybackState(trackId, position, wasPlaying, repeat, shuffle)
    }

    // Создадим data class для состояния
    data class PlaybackState(
        val trackId: Long,
        val position: Int,
        val wasPlaying: Boolean,
        val repeatMode: RepeatMode,
        val shuffleMode: Boolean
    )

    // Обновим restorePlaybackState
    fun restorePlaybackState(tracks: List<Track>) {
        if (isServiceInitialized && playlist.isNotEmpty()) {
            Log.d(TAG, "Service already initialized, skipping restore")
            return
        }

        val state = loadPlaybackState()

        // Восстанавливаем режимы
        setRepeatMode(state.repeatMode)
        setShuffleMode(state.shuffleMode)

        if (state.trackId != -1L) {
            val index = tracks.indexOfFirst { it.id == state.trackId }
            if (index != -1) {
                setPlaylist(tracks, index)

                handler.postDelayed({
                    seekTo(state.position)

                    if (state.wasPlaying) {
                        play()
                    }
                    Log.d(TAG, "Restored: track=${currentTrack?.title}, pos=${state.position}, " +
                            "playing=${state.wasPlaying}, repeat=${state.repeatMode}, shuffle=${state.shuffleMode}")
                }, 500)
            } else {
                setPlaylist(tracks, 0)
            }
        } else {
            setPlaylist(tracks, 0)
        }
    }

    private fun initMediaPlayer() {
        mediaPlayer = MediaPlayer().apply {
            setOnPreparedListener {
                this@MusicService.isPrepared = true
                Log.d(TAG, "MediaPlayer подготовлен")
                onPreparedListener?.invoke()
                showNotification()
            }

            setOnCompletionListener {
                Log.d(TAG, "Трек завершен")
                this@MusicService.isPlaying = false
                trackChangeListener?.onPlayStateChanged(false)
                onCompletionListener?.invoke()
                playNext()
            }

            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Ошибка MediaPlayer: what=$what, extra=$extra")
                onErrorListener?.invoke("Ошибка воспроизведения: $what")
                true
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Музыкальный плеер",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Управление воспроизведением музыки"
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification() {
        val track = currentTrack ?: return

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nowPlayingIntent = Intent(this, NowPlayingActivity::class.java).apply {
            putExtra("track_id", track.id)
            putExtra("track_title", track.title)
            putExtra("track_artist", track.artist)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val nowPlayingPendingIntent = PendingIntent.getActivity(
            this, 1, nowPlayingIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = Intent(this, MusicService::class.java).apply {
            action = "PLAY_PAUSE"
        }
        val playPausePendingIntent = PendingIntent.getService(
            this, 2, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = Intent(this, MusicService::class.java).apply {
            action = "NEXT"
        }
        val nextPendingIntent = PendingIntent.getService(
            this, 3, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = Intent(this, MusicService::class.java).apply {
            action = "PREV"
        }
        val prevPendingIntent = PendingIntent.getService(
            this, 4, prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setSmallIcon(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            .setContentIntent(nowPlayingPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .addAction(R.drawable.ic_prev, "Предыдущий", prevPendingIntent)
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) "Пауза" else "Старт",
                playPausePendingIntent
            )
            .addAction(R.drawable.ic_next, "Следующий", nextPendingIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2))
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun requestAudioFocus(): Boolean {
        val result = audioManager.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        audioManager.abandonAudioFocus(audioFocusChangeListener)
    }

    fun setOnTrackChangeListener(listener: OnTrackChangeListener) {
        trackChangeListener = listener
    }

    // Изменим setPlaylist
    fun setPlaylist(tracks: List<Track>, startPosition: Int = 0) {
        // Если сервис уже инициализирован и плейлист не пустой, не сбрасываем
        if (isServiceInitialized && playlist.isNotEmpty()) {
            Log.d(TAG, "Service already initialized, keeping current playlist")
            return
        }

        playlist = tracks
        currentPosition = startPosition.coerceIn(0, tracks.size - 1)
        if (playlist.isNotEmpty()) {
            currentTrack = playlist[currentPosition]
            prepareTrack(currentTrack!!)
            isServiceInitialized = true
        }
        Log.d(TAG, "Playlist set with ${tracks.size} tracks at position $startPosition")
    }

    // Добавим метод для проверки инициализации
    fun isInitialized(): Boolean = isServiceInitialized && playlist.isNotEmpty()

    private fun prepareTrack(track: Track) {
        try {
            mediaPlayer?.reset()
            mediaPlayer?.setDataSource(track.path)
            mediaPlayer?.prepareAsync()
            currentTrack = track
            isPrepared = false
            isPlaying = false
            Log.d(TAG, "Подготовка трека: ${track.title}")

            // Уведомляем о смене трека
            trackChangeListener?.onTrackChanged(track)
            trackChangeListener?.onPlayStateChanged(false)
        } catch (e: IOException) {
            Log.e(TAG, "Ошибка установки источника: ${e.message}")
            onErrorListener?.invoke("Не удалось загрузить файл: ${track.title}")
        }
    }

    fun play() {
        if (requestAudioFocus()) {
            if (isPrepared) {
                mediaPlayer?.start()
                isPlaying = true
                showNotification()
                Log.d(TAG, "Воспроизведение начато")
                trackChangeListener?.onPlayStateChanged(true)
            } else if (currentTrack != null) {
                onPreparedListener = {
                    mediaPlayer?.start()
                    isPlaying = true
                    showNotification()
                    trackChangeListener?.onPlayStateChanged(true)
                }
            }
        }
    }

    fun pause() {
        if (isPlaying) {
            mediaPlayer?.pause()
            isPlaying = false
            abandonAudioFocus()
            showNotification()
            Log.d(TAG, "Воспроизведение приостановлено")
            trackChangeListener?.onPlayStateChanged(false)
        }
    }

    fun stop() {
        mediaPlayer?.stop()
        isPlaying = false
        isPrepared = false
        abandonAudioFocus()
        stopForeground(false)
        Log.d(TAG, "Воспроизведение остановлено")
        trackChangeListener?.onPlayStateChanged(false)
    }

    fun seekTo(position: Int) {
        mediaPlayer?.seekTo(position)
    }

    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0

    fun getDuration(): Int = mediaPlayer?.duration ?: 0

    fun playNext() {
        if (playlist.isEmpty()) return

        when (repeatMode) {
            RepeatMode.ONE -> {
                prepareTrack(currentTrack!!)
                onPreparedListener = { play() }
            }
            RepeatMode.ALL -> {
                if (shuffleMode) {
                    var newPosition = Random.nextInt(playlist.size)
                    while (newPosition == currentPosition && playlist.size > 1) {
                        newPosition = Random.nextInt(playlist.size)
                    }
                    currentPosition = newPosition
                } else {
                    currentPosition = (currentPosition + 1) % playlist.size
                }
                currentTrack = playlist[currentPosition]
                prepareTrack(currentTrack!!)
                onPreparedListener = { play() }
            }
            RepeatMode.NONE -> {
                if (shuffleMode) {
                    if (playlist.size > 1) {
                        var newPosition = Random.nextInt(playlist.size)
                        while (newPosition == currentPosition) {
                            newPosition = Random.nextInt(playlist.size)
                        }
                        currentPosition = newPosition
                        currentTrack = playlist[currentPosition]
                        prepareTrack(currentTrack!!)
                        onPreparedListener = { play() }
                    }
                } else {
                    if (currentPosition < playlist.size - 1) {
                        currentPosition++
                        currentTrack = playlist[currentPosition]
                        prepareTrack(currentTrack!!)
                        onPreparedListener = { play() }
                    } else {
                        pause()
                    }
                }
            }
        }
    }

    fun playPrevious() {
        if (playlist.isEmpty()) return

        when (repeatMode) {
            RepeatMode.ONE -> {
                prepareTrack(currentTrack!!)
                onPreparedListener = { play() }
            }
            else -> {
                if (shuffleMode) {
                    var newPosition = Random.nextInt(playlist.size)
                    while (newPosition == currentPosition && playlist.size > 1) {
                        newPosition = Random.nextInt(playlist.size)
                    }
                    currentPosition = newPosition
                } else {
                    currentPosition = if (currentPosition > 0) {
                        currentPosition - 1
                    } else {
                        playlist.size - 1
                    }
                }
                currentTrack = playlist[currentPosition]
                prepareTrack(currentTrack!!)
                onPreparedListener = { play() }
            }
        }
    }

    fun getCurrentTrack(): Track? = currentTrack

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        Log.d(TAG, "Repeat mode: $mode")
    }

    fun setShuffleMode(enabled: Boolean) {
        shuffleMode = enabled
        Log.d(TAG, "Shuffle mode: $enabled")
    }

    fun setOnPreparedListener(listener: () -> Unit) {
        onPreparedListener = listener
    }

    fun setOnCompletionListener(listener: () -> Unit) {
        onCompletionListener = listener
    }

    fun setOnErrorListener(listener: (String) -> Unit) {
        onErrorListener = listener
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "MusicService получена команда: ${intent?.action}")

        when (intent?.action) {
            "PLAY_PAUSE" -> if (isPlaying) pause() else play()
            "NEXT" -> playNext()
            "PREV" -> playPrevious()
            "PAUSE" -> pause()
            "STOP" -> stop()
            else -> {
                // При обычном запуске без команды ничего не делаем
                Log.d(TAG, "Service started without command")
            }
        }

        return START_STICKY
    }

    // Добавьте в начало класса MusicService
    private var savedTrackId: Long = -1
    private var savedPosition: Int = 0

    // Метод для сохранения текущего трека
    fun saveCurrentTrack() {
        savedTrackId = currentTrack?.id ?: -1
        savedPosition = currentPosition
        Log.d(TAG, "Saved track: $savedTrackId at position $savedPosition")
    }

    // Метод для получения сохраненного трека
    fun getSavedTrackId(): Long = savedTrackId
    fun getSavedPosition(): Int = savedPosition

    // Метод для восстановления трека
    fun restoreTrackIfNeeded(tracks: List<Track>) {
        if (savedTrackId != -1L && playlist.isEmpty()) {
            val index = tracks.indexOfFirst { it.id == savedTrackId }
            if (index != -1) {
                setPlaylist(tracks, index)
                Log.d(TAG, "Restored track at position $index")
            } else {
                setPlaylist(tracks, 0)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (isPlaying) {
            showNotification()
        } else {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        unregisterReceiver(noisyReceiver)
        abandonAudioFocus()
        Log.d(TAG, "MusicService уничтожен")
    }
}