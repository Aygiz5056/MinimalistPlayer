package com.example.minimalistplayer.ui.artist

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.minimalistplayer.data.MusicRepository
import com.example.minimalistplayer.data.Track
import com.example.minimalistplayer.databinding.ActivityArtistTracksBinding
import com.example.minimalistplayer.service.MusicService
import com.example.minimalistplayer.ui.main.TrackAdapter
import com.example.minimalistplayer.ui.nowplaying.NowPlayingActivity
import kotlinx.coroutines.launch

class ArtistTracksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArtistTracksBinding
    private lateinit var trackAdapter: TrackAdapter
    private lateinit var musicRepository: MusicRepository

    private var artistName: String = ""
    private var tracks: List<Track> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArtistTracksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        artistName = intent.getStringExtra("artist_name") ?: "Неизвестный исполнитель"

        musicRepository = MusicRepository(this)

        setupToolbar()
        setupRecyclerView()
        loadTracks()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = artistName

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        trackAdapter = TrackAdapter(
            tracks = emptyList(),
            onItemClick = { track ->
                val intent = Intent(this, NowPlayingActivity::class.java)
                intent.putExtra("track_id", track.id)
                intent.putExtra("track_title", track.title)
                intent.putExtra("track_artist", track.artist)
                startActivity(intent)
            },
            onFavoriteClick = { track, position ->
                // TODO: реализовать позже
                Toast.makeText(this, "Избранное", Toast.LENGTH_SHORT).show()
            },
            onItemLongClick = { track ->
                Toast.makeText(this, "Долгое нажатие: ${track.title}", Toast.LENGTH_SHORT).show()
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = trackAdapter
    }

    private fun loadTracks() {
        lifecycleScope.launch {
            val allTracks = musicRepository.getAllTracks()
            tracks = allTracks.filter { it.artist == artistName }
            trackAdapter.updateTracks(tracks)

            binding.tvTrackCount.text = "${tracks.size} треков"
        }
    }
}