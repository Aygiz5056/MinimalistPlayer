package com.example.minimalistplayer.ui.nowplaying

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import com.bumptech.glide.Glide
import com.example.minimalistplayer.R
import com.example.minimalistplayer.data.Track
import com.example.minimalistplayer.service.MusicService
import com.example.minimalistplayer.ui.artist.ArtistTracksActivity
import com.example.minimalistplayer.ui.visualizer.AudioVisualizerView
import kotlin.math.abs

class NowPlayingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NowPlayingActivity"
    }

    // Прямые ссылки на View
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar
    private lateinit var backButton: ImageButton
    private lateinit var repeatButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var favoriteButton: ImageButton
    private lateinit var backgroundBlur: ImageView
    private lateinit var overlay: View
    private lateinit var albumArtContainer: FrameLayout
    private lateinit var albumArt: ImageView
    private lateinit var visualizer: AudioVisualizerView
    private lateinit var trackTitleTextView: TextView
    private lateinit var trackArtistTextView: TextView
    private lateinit var currentTimeTextView: TextView
    private lateinit var totalTimeTextView: TextView
    private lateinit var progressBar: SeekBar
    private lateinit var playPauseButton: ImageButton
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var volumeIndicator: FrameLayout
    private lateinit var volumeIcon: ImageView
    private lateinit var volumeProgress: ProgressBar
    private lateinit var volumePercent: TextView
    private lateinit var playerInfo: LinearLayout
    private lateinit var playerControls: LinearLayout

    private lateinit var gestureDetector: GestureDetectorCompat

    private var musicService: MusicService? = null
    private var isBound = false

    private var isRecording = false
    private val handler = Handler(Looper.getMainLooper())

    // Данные текущего трека
    private var currentTrackId: Long = -1
    private var currentTrackTitle: String = ""
    private var currentTrackArtist: String = ""

    // Режимы воспроизведения
    private var isShuffleMode = false
    private var repeatMode: MusicService.RepeatMode = MusicService.RepeatMode.NONE

    // Громкость
    private var currentVolume = 50
    private var isVolumeIndicatorVisible = false
    private var screenWidth = 0

    private val hideVolumeRunnable = Runnable {
        volumeIndicator.visibility = View.GONE
        isVolumeIndicatorVisible = false
    }

    // Слушатель для данных визуалайзера
    private val visualizerDataListener = object : MusicService.VisualizerDataListener {
        override fun onWaveformData(waveform: ByteArray) {
            runOnUiThread {
                if (visualizer.isVisualizing) {
                    visualizer.updateVisualizerData(waveform)
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                musicService = (service as MusicService.MusicBinder).getService()
                isBound = true
                Log.d(TAG, "Service connected")

                // Устанавливаем слушатель изменений треков
                musicService?.setOnTrackChangeListener(object : MusicService.OnTrackChangeListener {
                    override fun onTrackChanged(track: Track) {
                        Log.d(TAG, "onTrackChanged: ${track.title}")
                        runOnUiThread {
                            trackTitleTextView.text = track.title
                            trackArtistTextView.text = track.artist
                            totalTimeTextView.text = formatTime(track.duration.toInt())
                            loadAlbumArt(track)
                            progressBar.progress = 0
                            currentTimeTextView.text = "0:00"
                        }
                    }

                    override fun onPlayStateChanged(isPlaying: Boolean) {
                        Log.d(TAG, "onPlayStateChanged: $isPlaying")
                        runOnUiThread {
                            val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                            playPauseButton.setImageResource(icon)
                            visualizer.isVisualizing = isPlaying
                        }
                    }
                })

                // Включаем визуалайзер
                musicService?.enableVisualizer(true)
                visualizer.isVisualizing = true

                // Устанавливаем слушатель данных визуалайзера
                musicService?.setVisualizerListener(visualizerDataListener)

                // Синхронизируем состояние
                syncStateWithService()
                updateUI()
                setupServiceListeners()

            } catch (e: Exception) {
                Log.e(TAG, "Error in onServiceConnected", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        try {
            // Получаем данные из Intent
            currentTrackId = intent.getLongExtra("track_id", -1)
            currentTrackTitle = intent.getStringExtra("track_title") ?: "Неизвестно"
            currentTrackArtist = intent.getStringExtra("track_artist") ?: "Неизвестно"

            Log.d(TAG, "Received: id=$currentTrackId, title=$currentTrackTitle, artist=$currentTrackArtist")

            initViews()
            setupGestureDetector()
            setupClickListeners()
            setupSeekBar()

            // Получаем текущую громкость системы
            currentVolume = getCurrentSystemVolume()
            volumeProgress.progress = currentVolume
            volumePercent.text = "$currentVolume%"

            // Показываем данные сразу
            trackTitleTextView.text = currentTrackTitle
            trackArtistTextView.text = currentTrackArtist

            val serviceIntent = Intent(this, MusicService::class.java)
            bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

            startProgressUpdate()

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Ошибка загрузки: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        backButton = findViewById(R.id.backButton)
        repeatButton = findViewById(R.id.repeatButton)
        shuffleButton = findViewById(R.id.shuffleButton)
        favoriteButton = findViewById(R.id.favoriteButton)
        backgroundBlur = findViewById(R.id.backgroundBlur)
        overlay = findViewById(R.id.overlay)
        albumArtContainer = findViewById(R.id.albumArtContainer)
        albumArt = findViewById(R.id.albumArt)
        visualizer = findViewById(R.id.visualizer)
        trackTitleTextView = findViewById(R.id.trackTitle)
        trackArtistTextView = findViewById(R.id.trackArtist)
        currentTimeTextView = findViewById(R.id.currentTime)
        totalTimeTextView = findViewById(R.id.totalTime)
        progressBar = findViewById(R.id.progressBar)
        playPauseButton = findViewById(R.id.playPauseButton)
        prevButton = findViewById(R.id.prevButton)
        nextButton = findViewById(R.id.nextButton)
        volumeIndicator = findViewById(R.id.volumeIndicator)
        volumeIcon = findViewById(R.id.volumeIcon)
        volumeProgress = findViewById(R.id.volumeProgress)
        volumePercent = findViewById(R.id.volumePercent)
        playerInfo = findViewById(R.id.playerInfo)
        playerControls = findViewById(R.id.playerControls)

        // Начальное состояние
        visualizer.isVisualizing = true
        volumeIndicator.visibility = View.GONE
        showAlbumArt(false)

        // Получаем ширину экрана для жестов
        screenWidth = resources.displayMetrics.widthPixels
    }

    private fun getCurrentSystemVolume(): Int {
        return try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentSysVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            (currentSysVolume * 100 / maxVolume).coerceIn(0, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting volume", e)
            50
        }
    }

    private fun setVolume(progress: Int) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val newVolume = (progress * maxVolume / 100).coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
            currentVolume = progress
            updateVolumeUI()
            showVolumeIndicator()
            Log.d(TAG, "Volume set to: $progress%")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting volume", e)
        }
    }

    private fun showVolumeIndicator() {
        if (!::volumeIndicator.isInitialized) return

        volumeIndicator.visibility = View.VISIBLE
        volumeIndicator.bringToFront()
        volumeProgress.progress = currentVolume
        volumePercent.text = "$currentVolume%"
        isVolumeIndicatorVisible = true

        updateVolumeUI()

        volumeIndicator.removeCallbacks(hideVolumeRunnable)
        volumeIndicator.postDelayed(hideVolumeRunnable, 1500)
    }

    private fun updateVolumeUI() {
        volumeProgress.progress = currentVolume
        volumePercent.text = "$currentVolume%"

        volumeIcon.setImageResource(
            when {
                currentVolume == 0 -> R.drawable.ic_volume_mute
                currentVolume < 33 -> R.drawable.ic_volume_low
                currentVolume < 66 -> R.drawable.ic_volume_medium
                else -> R.drawable.ic_volume_high
            }
        )

        val iconColor = when {
            currentVolume == 0 -> Color.parseColor("#FF808080")
            else -> Color.parseColor("#FFFF4081")
        }
        volumeIcon.setColorFilter(iconColor)
        volumePercent.setTextColor(iconColor)
    }

    private fun showAlbumArt(show: Boolean) {
        if (show) {
            albumArt.visibility = View.VISIBLE
            backgroundBlur.visibility = View.VISIBLE
            overlay.visibility = View.VISIBLE
            visualizer.visibility = View.GONE
            visualizer.isVisualizing = false
        } else {
            albumArt.visibility = View.GONE
            backgroundBlur.visibility = View.GONE
            overlay.visibility = View.GONE
            visualizer.visibility = View.VISIBLE
            visualizer.isVisualizing = true
        }
    }

    private fun loadAlbumArt(track: Track) {
        Log.d(TAG, "Loading album art for: ${track.title}, uri: ${track.albumArtUri}")

        if (track.albumArtUri != null) {
            Glide.with(this)
                .load(track.albumArtUri)
                .centerCrop()
                .error(R.drawable.ic_music_note)
                .into(albumArt)

            Glide.with(this)
                .load(track.albumArtUri)
                .centerCrop()
                .error(R.drawable.ic_music_note)
                .into(backgroundBlur)

            backgroundBlur.post {
                try {
                    val drawable = backgroundBlur.drawable
                    if (drawable is androidx.core.graphics.drawable.RoundedBitmapDrawable) {
                        val bitmap = drawable.bitmap
                        if (bitmap != null) {
                            val blurred = blurRenderScript(bitmap)
                            backgroundBlur.setImageBitmap(blurred)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error blurring image", e)
                }
            }

            showAlbumArt(true)
            Log.d(TAG, "Album art loaded successfully")
        } else {
            showAlbumArt(false)
            Log.d(TAG, "No album art available")
        }
    }

    private fun blurRenderScript(bitmap: Bitmap): Bitmap {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                val outputBitmap = Bitmap.createBitmap(bitmap)
                val renderScript = RenderScript.create(this)
                val blurScript = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript))
                val allocationIn = Allocation.createFromBitmap(renderScript, bitmap)
                val allocationOut = Allocation.createFromBitmap(renderScript, outputBitmap)

                blurScript.setRadius(25f)
                blurScript.setInput(allocationIn)
                blurScript.forEach(allocationOut)
                allocationOut.copyTo(outputBitmap)

                renderScript.destroy()
                outputBitmap
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in blurRenderScript", e)
            bitmap
        }
    }

    private fun setupServiceListeners() {
        musicService?.setOnCompletionListener {
            Log.d(TAG, "Track completed")
        }

        musicService?.setOnPreparedListener {
            runOnUiThread {
                totalTimeTextView.text = formatTime(musicService?.getDuration() ?: 0)
            }
        }
    }

    private fun syncStateWithService() {
        musicService?.let { service ->
            isShuffleMode = service.shuffleMode
            repeatMode = service.repeatMode
            updateButtonStates()
            Log.d(TAG, "State synced - Shuffle: $isShuffleMode, Repeat: $repeatMode")
        }
    }

    private fun updateButtonStates() {
        // Обновляем кнопку перемешивания
        if (isShuffleMode) {
            shuffleButton.setColorFilter(ContextCompat.getColor(this, R.color.accent_red))
            shuffleButton.imageTintList = ContextCompat.getColorStateList(this, R.color.accent_red)
            Log.d(TAG, "Shuffle button set to RED")
        } else {
            shuffleButton.setColorFilter(ContextCompat.getColor(this, R.color.white_transparent))
            shuffleButton.imageTintList = ContextCompat.getColorStateList(this, R.color.white_transparent)
            Log.d(TAG, "Shuffle button set to WHITE")
        }

        // Обновляем кнопку повтора
        when (repeatMode) {
            MusicService.RepeatMode.NONE -> {
                repeatButton.setImageResource(R.drawable.ic_repeat)
                repeatButton.setColorFilter(ContextCompat.getColor(this, R.color.white_transparent))
            }
            MusicService.RepeatMode.ALL -> {
                repeatButton.setImageResource(R.drawable.ic_repeat)
                repeatButton.setColorFilter(ContextCompat.getColor(this, R.color.accent_red))
            }
            MusicService.RepeatMode.ONE -> {
                repeatButton.setImageResource(R.drawable.ic_repeat_one)
                repeatButton.setColorFilter(ContextCompat.getColor(this, R.color.accent_red))
            }
        }
    }

    private fun updateUI() {
        musicService?.getCurrentTrack()?.let { track ->
            Log.d(TAG, "updateUI: ${track.title}")
            trackTitleTextView.text = track.title
            trackArtistTextView.text = track.artist
            totalTimeTextView.text = formatTime(track.duration.toInt())

            val icon = if (musicService?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
            playPauseButton.setImageResource(icon)

            loadAlbumArt(track)
        }
    }

    private fun formatTime(millis: Int): String {
        if (millis <= 0) return "0:00"

        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = (millis / (1000 * 60 * 60))

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun setupSeekBar() {
        progressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    musicService?.let { service ->
                        val position = (service.getDuration() * progress / 100)
                        service.seekTo(position)
                        currentTimeTextView.text = formatTime(position)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun startProgressUpdate() {
        Thread {
            while (true) {
                try {
                    Thread.sleep(1000)
                    runOnUiThread {
                        musicService?.let { service ->
                            if (service.isPlaying) {
                                val current = service.getCurrentPosition()
                                val duration = service.getDuration()
                                if (duration > 0) {
                                    val progress = (current * 100 / duration)
                                    progressBar.progress = progress
                                    currentTimeTextView.text = formatTime(current)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in progress update", e)
                    break
                }
            }
        }.start()
    }

    private fun setupGestureDetector() {
        Log.d(TAG, "setupGestureDetector called")

        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                Log.d(TAG, "Fling detected: vX=$velocityX, vY=$velocityY")

                if (e1 == null || e2 == null) return false

                val startX = e1.x
                val isRightSide = startX > screenWidth / 2

                // Вертикальные свайпы для громкости (только правая половина)
                if (abs(velocityY) > abs(velocityX) && abs(velocityY) > 300 && isRightSide) {
                    if (velocityY < -300) {
                        // Свайп вверх - громче
                        currentVolume = (currentVolume + 15).coerceIn(0, 100)
                        setVolume(currentVolume)
                        showVolumeIndicator()
                        Log.d(TAG, "Volume up fling: $currentVolume%")
                    } else if (velocityY > 300) {
                        // Свайп вниз - тише
                        currentVolume = (currentVolume - 15).coerceIn(0, 100)
                        setVolume(currentVolume)
                        showVolumeIndicator()
                        Log.d(TAG, "Volume down fling: $currentVolume%")
                    }
                    return true
                }

                // Горизонтальные свайпы для переключения треков (любая область)
                if (abs(velocityX) > abs(velocityY) && abs(velocityX) > 500) {
                    if (velocityX < -500) {
                        // Свайп влево - следующий трек
                        Log.d(TAG, "Swipe left - next track")
                        nextTrack()
                        return true
                    } else if (velocityX > 500) {
                        // Свайп вправо - предыдущий трек
                        Log.d(TAG, "Swipe right - previous track")
                        previousTrack()
                        return true
                    }
                }

                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                Log.d(TAG, "Double tap detected")
                togglePlayPause()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null || e2 == null) return false

                val startX = e1.x
                // Плавная регулировка громкости только в правой половине
                if (startX > screenWidth / 2 && abs(distanceY) > abs(distanceX)) {
                    val deltaY = e2.y - e1.y
                    if (abs(deltaY) > 5) {
                        // Инвертируем: свайп вверх (deltaY отрицательный) = увеличение
                        val change = (-deltaY / 10).toInt()
                        val newVolume = (currentVolume + change).coerceIn(0, 100)
                        if (newVolume != currentVolume) {
                            currentVolume = newVolume
                            setVolume(currentVolume)
                            showVolumeIndicator()
                            Log.d(TAG, "Volume scroll: $currentVolume%")
                        }
                    }
                    return true
                }
                return false
            }
        })

        // Вешаем обработчики на несколько элементов
        albumArtContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }

        playerInfo.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }

        playerControls.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun setupClickListeners() {
        backButton.setOnClickListener {
            Log.d(TAG, "Back button clicked")
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        repeatButton.setOnClickListener {
            repeatMode = when (repeatMode) {
                MusicService.RepeatMode.NONE -> MusicService.RepeatMode.ALL
                MusicService.RepeatMode.ALL -> MusicService.RepeatMode.ONE
                MusicService.RepeatMode.ONE -> MusicService.RepeatMode.NONE
            }

            updateButtonStates()

            when (repeatMode) {
                MusicService.RepeatMode.NONE -> {
                    Toast.makeText(this, "Повтор выключен", Toast.LENGTH_SHORT).show()
                }
                MusicService.RepeatMode.ALL -> {
                    Toast.makeText(this, "Повтор всех треков", Toast.LENGTH_SHORT).show()
                }
                MusicService.RepeatMode.ONE -> {
                    Toast.makeText(this, "Повтор одного трека", Toast.LENGTH_SHORT).show()
                }
            }

            musicService?.setRepeatMode(repeatMode)
            Log.d(TAG, "Repeat mode set to: $repeatMode")
        }

        shuffleButton.setOnClickListener {
            Log.d(TAG, "Shuffle button clicked, current state: $isShuffleMode")

            isShuffleMode = !isShuffleMode
            updateButtonStates()

            if (isShuffleMode) {
                Toast.makeText(this, "Перемешивание включено", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Перемешивание выключено", Toast.LENGTH_SHORT).show()
            }

            musicService?.setShuffleMode(isShuffleMode)
            Log.d(TAG, "Shuffle mode set to: $isShuffleMode")
        }

        favoriteButton.setOnClickListener {
            toggleFavorite()
        }

        playerInfo.setOnClickListener {
            musicService?.getCurrentTrack()?.let { track ->
                val intent = Intent(this, ArtistTracksActivity::class.java)
                intent.putExtra("artist_name", track.artist)
                startActivity(intent)
            }
        }

        playPauseButton.setOnClickListener { togglePlayPause() }
        prevButton.setOnClickListener { previousTrack() }
        nextButton.setOnClickListener { nextTrack() }
    }

    private fun togglePlayPause() {
        musicService?.let { service ->
            if (service.isPlaying) {
                service.pause()
                playPauseButton.setImageResource(R.drawable.ic_play)
                visualizer.isVisualizing = false
                Log.d(TAG, "Paused")
            } else {
                service.play()
                playPauseButton.setImageResource(R.drawable.ic_pause)
                visualizer.isVisualizing = true
                Log.d(TAG, "Playing")
            }
        }
    }

    private fun previousTrack() {
        musicService?.playPrevious()
        // Даем время на смену трека
        handler.postDelayed({
            musicService?.getAudioSessionId()?.let {
                visualizer.setAudioSessionId(it)
            }
        }, 300)
        Log.d(TAG, "Previous track")
    }

    private fun nextTrack() {
        musicService?.playNext()
        handler.postDelayed({
            musicService?.getAudioSessionId()?.let {
                visualizer.setAudioSessionId(it)
            }
        }, 300)
        Log.d(TAG, "Next track")
    }

    private fun toggleFavorite() {
        val isFavorite = favoriteButton.tag == "favorite"
        val icon = if (isFavorite) R.drawable.ic_favorite_border else R.drawable.ic_favorite
        favoriteButton.setImageResource(icon)
        favoriteButton.tag = if (isFavorite) "not_favorite" else "favorite"

        val message = if (isFavorite) "Удалено из избранного" else "Добавлено в избранное"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        Log.d(TAG, message)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")

        musicService?.enableVisualizer(true)
        visualizer.isVisualizing = true

        syncStateWithService()
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")

        musicService?.enableVisualizer(false)
        visualizer.isVisualizing = false
    }

    override fun onDestroy() {
        super.onDestroy()

        musicService?.enableVisualizer(false)
        visualizer.release()

        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Activity destroyed")
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}