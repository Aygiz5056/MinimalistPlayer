package com.example.minimalistplayer.ui.main

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.minimalistplayer.R
import com.example.minimalistplayer.data.FavoriteDatabase
import com.example.minimalistplayer.data.MusicRepository
import com.example.minimalistplayer.data.Playlist
import com.example.minimalistplayer.data.Track
import com.example.minimalistplayer.service.MusicService
import com.example.minimalistplayer.ui.dialog.SleepTimerDialog
import com.example.minimalistplayer.ui.nowplaying.NowPlayingActivity
import com.example.minimalistplayer.ui.playlist.PlaylistActivity
import com.example.minimalistplayer.ui.settings.SettingsActivity
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat

class MainActivity : AppCompatActivity() {

    // Прямые ссылки на View вместо binding
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var menuButton: ImageButton
    private lateinit var favoritesButton: ImageButton
    private lateinit var searchView: SearchView

    // Мини-плеер
    private lateinit var miniPlayerTrackTitle: android.widget.TextView
    private lateinit var miniPlayerProgressBar: android.widget.SeekBar
    private lateinit var miniPlayerPlayPause: ImageButton
    private lateinit var miniPlayerPrev: ImageButton
    private lateinit var miniPlayerNext: ImageButton

    private lateinit var trackAdapter: TrackAdapter
    private lateinit var musicRepository: MusicRepository
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var database: FavoriteDatabase

    private var allTracks = listOf<Track>()
    private var isShowingFavorites = false

    // Для связи с сервисом
    private var musicService: MusicService? = null
    private var isBound = false

    private lateinit var backgroundAlbumArt: ImageView
    private lateinit var backgroundOverlay: View

    private var miniPlayerCard: androidx.cardview.widget.CardView? = null
    private var trackInfoContainer: View? = null
    private lateinit var currentTrackArtist: TextView

    private val connection = object : ServiceConnection {
        // В connection.onServiceConnected:
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            Log.d("MainActivity", "Service connected")

            // Устанавливаем слушатель изменений
            musicService?.setOnTrackChangeListener(object : MusicService.OnTrackChangeListener {
                override fun onTrackChanged(track: Track) {
                    runOnUiThread {
                        updateMiniPlayerFromService()
                    }
                }

                override fun onPlayStateChanged(isPlaying: Boolean) {
                    runOnUiThread {
                        // Обновляем иконку play/pause
                        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        miniPlayerPlayPause.setImageResource(icon)
                    }
                }
            })

            updateMiniPlayerFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
            Log.d("MainActivity", "Service disconnected")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            loadMusic()
        } else {
            Toast.makeText(this, "Нужно разрешение на чтение музыки", Toast.LENGTH_LONG).show()
            allTracks = musicRepository.getMockTracks()
            trackAdapter.updateTracks(allTracks)
            showEmptyView(allTracks.isEmpty())
        }
    }

    private fun logCurrentState() {
        musicService?.let { service ->
            Log.d("MainActivity", "Current state - Shuffle: ${service.shuffleMode}, Repeat: ${service.repeatMode}, Track: ${service.getCurrentTrack()?.title}")
        }
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)
        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.emptyView)
        menuButton = findViewById(R.id.menuButton)
        favoritesButton = findViewById(R.id.favoritesButton)
        searchView = findViewById(R.id.searchView)

        // Добавь эти две строки!
        backgroundAlbumArt = findViewById(R.id.backgroundAlbumArt)
        backgroundOverlay = findViewById(R.id.backgroundOverlay)

        // Мини-плеер элементы
        miniPlayerCard = findViewById(R.id.miniPlayerCard)
        trackInfoContainer = findViewById(R.id.trackInfoContainer)
        miniPlayerTrackTitle = findViewById(R.id.currentTrackTitle)
        currentTrackArtist = findViewById(R.id.currentTrackArtist)
        miniPlayerProgressBar = findViewById(R.id.progressBar)
        miniPlayerPlayPause = findViewById(R.id.playPauseButton)
        miniPlayerPrev = findViewById(R.id.prevButton)
        miniPlayerNext = findViewById(R.id.nextButton)

        try {
            miniPlayerCard = findViewById(R.id.miniPlayerCard)
            trackInfoContainer = findViewById(R.id.trackInfoContainer)
        } catch (e: Exception) {
            miniPlayerCard = null
            trackInfoContainer = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        musicRepository = MusicRepository(this)
        database = FavoriteDatabase.getInstance(this)

        setupUI()
        setupDrawer()
        setupGestures()
        checkPermissions()

        // Проверяем, нужно ли открыть меню
        if (intent.getBooleanExtra("open_drawer", false)) {
            drawerLayout.openDrawer(navigationView)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Обрабатываем новые интенты, если Activity уже существует
        if (intent?.getBooleanExtra("open_drawer", false) == true) {
            shouldOpenDrawer = true
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "onStart called")
        val intent = Intent(this, MusicService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume called")

        // При возврате на главный экран обновляем информацию о текущем треке
        updateMiniPlayerFromService()

        // Проверяем, нужно ли открыть меню (при возврате с экрана песни)
        if (shouldOpenDrawer) {
            drawerLayout.openDrawer(navigationView)
            shouldOpenDrawer = false
        }
    }

    // Добавим переменную
    private var shouldOpenDrawer = false
    override fun onPause() {
        super.onPause()
        Log.d("MainActivity", "onPause called")
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "onStop called")
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy called")
    }

    private fun setupUI() {
        trackAdapter = TrackAdapter(
            tracks = emptyList(),
            onItemClick = { track ->
                // Только воспроизводим трек, НЕ переустанавливаем плейлист
                musicService?.let { service ->
                    // Устанавливаем плейлист ТОЛЬКО если его нет или он пустой
                    if (service.getPlaylist().isEmpty()) {
                        service.setPlaylist(allTracks, allTracks.indexOf(track))
                    } else {
                        // Если плейлист уже есть, просто переключаемся на выбранный трек
                        service.playTrackAtPosition(allTracks.indexOf(track))
                    }
                    service.play()
                    updateMiniPlayer(track)
                }
            },
            onFavoriteClick = { track, position ->
                lifecycleScope.launch {
                    if (track.isFavorite) {
                        musicRepository.addToFavorites(track)
                        Toast.makeText(this@MainActivity, "Добавлено в избранное", Toast.LENGTH_SHORT).show()
                    } else {
                        musicRepository.removeFromFavorites(track)
                        Toast.makeText(this@MainActivity, "Удалено из избранного", Toast.LENGTH_SHORT).show()
                    }

                    trackAdapter.updateTrack(track, position)

                    if (isShowingFavorites) {
                        filterTracks()
                    }
                }
            },
            onItemLongClick = { track ->
                showAddToPlaylistDialog(track)
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = trackAdapter

        menuButton.setOnClickListener {
            drawerLayout.openDrawer(navigationView)
        }

        favoritesButton.setOnClickListener {
            isShowingFavorites = !isShowingFavorites
            val icon = if (isShowingFavorites) {
                R.drawable.ic_favorite
            } else {
                R.drawable.ic_favorite_border
            }
            favoritesButton.setImageResource(icon)
            filterTracks()
        }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                filterTracks(newText)
                return true
            }
        })

        setupMiniPlayer()
    }

    private fun setupMiniPlayer() {
        Log.d("MainActivity", "Setting up mini player")

        // Клик по карточке плеера открывает экран песни
        miniPlayerCard?.setOnClickListener {
            Log.d("MainActivity", "miniPlayerCard clicked")
            openNowPlaying()
        }

        // Клик по информации о треке тоже открывает экран песни
        trackInfoContainer?.setOnClickListener {
            Log.d("MainActivity", "trackInfoContainer clicked")
            openNowPlaying()
        }

        // Обработка длинного нажатия на название - показать полный текст
        miniPlayerTrackTitle.setOnLongClickListener {
            musicService?.getCurrentTrack()?.let { track ->
                showFullTextDialog("Название", track.title)
            } ?: Toast.makeText(this@MainActivity, "Нет текущего трека", Toast.LENGTH_SHORT).show()
            true
        }

        // Обработка длинного нажатия на исполнителя - показать полный текст
        currentTrackArtist.setOnLongClickListener {
            musicService?.getCurrentTrack()?.let { track ->
                showFullTextDialog("Исполнитель", track.artist)
            } ?: Toast.makeText(this@MainActivity, "Нет текущего трека", Toast.LENGTH_SHORT).show()
            true
        }

        // Кнопка play/pause
        miniPlayerPlayPause.setOnClickListener {
            try {
                musicService?.let { service ->
                    Log.d("MainActivity", "Play/Pause clicked")
                    if (service.isPlaying) {
                        service.pause()
                        miniPlayerPlayPause.setImageResource(R.drawable.ic_play)
                    } else {
                        service.play()
                        miniPlayerPlayPause.setImageResource(R.drawable.ic_pause)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error in play/pause", e)
            }
        }

        // Кнопка предыдущий трек
        miniPlayerPrev.setOnClickListener {
            try {
                musicService?.let { service ->
                    Log.d("MainActivity", "Previous clicked")
                    service.playPrevious()
                    updateMiniPlayerFromService()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error in previous click", e)
            }
        }

        // Кнопка следующий трек
        miniPlayerNext.setOnClickListener {
            try {
                musicService?.let { service ->
                    Log.d("MainActivity", "Next clicked")
                    service.playNext()
                    updateMiniPlayerFromService()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error in next click", e)
            }
        }

        // Обработка ползунка
        miniPlayerProgressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    musicService?.let { service ->
                        val position = (service.getDuration() * progress / 100)
                        service.seekTo(position)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        startProgressUpdate()
    }
    // Вспомогательная функция для проверки, коснулись ли конкретного View
    private fun isTouchOnView(event: MotionEvent, view: View?): Boolean {
        if (view == null) return false

        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val viewX = location[0]
        val viewY = location[1]
        val viewWidth = view.width
        val viewHeight = view.height

        val touchX = event.rawX.toInt()
        val touchY = event.rawY.toInt()

        return touchX >= viewX && touchX <= viewX + viewWidth &&
                touchY >= viewY && touchY <= viewY + viewHeight
    }

    // Добавьте в ресурсы (values/ids.xml) или используйте существующие id
// <item name="touch_start_x" type="id"/>
// <item name="touch_start_y" type="id"/>

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
                                    // Устанавливаем прогресс без вызова слушателя
                                    miniPlayerProgressBar.progress = progress
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error in progress update thread", e)
                    break
                }
            }
        }.start()
    }

    private fun showFullTextDialog(title: String, content: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(content)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openNowPlaying() {
        Log.d("MainActivity", "openNowPlaying called")

        if (!isBound || musicService == null) {
            Log.d("MainActivity", "Service not bound or null")
            Toast.makeText(this, "Плеер не готов", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val currentTrack = musicService?.getCurrentTrack()
            if (currentTrack == null) {
                Log.d("MainActivity", "No current track")
                Toast.makeText(this, "Нет текущего трека", Toast.LENGTH_SHORT).show()
                return
            }

            Log.d("MainActivity", "Opening NowPlayingActivity for: ${currentTrack.title}")
            val intent = Intent(this, NowPlayingActivity::class.java).apply {
                putExtra("track_id", currentTrack.id)
                putExtra("track_title", currentTrack.title)
                putExtra("track_artist", currentTrack.artist)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)

        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening NowPlayingActivity", e)
            Toast.makeText(this, "Ошибка открытия плеера: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getCurrentTrack(): Track? {
        return if (isBound && musicService != null) {
            musicService?.getCurrentTrack()
        } else {
            null
        }
    }

    private fun updateMiniPlayer(track: Track) {
        miniPlayerTrackTitle.text = track.title
        currentTrackArtist.text = track.artist
        miniPlayerPlayPause.setImageResource(R.drawable.ic_pause)
        //updateBackgroundAlbumArt(track)
    }

    private fun updateMiniPlayerFromService() {
        musicService?.getCurrentTrack()?.let { track ->
            miniPlayerTrackTitle.text = track.title
            currentTrackArtist.text = track.artist
            miniPlayerPlayPause.setImageResource(
                if (musicService?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
            )
            updateBackgroundAlbumArt(track)
        }
    }

    private fun updateBackgroundAlbumArt(track: Track?) {
        if (!::backgroundAlbumArt.isInitialized || !::backgroundOverlay.isInitialized) {
            return // если переменные не инициализированы, выходим
        }

        if (track == null) {
            backgroundAlbumArt.visibility = View.GONE
            backgroundOverlay.visibility = View.GONE
            return
        }

        if (track.albumArtUri != null) {
            Glide.with(this)
                .load(track.albumArtUri)
                .centerCrop()
                .into(backgroundAlbumArt)

            backgroundAlbumArt.visibility = View.VISIBLE
            backgroundOverlay.visibility = View.VISIBLE
        } else {
            backgroundAlbumArt.visibility = View.GONE
            backgroundOverlay.visibility = View.GONE
        }
    }

    private fun setupDrawer() {
        navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                R.id.nav_playlists -> {
                    startActivity(Intent(this, PlaylistActivity::class.java))
                }
                R.id.nav_donate -> {
                    openDonateLink()
                }
                R.id.nav_timer -> {
                    if (isBound && musicService != null) {
                        val timerDialog = SleepTimerDialog()
                        timerDialog.setMusicService(musicService!!)
                        timerDialog.setListener(object : SleepTimerDialog.SleepTimerListener {
                            override fun onTimerSet(minutes: Int) {
                                Toast.makeText(this@MainActivity, "Таймер установлен на $minutes мин", Toast.LENGTH_SHORT).show()
                            }

                            override fun onTimerCancel() {
                                Toast.makeText(this@MainActivity, "Таймер отключен", Toast.LENGTH_SHORT).show()
                            }
                        })
                        timerDialog.show(supportFragmentManager, SleepTimerDialog.TAG)
                    } else {
                        Toast.makeText(this, "Плеер не готов", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            drawerLayout.closeDrawers()
            true
        }
    }

    private fun openDonateLink() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.donationalerts.com/r/shedanhoolderz"))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupGestures() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                // Проверяем, открыто ли меню
                if (drawerLayout.isDrawerOpen(navigationView)) {
                    return false
                }

                // Получаем координаты начала касания
                val startY = e1?.y ?: return false

                // Получаем высоту верхней панели и мини-плеера
                val appBarHeight = findViewById<View>(R.id.appBarLayout)?.height ?: 0
                val miniPlayerHeight = miniPlayerCard?.height ?: 0
                val screenHeight = resources.displayMetrics.heightPixels

                // Область для свайпов (исключаем верхнюю панель и мини-плеер)
                val topMargin = dpToPx(20)
                val bottomMargin = dpToPx(20)

                val isInSwipeArea = startY > (appBarHeight + topMargin) &&
                        startY < (screenHeight - miniPlayerHeight - bottomMargin)

                if (!isInSwipeArea) {
                    Log.d("MainActivity", "Swipe outside allowed area: y=$startY")
                    return false
                }

                // Свайп влево - открываем экран песни
                if (abs(velocityX) > abs(velocityY) && velocityX < -300) {
                    Log.d("MainActivity", "Swipe left detected")
                    openNowPlaying()
                    return true
                }

                // Свайп вправо - открываем боковое меню
                if (abs(velocityX) > abs(velocityY) && velocityX > 300) {
                    Log.d("MainActivity", "Swipe right detected - opening drawer")
                    drawerLayout.openDrawer(navigationView)
                    return true
                }

                return false
            }
        })

        // Вешаем обработчик на recyclerView
        recyclerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    // Добавьте этот метод в класс MainActivity
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun checkPermissions() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                loadMusic()
            }
            else -> {
                requestPermissionLauncher.launch(permission)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun loadMusic() {
        lifecycleScope.launch {
            try {
                val tracks = musicRepository.getAllTracks()
                allTracks = tracks

                // Логируем статистику по обложкам
                val tracksWithArt = tracks.count { it.albumArtUri != null }
                Log.d("MainActivity", "Loaded ${tracks.size} tracks, $tracksWithArt have album art")

                if (tracks.isEmpty()) {
                    showEmptyView(true)
                    allTracks = musicRepository.getMockTracks()
                    trackAdapter.updateTracks(allTracks)

                    Toast.makeText(
                        this@MainActivity,
                        "Реальная музыка не найдена, показываем тестовые треки",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    showEmptyView(false)
                    trackAdapter.updateTracks(tracks)
                }

                if (isBound) {
                    musicService?.setPlaylist(allTracks, 0)
                }

            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка загрузки музыки", e)
                showEmptyView(true)
                Toast.makeText(
                    this@MainActivity,
                    "Ошибка загрузки: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showEmptyView(show: Boolean) {
        if (show) {
            emptyView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun filterTracks(query: String? = null) {
        var filtered = allTracks

        if (isShowingFavorites) {
            filtered = filtered.filter { it.isFavorite }
        }

        if (!query.isNullOrBlank()) {
            filtered = filtered.filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.artist.contains(query, ignoreCase = true)
            }
        }

        trackAdapter.updateTracks(filtered)
        showEmptyView(filtered.isEmpty())
    }

    private fun showAddToPlaylistDialog(track: Track) {
        lifecycleScope.launch {
            val playlists = database.playlistDao().getAllPlaylists().first()

            if (playlists.isEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Нет плейлистов")
                    .setMessage("Сначала создайте плейлист")
                    .setPositiveButton("Создать") { _, _ ->
                        startActivity(Intent(this@MainActivity, PlaylistActivity::class.java))
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
                return@launch
            }

            val playlistNames = playlists.map { it.name }.toTypedArray()

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Добавить в плейлист")
                .setItems(playlistNames) { _, which ->
                    val playlist = playlists[which]
                    addTrackToPlaylist(playlist.id, track.id)
                }
                .setNeutralButton("Создать новый") { _, _ ->
                    showCreatePlaylistDialog(track)
                }
                .show()
        }
    }

    private fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        lifecycleScope.launch {
            val count = database.playlistDao().getTrackCount(playlistId)
            database.playlistDao().addTrackToPlaylist(playlistId, trackId, count)
            Toast.makeText(this@MainActivity, "Добавлено в плейлист", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCreatePlaylistDialog(track: Track) {
        val input = android.widget.EditText(this)

        AlertDialog.Builder(this)
            .setTitle("Создать плейлист")
            .setMessage("Введите название")
            .setView(input)
            .setPositiveButton("Создать") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    createPlaylistAndAddTrack(name, track)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun createPlaylistAndAddTrack(name: String, track: Track) {
        lifecycleScope.launch {
            val playlist = Playlist(name = name)
            val playlistId = database.playlistDao().insert(playlist)
            addTrackToPlaylist(playlistId, track.id)
            Toast.makeText(this@MainActivity, "Плейлист создан и трек добавлен", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private fun showFullTextDialog(mainActivity: MainActivity, title: String, content: String) {
            AlertDialog.Builder(mainActivity)
                .setTitle(title)
                .setMessage(content)
                .setPositiveButton("OK", null)
                .show()
        }
    }
}