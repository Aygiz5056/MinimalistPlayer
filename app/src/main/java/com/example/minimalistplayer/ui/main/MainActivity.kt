package com.example.minimalistplayer.ui.main

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
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
import com.example.minimalistplayer.ui.nowplaying.NowPlayingActivity
import com.example.minimalistplayer.ui.playlist.PlaylistActivity
import com.example.minimalistplayer.ui.settings.SettingsActivity
import com.example.minimalistplayer.ui.dialog.SleepTimerDialog
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // Прямые ссылки на View
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var menuButton: ImageButton
    private lateinit var favoritesButton: ImageButton
    private lateinit var searchView: SearchView
    private lateinit var appBarLayout: View
    private lateinit var backgroundAlbumArt: ImageView
    private lateinit var backgroundOverlay: View
    private lateinit var loadingProgress: ProgressBar

    // Мини-плеер
    private var miniPlayerCard: View? = null
    private var trackInfoContainer: View? = null
    private lateinit var miniPlayerTrackTitle: TextView
    private lateinit var currentTrackArtist: TextView
    private lateinit var miniPlayerProgressBar: SeekBar
    private lateinit var miniPlayerPlayPause: ImageButton
    private lateinit var miniPlayerPrev: ImageButton
    private lateinit var miniPlayerNext: ImageButton

    private lateinit var trackAdapter: TrackAdapter
    private lateinit var musicRepository: MusicRepository
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var database: FavoriteDatabase

    private var allTracks = listOf<Track>()
    private var isShowingFavorites = false
    private var shouldOpenDrawer = false
    private var lastClickTime = 0L

    // Для связи с сервисом
    private var musicService: MusicService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true

            // Устанавливаем слушатель изменений
            musicService?.setOnTrackChangeListener(object : MusicService.OnTrackChangeListener {
                override fun onTrackChanged(track: Track) {
                    runOnUiThread {
                        updateMiniPlayer(track)
                    }
                }

                override fun onPlayStateChanged(isPlaying: Boolean) {
                    runOnUiThread {
                        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        miniPlayerPlayPause.setImageResource(icon)
                    }
                }
            })

            // Проверяем, инициализирован ли уже сервис
            if (musicService?.isInitialized() == true) {
                Log.d(TAG, "Service already initialized, restoring UI only")
                updateMiniPlayerFromService()
            } else if (allTracks.isNotEmpty()) {
                // Сервис не инициализирован - восстанавливаем состояние
                Log.d(TAG, "Service not initialized, restoring state")
                musicService?.restorePlaybackState(allTracks)
            }

            updateMiniPlayerFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            musicService = null
            isBound = false
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

    private val favoriteUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "FAVORITE_UPDATED") {
                Log.d(TAG, "Favorite updated, refreshing list")
                if (isShowingFavorites) {
                    filterTracks()
                }
            }
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

        if (intent.getBooleanExtra("open_drawer", false)) {
            shouldOpenDrawer = true
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
        appBarLayout = findViewById(R.id.appBarLayout)
        backgroundAlbumArt = findViewById(R.id.backgroundAlbumArt)
        backgroundOverlay = findViewById(R.id.backgroundOverlay)
        loadingProgress = findViewById(R.id.loadingProgress)

        // Мини-плеер элементы
        miniPlayerCard = findViewById(R.id.miniPlayerCard)
        trackInfoContainer = findViewById(R.id.trackInfoContainer)
        miniPlayerTrackTitle = findViewById(R.id.currentTrackTitle)
        currentTrackArtist = findViewById(R.id.currentTrackArtist)
        miniPlayerProgressBar = findViewById(R.id.progressBar)
        miniPlayerPlayPause = findViewById(R.id.playPauseButton)
        miniPlayerPrev = findViewById(R.id.prevButton)
        miniPlayerNext = findViewById(R.id.nextButton)
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart called")
        val intent = Intent(this, MusicService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")

        // Для Android 14+ нужно указать флаги
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(favoriteUpdateReceiver, IntentFilter("FAVORITE_UPDATED"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(favoriteUpdateReceiver, IntentFilter("FAVORITE_UPDATED"))
        }

        updateMiniPlayerFromService()

        if (shouldOpenDrawer) {
            drawerLayout.openDrawer(navigationView)
            shouldOpenDrawer = false
        }
    }

    // Обновляем onPause
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")
        try {
            unregisterReceiver(favoriteUpdateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        musicService?.savePlaybackState()
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop called")

        // Сохраняем состояние
        musicService?.savePlaybackState()

        // Не отключаемся от сервиса, если музыка играет
        if (musicService?.isPlaying == false) {
            if (isBound) {
                unbindService(connection)
                isBound = false
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("open_drawer", false) == true) {
            shouldOpenDrawer = true
        }
    }

    private fun setupUI() {
        trackAdapter = TrackAdapter(
            tracks = emptyList(),
            onItemClick = { track ->
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastClickTime < 500) return@TrackAdapter
                lastClickTime = currentTime

                Log.d(TAG, "Track clicked: ${track.title}")

                if (!isBound || musicService == null) {
                    Toast.makeText(this, "Плеер не готов", Toast.LENGTH_SHORT).show()
                    return@TrackAdapter
                }

                try {
                    val index = allTracks.indexOf(track)
                    musicService?.let { service ->
                        service.setPlaylist(allTracks, index)
                        service.play()
                        updateMiniPlayer(track)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error playing track", e)
                }
            },
            onFavoriteClick = { track, position ->
                lifecycleScope.launch {
                    try {
                        if (track.isFavorite) {
                            musicRepository.addToFavorites(track)
                            Toast.makeText(this@MainActivity, "❤️ Добавлено в избранное", Toast.LENGTH_SHORT).show()
                        } else {
                            musicRepository.removeFromFavorites(track)
                            Toast.makeText(this@MainActivity, "♡ Удалено из избранного", Toast.LENGTH_SHORT).show()
                        }

                        // Обновляем трек в списке
                        trackAdapter.updateTrack(track, position)

                        // Если мы в режиме показа избранного, обновляем список с правильной сортировкой
                        if (isShowingFavorites) {
                            filterTracks()
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving favorite", e)
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
            filterTracks() // Вызываем фильтр с текущим поисковым запросом
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
        // Клик по карточке плеера открывает экран песни
        miniPlayerCard?.setOnClickListener {
            openNowPlaying()
        }

        trackInfoContainer?.setOnClickListener {
            openNowPlaying()
        }

        miniPlayerPlayPause.setOnClickListener {
            musicService?.let { service ->
                if (service.isPlaying) {
                    service.pause()
                } else {
                    service.play()
                }
            }
        }

        miniPlayerPrev.setOnClickListener {
            musicService?.playPrevious()
        }

        miniPlayerNext.setOnClickListener {
            musicService?.playNext()
        }

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
                                    miniPlayerProgressBar.progress = progress
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

    private fun updateMiniPlayer(track: Track) {
        miniPlayerTrackTitle.text = track.title
        currentTrackArtist.text = track.artist
        miniPlayerPlayPause.setImageResource(if (musicService?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
        updateBackgroundAlbumArt(track)
    }

    private fun updateMiniPlayerFromService() {
        musicService?.getCurrentTrack()?.let { track ->
            miniPlayerTrackTitle.text = track.title
            currentTrackArtist.text = track.artist
            miniPlayerPlayPause.setImageResource(
                if (musicService?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
            )
            updateBackgroundAlbumArt(track)

            // Логируем текущие режимы для отладки
            Log.d(TAG, "Current modes - Repeat: ${musicService?.repeatMode}, Shuffle: ${musicService?.shuffleMode}")
        }
    }

    private fun updateBackgroundAlbumArt(track: Track?) {
        if (track == null || track.albumArtUri == null) {
            backgroundAlbumArt.visibility = View.GONE
            backgroundOverlay.visibility = View.GONE
            return
        }

        Glide.with(this)
            .load(track.albumArtUri)
            .centerCrop()
            .into(backgroundAlbumArt)

        backgroundAlbumArt.visibility = View.VISIBLE
        backgroundOverlay.visibility = View.VISIBLE
    }

    private fun setupDrawer() {
        navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.nav_playlists -> startActivity(Intent(this, PlaylistActivity::class.java))
                R.id.nav_donate -> openDonateLink()
                R.id.nav_timer -> showTimerDialog()
            }
            drawerLayout.closeDrawers()
            true
        }
    }

    private fun openDonateLink() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.donationalerts.com/r/shedanhoolderz")))
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTimerDialog() {
        if (isBound && musicService != null) {
            val timerDialog = SleepTimerDialog()
            timerDialog.setMusicService(musicService!!)
            timerDialog.setListener(object : SleepTimerDialog.SleepTimerListener {
                override fun onTimerSet(minutes: Int) {
                    Toast.makeText(this@MainActivity, "Таймер на $minutes мин", Toast.LENGTH_SHORT).show()
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

    private fun setupGestures() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (drawerLayout.isDrawerOpen(navigationView)) return false

                if (abs(velocityX) > abs(velocityY)) {
                    if (velocityX < -500) {
                        openNowPlaying()
                        return true
                    } else if (velocityX > 500) {
                        drawerLayout.openDrawer(navigationView)
                        return true
                    }
                }
                return false
            }
        })

        recyclerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun checkPermissions() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadMusic()
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun loadMusic() {
        lifecycleScope.launch {
            try {
                showLoading(true)
                allTracks = musicRepository.getAllTracks()

                if (allTracks.isEmpty()) {
                    showEmptyView(true)
                    allTracks = musicRepository.getMockTracks()
                } else {
                    showEmptyView(false)
                }

                trackAdapter.updateTracks(allTracks)

                // Устанавливаем плейлист ТОЛЬКО если сервис еще не инициализирован
                if (isBound && musicService != null && musicService?.isInitialized() == false) {
                    Log.d(TAG, "Setting playlist from loadMusic")
                    musicService?.restorePlaybackState(allTracks)
                }

                showLoading(false)

            } catch (e: Exception) {
                Log.e(TAG, "Error loading music", e)
                showEmptyView(true)
                showLoading(false)
            }
        }
    }

    /**
     * Показывает или скрывает пустой экран
     */
    private fun showEmptyView(show: Boolean) {
        emptyView.visibility = if (show) View.VISIBLE else View.GONE
        recyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun showLoading(show: Boolean) {
        loadingProgress.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * Фильтрует список треков по избранному и поисковому запросу
     */
    private fun filterTracks(query: String? = null) {
        // Режим показа избранного
        if (isShowingFavorites) {
            lifecycleScope.launch {
                try {
                    // Получаем упорядоченный список ID избранных треков
                    val favoriteIds = musicRepository.getFavoriteIdsOrdered()

                    // Сортируем все треки в том же порядке, что и favoriteIds
                    val sortedFavorites = favoriteIds.mapNotNull { id ->
                        allTracks.find { it.id == id }
                    }

                    var filtered = sortedFavorites

                    // Применяем поиск если есть запрос
                    if (!query.isNullOrBlank()) {
                        filtered = filtered.filter {
                            it.title.contains(query, ignoreCase = true) ||
                                    it.artist.contains(query, ignoreCase = true)
                        }
                    }

                    // Обновляем адаптер
                    trackAdapter.updateTracks(filtered)
                    showEmptyView(filtered.isEmpty())

                    Log.d(TAG, "Filtered favorites: ${filtered.size} tracks")

                } catch (e: Exception) {
                    Log.e(TAG, "Error filtering favorites", e)
                }
            }
            return
        }

        // Обычный режим (не избранное)
        var filtered = allTracks

        // Применяем поиск если есть запрос
        if (!query.isNullOrBlank()) {
            filtered = filtered.filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.artist.contains(query, ignoreCase = true)
            }
        }

        // Обновляем адаптер
        trackAdapter.updateTracks(filtered)
        showEmptyView(filtered.isEmpty())

        Log.d(TAG, "Filtered all tracks: ${filtered.size} tracks")
    }

    private fun openNowPlaying() {
        musicService?.getCurrentTrack()?.let { track ->
            val intent = Intent(this, NowPlayingActivity::class.java).apply {
                putExtra("track_id", track.id)
                putExtra("track_title", track.title)
                putExtra("track_artist", track.artist)
            }
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } ?: Toast.makeText(this, "Нет текущего трека", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this@MainActivity, "Плейлист создан", Toast.LENGTH_SHORT).show()
        }
    }
}