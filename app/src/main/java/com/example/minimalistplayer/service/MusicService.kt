package com.example.minimalistplayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.minimalistplayer.R
import com.example.minimalistplayer.data.Track
import com.example.minimalistplayer.ui.main.MainActivity
import com.example.minimalistplayer.ui.nowplaying.NowPlayingActivity
import java.io.IOException
import kotlin.math.abs
import kotlin.random.Random

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

    // Интерфейс для слушателей
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

    // Режимы воспроизведения - ОБЪЯВЛЯЕМ КАК СВОЙСТВА
    var repeatMode: RepeatMode = RepeatMode.NONE
        private set

    var shuffleMode = false
        private set

    // Слушатели
    private var onPreparedListener: (() -> Unit)? = null
    private var onCompletionListener: (() -> Unit)? = null
    private var onErrorListener: ((String) -> Unit)? = null
    private var trackChangeListener: OnTrackChangeListener? = null
    private var visualizer: Visualizer? = null
    private var isVisualizerEnabled = false

    // AudioManager для аудиофокуса
    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Audio focus loss")
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus loss transient")
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus loss transient can duck")
                mediaPlayer?.setVolume(0.3f, 0.3f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gain")
                mediaPlayer?.setVolume(1.0f, 1.0f)
            }
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

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MusicService создан")
        initMediaPlayer()
        createNotificationChannel()

        registerReceiver(
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        )
    }

    // В методе initMediaPlayer():
    private fun initMediaPlayer() {
        mediaPlayer = MediaPlayer().apply {
            // Явно указываем создать новую аудиосессию
            setAudioSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)

            setOnPreparedListener {
                this@MusicService.isPrepared = true
                Log.d(TAG, "MediaPlayer prepared, audio session: ${this.audioSessionId}")
                onPreparedListener?.invoke()
                showNotification()
            }

            setOnCompletionListener {
                Log.d(TAG, "Track completed, audio session: ${this.audioSessionId}")
                this@MusicService.isPlaying = false
                trackChangeListener?.onPlayStateChanged(false)
                onCompletionListener?.invoke()
                playNext()
            }

            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Error MediaPlayer: what=$what, extra=$extra")
                onErrorListener?.invoke("Ошибка воспроизведения: $what")
                true
            }
        }
    }

    // Метод getAudioSessionId():
    fun getAudioSessionId(): Int {
        return mediaPlayer?.audioSessionId ?: 0
    }

    // И добавим метод для принудительного создания аудиосессии
    private fun ensureAudioSession() {
        if (mediaPlayer == null) return

        try {
            // Если сессия не создана, создаем новую
            if (mediaPlayer?.audioSessionId == 0) {
                mediaPlayer?.setAudioSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                Log.d(TAG, "Generated new audio session: ${mediaPlayer?.audioSessionId}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring audio session", e)
        }
    }

    // Добавим интерфейс для передачи данных
    interface VisualizerDataListener {
        fun onWaveformData(waveform: ByteArray)
    }

    private var visualizerListener: VisualizerDataListener? = null

    fun setVisualizerListener(listener: VisualizerDataListener) {
        visualizerListener = listener
        setupVisualizer()
    }

    fun enableVisualizer(enable: Boolean) {
        isVisualizerEnabled = enable
        if (enable) {
            startVisualizer()
        } else {
            stopVisualizer()
        }
    }

    private fun startVisualizer() {
        try {
            val sessionId = mediaPlayer?.audioSessionId ?: return
            if (sessionId == 0) return

            stopVisualizer() // Останавливаем предыдущий

            visualizer = Visualizer(sessionId)
            visualizer?.captureSize = Visualizer.getCaptureSizeRange()[1]

            visualizer?.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (isVisualizerEnabled && waveform != null) {
                            visualizerListener?.onWaveformData(waveform)
                        }
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {}
                },
                Visualizer.getMaxCaptureRate() / 2,
                true,
                false
            )

            visualizer?.enabled = true
            Log.d(TAG, "Visualizer started for session: $sessionId")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting visualizer", e)
        }
    }

    private fun stopVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping visualizer", e)
        }
    }

    // В методе prepareTrack() временно отключаем визуалайзер
    private fun prepareTrack(track: Track) {
        try {
            // Останавливаем визуалайзер на время подготовки
            val wasVisualizerEnabled = isVisualizerEnabled
            if (wasVisualizerEnabled) {
                stopVisualizer()
            }

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

            // Возвращаем визуалайзер после подготовки
            if (wasVisualizerEnabled) {
                Handler(Looper.getMainLooper()).postDelayed({
                    startVisualizer()
                }, 500)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка подготовки трека", e)
        }
    }

    private fun setupVisualizer() {
        try {
            // Получаем аудиосессию от MediaPlayer
            val audioSessionId = mediaPlayer?.audioSessionId ?: return
            Log.d(TAG, "Setting up visualizer for session: $audioSessionId")

            // Создаем визуализатор
            visualizer = Visualizer(audioSessionId)

            // Устанавливаем размер захвата (максимальный)
            val captureSizeRange = Visualizer.getCaptureSizeRange()
            visualizer?.captureSize = captureSizeRange[1]

            // Устанавливаем слушатель
            visualizer?.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {
                        // Отправляем данные в активность
                        waveform?.let {
                            visualizerListener?.onWaveformData(it)
                        }
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        // Не используем
                    }
                },
                Visualizer.getMaxCaptureRate() / 2, // Обновление 30 раз в секунду
                true,  // Включить waveform
                false  // Отключить FFT
            )

            // Включаем визуализатор
            visualizer?.enabled = true
            Log.d(TAG, "Visualizer enabled")

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up visualizer", e)
        }
    }

    // При смене трека обновляем визуализатор
    private fun updateVisualizer() {
        visualizer?.release()
        setupVisualizer()
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

    private fun updateWidget() {
        val track = currentTrack
        val title = track?.title ?: "Нет трека"
        val artist = track?.artist ?: "Неизвестно"

        val intent = Intent("UPDATE_WIDGET").apply {
            putExtra("title", title)
            putExtra("artist", artist)
            putExtra("isPlaying", isPlaying)
            putExtra("isFavorite", track?.isFavorite ?: false)
        }
        sendBroadcast(intent)
    }

    fun setPlaylist(tracks: List<Track>, startPosition: Int = 0) {
        playlist = tracks
        currentPosition = startPosition.coerceIn(0, tracks.size - 1)
        if (playlist.isNotEmpty()) {
            currentTrack = playlist[currentPosition]
            prepareTrack(currentTrack!!)
        }
        Log.d(TAG, "Playlist set with ${tracks.size} tracks")
    }

    fun getPlaylist(): List<Track> = playlist

    fun playTrackAtPosition(position: Int) {
        if (position in playlist.indices && position != currentPosition) {
            currentPosition = position
            currentTrack = playlist[currentPosition]
            prepareTrack(currentTrack!!)
            onPreparedListener = {
                play()
            }
        } else if (position == currentPosition) {
            play()
        }
    }

    fun play() {
        if (requestAudioFocus()) {
            if (isPrepared) {
                mediaPlayer?.start()
                isPlaying = true
                showNotification()
                updateWidget()
                Log.d(TAG, "Воспроизведение начато")
                trackChangeListener?.onPlayStateChanged(true)
            } else if (currentTrack != null) {
                onPreparedListener = {
                    mediaPlayer?.start()
                    isPlaying = true
                    showNotification()
                    updateWidget()
                    trackChangeListener?.onPlayStateChanged(true)
                }
            }
        }
    }

    // В методе pause():
    fun pause() {
        if (isPlaying) {
            mediaPlayer?.pause()
            isPlaying = false
            abandonAudioFocus()
            showNotification()
            updateWidget()
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
        if (playlist.isEmpty()) {
            Log.e(TAG, "Playlist is empty")
            return
        }

        try {
            Log.d(TAG, "playNext called. Current position: $currentPosition, Shuffle: $shuffleMode, Repeat: $repeatMode")

            when (repeatMode) {
                RepeatMode.ONE -> {
                    // Повтор одного трека - просто играем заново текущий
                    if (currentTrack != null) {
                        prepareTrack(currentTrack!!)
                        onPreparedListener = { play() }
                    }
                }
                RepeatMode.ALL -> {
                    if (shuffleMode) {
                        // Режим перемешивания + повтор всех
                        val newPosition = generateRandomPosition()
                        currentPosition = newPosition
                        Log.d(TAG, "Shuffle mode (repeat all) - new position: $currentPosition")
                    } else {
                        // Обычный циклический переход
                        currentPosition = (currentPosition + 1) % playlist.size
                        Log.d(TAG, "Sequential next (repeat all) to: $currentPosition")
                    }
                    currentTrack = playlist[currentPosition]
                    prepareTrack(currentTrack!!)
                    onPreparedListener = { play() }
                }
                RepeatMode.NONE -> {
                    if (shuffleMode) {
                        // Режим перемешивания без повтора
                        if (playlist.size > 1) {
                            val newPosition = generateRandomPosition()
                            currentPosition = newPosition
                            Log.d(TAG, "Shuffle mode (no repeat) to: $currentPosition")
                            currentTrack = playlist[currentPosition]
                            prepareTrack(currentTrack!!)
                            onPreparedListener = { play() }
                        }
                    } else {
                        // Обычный переход, но без цикла
                        if (currentPosition < playlist.size - 1) {
                            currentPosition++
                            Log.d(TAG, "Sequential next to: $currentPosition")
                            currentTrack = playlist[currentPosition]
                            prepareTrack(currentTrack!!)
                            onPreparedListener = { play() }
                        } else {
                            Log.d(TAG, "Last track, stopping")
                            pause()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in playNext", e)
        }
    }

    fun playPrevious() {
        if (playlist.isEmpty()) {
            Log.e(TAG, "Playlist is empty")
            return
        }

        try {
            Log.d(TAG, "playPrevious called. Current position: $currentPosition, Shuffle: $shuffleMode, Repeat: $repeatMode")

            when (repeatMode) {
                RepeatMode.ONE -> {
                    if (currentTrack != null) {
                        prepareTrack(currentTrack!!)
                        onPreparedListener = { play() }
                    }
                }
                else -> {
                    if (shuffleMode) {
                        // В режиме перемешивания предыдущий трек - тоже случайный
                        val newPosition = generateRandomPosition()
                        currentPosition = newPosition
                        Log.d(TAG, "Shuffle mode previous to: $currentPosition")
                    } else {
                        // Обычный переход назад
                        currentPosition = if (currentPosition > 0) {
                            currentPosition - 1
                        } else {
                            playlist.size - 1
                        }
                        Log.d(TAG, "Sequential previous to: $currentPosition")
                    }
                    currentTrack = playlist[currentPosition]
                    prepareTrack(currentTrack!!)
                    onPreparedListener = { play() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in playPrevious", e)
        }
    }

    // Вспомогательный метод для генерации случайной позиции
    private fun generateRandomPosition(): Int {
        if (playlist.size <= 1) return 0

        var newPosition = currentPosition
        while (newPosition == currentPosition) {
            newPosition = (0 until playlist.size).random()
        }
        return newPosition
    }



    fun getCurrentTrack(): Track? = currentTrack

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        Log.d(TAG, "Repeat mode set to: $mode")
    }

    fun setShuffleMode(enabled: Boolean) {
        shuffleMode = enabled
        Log.d(TAG, "Shuffle mode set to: $enabled")
    }

    fun setOnTrackChangeListener(listener: OnTrackChangeListener) {
        trackChangeListener = listener
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
            "PLAY_PAUSE" -> {
                if (isPlaying) pause() else play()
            }
            "NEXT" -> {
                playNext()
            }
            "PREV" -> {
                playPrevious()
            }
            "PAUSE" -> {
                pause()
            }
            "STOP" -> {
                stop()
            }
        }

        return START_STICKY
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

    // Добавьте этот метод в MusicService
    fun startVisualizerDebug() {
        try {
            val sessionId = mediaPlayer?.audioSessionId ?: run {
                Log.e(TAG, "No audio session ID available")
                return
            }
            Log.d(TAG, "Starting visualizer debug for session: $sessionId")

            val visualizer = Visualizer(sessionId)
            visualizer.captureSize = Visualizer.getCaptureSizeRange()[1]

            visualizer.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (waveform != null) {
                            Log.d(TAG, "Waveform data received, size: ${waveform.size}")
                            // Проверяем, есть ли ненулевые данные
                            var hasData = false
                            for (i in 0 until minOf(10, waveform.size)) {
                                if (abs(waveform[i].toInt()) > 10) {
                                    hasData = true
                                    break
                                }
                            }
                            Log.d(TAG, "Has audio data: $hasData")

                            visualizerListener?.onWaveformData(waveform)
                        }
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {}
                },
                Visualizer.getMaxCaptureRate() / 2,
                true,
                false
            )

            visualizer.enabled = true
            Log.d(TAG, "Visualizer enabled: ${visualizer.enabled}")

        } catch (e: Exception) {
            Log.e(TAG, "Error in visualizer debug", e)
        }
    }
}