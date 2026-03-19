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
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.minimalistplayer.R
import com.example.minimalistplayer.data.MusicRepository
import com.example.minimalistplayer.data.Track
import com.example.minimalistplayer.service.MusicService
import com.example.minimalistplayer.ui.artist.ArtistTracksActivity
import com.example.minimalistplayer.ui.visualizer.AudioVisualizerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var musicRepository: MusicRepository

    private var musicService: MusicService? = null
    private var isBound = false

    private val handler = Handler(Looper.getMainLooper())

    // Данные текущего трека
    private var currentTrackId: Long = -1
    private var currentTrackTitle: String = ""
    private var currentTrackArtist: String = ""
    private var currentTrack: Track? = null

    // Режимы воспроизведения
    private var isShuffleMode = false
    private var repeatMode: MusicService.RepeatMode = MusicService.RepeatMode.NONE

    // Громкость
    private var currentVolume = 50
    private var screenWidth = 0

    // Обновим hideVolumeRunnable с анимацией исчезновения
    private val hideVolumeRunnable = Runnable {
        volumeIndicator.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                volumeIndicator.visibility = View.GONE
                volumeIndicator.alpha = 1f // Сбрасываем для следующего показа
            }
            .start()
    }

    // Добавим переменные для плавности
    private var targetVolume = 50
    private var volumeChangeHandler = Handler(Looper.getMainLooper())
    private var volumeChangeRunnable: Runnable? = null
    private var isVolumeAnimating = false

    // Добавим переменные для отслеживания касания
    private var isTouchingVolume = false
    private var touchStartY = 0f
    private var touchStartVolume = 0
    private var screenHeight = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true

            // Устанавливаем слушатель изменений треков
            musicService?.setOnTrackChangeListener(object : MusicService.OnTrackChangeListener {
                override fun onTrackChanged(track: Track) {
                    runOnUiThread {
                        currentTrack = track
                        trackTitleTextView.text = track.title
                        trackArtistTextView.text = track.artist
                        totalTimeTextView.text = formatTime(track.duration.toInt())
                        loadAlbumArt(track)
                        progressBar.progress = 0
                        currentTimeTextView.text = "0:00"
                        checkFavoriteStatus(track)
                    }
                }

                override fun onPlayStateChanged(isPlaying: Boolean) {
                    runOnUiThread {
                        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        playPauseButton.setImageResource(icon)
                        visualizer.isVisualizing = isPlaying
                    }
                }
            })

            // Синхронизируем состояние
            syncStateWithService()
            updateUI()

            // Получаем текущую громкость
            currentVolume = getCurrentSystemVolume()
            volumeProgress.progress = currentVolume
            volumePercent.text = "$currentVolume%"
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        // Получаем данные из Intent
        currentTrackId = intent.getLongExtra("track_id", -1)
        currentTrackTitle = intent.getStringExtra("track_title") ?: "Неизвестно"
        currentTrackArtist = intent.getStringExtra("track_artist") ?: "Неизвестно"

        Log.d(TAG, "Received: $currentTrackTitle - $currentTrackArtist")

        musicRepository = MusicRepository(this)

        initViews()
        setupGestureDetector()
        setupClickListeners()
        setupSeekBar()

        trackTitleTextView.text = currentTrackTitle
        trackArtistTextView.text = currentTrackArtist

        val serviceIntent = Intent(this, MusicService::class.java)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        startProgressUpdate()
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
        screenWidth = resources.displayMetrics.widthPixels
        screenHeight = resources.displayMetrics.heightPixels

        visualizer.isVisualizing = true
        volumeIndicator.visibility = View.GONE
        showAlbumArt(false)

        screenWidth = resources.displayMetrics.widthPixels
    }

    private fun getCurrentSystemVolume(): Int {
        return try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentSysVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val volume = (currentSysVolume * 100 / maxVolume).coerceIn(0, 100)
            currentVolume = volume
            updateVolumeUI()
            volume
        } catch (e: Exception) {
            50
        }
    }

    // Обновим метод setVolume для плавности
    private fun setVolume(progress: Int) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val newVolume = (progress * maxVolume / 100).coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

            targetVolume = progress

            // Плавно обновляем UI
            if (!isVolumeAnimating) {
                startVolumeAnimation()
            }

            showVolumeIndicator()
            Log.d(TAG, "Volume set to: $progress%")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting volume", e)
        }
    }


    // Плавная анимация изменения громкости
    private fun startVolumeAnimation() {
        isVolumeAnimating = true
        volumeChangeRunnable = object : Runnable {
            override fun run() {
                if (currentVolume != targetVolume) {
                    val step = if (targetVolume > currentVolume) 1 else -1
                    currentVolume += step
                    // Исправляем здесь - используем updateVolumeUI вместо updateVolumeIcon
                    updateVolumeUI()
                    volumeChangeHandler.postDelayed(this, 20)
                } else {
                    isVolumeAnimating = false
                }
            }
        }
        volumeChangeHandler.post(volumeChangeRunnable!!)
    }

    // Обновим showVolumeIndicator для более длительного показа
    private fun showVolumeIndicator() {
        if (!::volumeIndicator.isInitialized) return

        // При активном касании показываем всегда
        if (isTouchingVolume) {
            if (volumeIndicator.visibility != View.VISIBLE) {
                volumeIndicator.alpha = 0f
                volumeIndicator.visibility = View.VISIBLE
                volumeIndicator.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()
            }
            // Сбрасываем таймер скрытия
            volumeIndicator.removeCallbacks(hideVolumeRunnable)
            return
        }

        // Если не касаемся, показываем с таймером
        if (volumeIndicator.visibility == View.VISIBLE) {
            volumeIndicator.removeCallbacks(hideVolumeRunnable)
            volumeIndicator.postDelayed(hideVolumeRunnable, 1500)
        } else {
            volumeIndicator.alpha = 0f
            volumeIndicator.visibility = View.VISIBLE
            volumeIndicator.animate()
                .alpha(1f)
                .setDuration(200)
                .withEndAction {
                    volumeIndicator.postDelayed(hideVolumeRunnable, 1500)
                }
                .start()
        }
    }

    private fun setVolumeImmediate(progress: Int) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val newVolume = (progress * maxVolume / 100).coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

            // Мгновенно обновляем UI
            updateVolumeUI()

            Log.d(TAG, "Volume set to: $progress%")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting volume", e)
        }
    }

    // Обновленный метод updateVolumeUI (единый метод для всего)
    private fun updateVolumeUI() {
        // Обновляем прогресс
        volumeProgress.progress = currentVolume
        volumePercent.text = "$currentVolume%"

        // Обновляем иконку в зависимости от громкости
        volumeIcon.setImageResource(
            when {
                currentVolume == 0 -> R.drawable.ic_volume_mute
                currentVolume < 33 -> R.drawable.ic_volume_low
                currentVolume < 66 -> R.drawable.ic_volume_medium
                else -> R.drawable.ic_volume_high
            }
        )

        // Обновляем цвет
        val iconColor = if (currentVolume == 0) Color.GRAY else Color.parseColor("#FFFF4081")
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
        } else {
            showAlbumArt(false)
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

    private fun syncStateWithService() {
        musicService?.let { service ->
            isShuffleMode = service.shuffleMode
            repeatMode = service.repeatMode
            updateButtonStates()
        }
    }

    private fun updateButtonStates() {
        // Кнопка перемешивания
        shuffleButton.setColorFilter(
            ContextCompat.getColor(
                this,
                if (isShuffleMode) R.color.accent_red else R.color.white_transparent
            )
        )

        // Кнопка повтора
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
            currentTrack = track
            trackTitleTextView.text = track.title
            trackArtistTextView.text = track.artist
            totalTimeTextView.text = formatTime(track.duration.toInt())

            playPauseButton.setImageResource(
                if (musicService?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
            )

            loadAlbumArt(track)
            checkFavoriteStatus(track)
        }
    }

    private fun checkFavoriteStatus(track: Track) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val isFavorite = musicRepository.isFavorite(track.id)
                withContext(Dispatchers.Main) {
                    updateFavoriteButton(isFavorite)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking favorite", e)
            }
        }
    }

    private fun updateFavoriteButton(isFavorite: Boolean) {
        val icon = if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        favoriteButton.setImageResource(icon)
        favoriteButton.tag = if (isFavorite) "favorite" else "not_favorite"
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
                    break
                }
            }
        }.start()
    }

    private fun setupGestureDetector() {
        Log.d(TAG, "setupGestureDetector called")

        // Создаем детектор жестов для свайпов и двойного тапа
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false

                Log.d(TAG, "Fling detected: vX=$velocityX, vY=$velocityY")

                // Горизонтальные свайпы для переключения треков
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
        })

        // Устанавливаем обработчик касаний на контейнер с обложкой
        albumArtContainer.setOnTouchListener { _, event ->
            // Сначала обрабатываем управление громкостью (правая половина экрана)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.x > screenWidth / 2) {
                        Log.d(TAG, "Volume touch started at y=${event.y}")
                        isTouchingVolume = true
                        touchStartY = event.y
                        touchStartVolume = currentVolume
                        showVolumeIndicator()
                        return@setOnTouchListener true
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isTouchingVolume) {
                        // Рассчитываем изменение громкости на основе движения пальца
                        val deltaY = touchStartY - event.y
                        val volumeChange = (deltaY / screenHeight * 100).toInt()
                        val newVolume = (touchStartVolume + volumeChange).coerceIn(0, 100)

                        if (newVolume != currentVolume) {
                            currentVolume = newVolume
                            setVolumeImmediate(currentVolume)
                            Log.d(TAG, "Volume changed to: $currentVolume%")
                        }
                        return@setOnTouchListener true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isTouchingVolume) {
                        Log.d(TAG, "Volume touch ended")
                        isTouchingVolume = false
                        // Индикатор скроется автоматически через таймер
                        return@setOnTouchListener true
                    }
                }
            }

            // Если не обработано как управление громкостью, передаем в детектор жестов
            gestureDetector.onTouchEvent(event)
        }

        // Также вешаем обработчик на playerInfo для увеличения области
        playerInfo.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }

        Log.d(TAG, "Gesture detector setup complete")
    }

    private fun setupClickListeners() {
        backButton.setOnClickListener {
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

            val message = when (repeatMode) {
                MusicService.RepeatMode.NONE -> "Повтор выключен"
                MusicService.RepeatMode.ALL -> "Повтор всех треков"
                MusicService.RepeatMode.ONE -> "Повтор одного трека"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

            musicService?.setRepeatMode(repeatMode)
            musicService?.savePlaybackState() // Сохраняем после изменения
        }

        shuffleButton.setOnClickListener {
            isShuffleMode = !isShuffleMode
            updateButtonStates()

            Toast.makeText(
                this,
                if (isShuffleMode) "Перемешивание включено" else "Перемешивание выключено",
                Toast.LENGTH_SHORT
            ).show()

            musicService?.setShuffleMode(isShuffleMode)
            musicService?.savePlaybackState() // Сохраняем после изменения
        }

        favoriteButton.setOnClickListener {
            toggleFavorite()
        }

        playerInfo.setOnClickListener {
            currentTrack?.let { track ->
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
            } else {
                service.play()
            }
        }
    }

    private fun previousTrack() {
        musicService?.playPrevious()
    }

    private fun nextTrack() {
        musicService?.playNext()
    }

    private fun toggleFavorite() {
        currentTrack?.let { track ->
            val newFavoriteState = favoriteButton.tag != "favorite"

            // Обновляем иконку сразу для отзывчивости
            updateFavoriteButton(newFavoriteState)

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    if (newFavoriteState) {
                        musicRepository.addToFavorites(track)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@NowPlayingActivity, "❤️ Добавлено в избранное", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        musicRepository.removeFromFavorites(track)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@NowPlayingActivity, "♡ Удалено из избранного", Toast.LENGTH_SHORT).show()
                        }
                    }
                    track.isFavorite = newFavoriteState

                    // Отправляем broadcast, чтобы MainActivity обновилась
                    sendBroadcast(Intent("FAVORITE_UPDATED"))

                } catch (e: Exception) {
                    Log.e(TAG, "Error toggling favorite", e)
                    withContext(Dispatchers.Main) {
                        updateFavoriteButton(!newFavoriteState)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        syncStateWithService()
    }

    override fun onPause() {
        super.onPause()
        musicService?.savePlaybackState()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Безопасно удаляем callbacks
        volumeChangeRunnable?.let {
            volumeChangeHandler.removeCallbacks(it)
        }

        musicService?.savePlaybackState()

        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        handler.removeCallbacksAndMessages(null)
        visualizer.release()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}