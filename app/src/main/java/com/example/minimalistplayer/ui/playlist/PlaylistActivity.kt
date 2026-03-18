package com.example.minimalistplayer.ui.playlist

import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.minimalistplayer.data.FavoriteDatabase
import com.example.minimalistplayer.data.Playlist
import com.example.minimalistplayer.databinding.ActivityPlaylistBinding
import kotlinx.coroutines.launch

class PlaylistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistBinding
    private lateinit var database: FavoriteDatabase
    private lateinit var adapter: PlaylistAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = FavoriteDatabase.getInstance(this)

        setupToolbar()
        setupRecyclerView()
        loadPlaylists()
        setupListeners()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Мои плейлисты"

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        adapter = PlaylistAdapter(
            onPlaylistClick = { playlist ->
                // TODO: открыть плейлист
                Toast.makeText(this, "Открыть: ${playlist.name}", Toast.LENGTH_SHORT).show()
            },
            onPlaylistLongClick = { playlist ->
                showPlaylistOptions(playlist)
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun loadPlaylists() {
        lifecycleScope.launch {
            database.playlistDao().getAllPlaylists().collect { playlists ->
                adapter.submitList(playlists)

                if (playlists.isEmpty()) {
                    binding.emptyView.visibility = android.view.View.VISIBLE
                    binding.recyclerView.visibility = android.view.View.GONE
                } else {
                    binding.emptyView.visibility = android.view.View.GONE
                    binding.recyclerView.visibility = android.view.View.VISIBLE
                }
            }
        }
    }

    private fun setupListeners() {
        binding.fabAdd.setOnClickListener {
            showCreatePlaylistDialog()
        }
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(this)

        AlertDialog.Builder(this)
            .setTitle("Создать плейлист")
            .setMessage("Введите название плейлиста")
            .setView(input)
            .setPositiveButton("Создать") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    createPlaylist(name)
                } else {
                    Toast.makeText(this, "Введите название", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun createPlaylist(name: String) {
        lifecycleScope.launch {
            val playlist = Playlist(name = name)
            database.playlistDao().insert(playlist)
            Toast.makeText(this@PlaylistActivity, "Плейлист создан", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPlaylistOptions(playlist: Playlist) {
        val options = arrayOf("Переименовать", "Удалить")

        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(playlist)
                    1 -> deletePlaylist(playlist)
                }
            }
            .show()
    }

    private fun showRenameDialog(playlist: Playlist) {
        val input = EditText(this)
        input.setText(playlist.name)

        AlertDialog.Builder(this)
            .setTitle("Переименовать плейлист")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    renamePlaylist(playlist, newName)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun renamePlaylist(playlist: Playlist, newName: String) {
        lifecycleScope.launch {
            database.playlistDao().update(playlist.copy(name = newName))
            Toast.makeText(this@PlaylistActivity, "Переименовано", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deletePlaylist(playlist: Playlist) {
        AlertDialog.Builder(this)
            .setTitle("Удалить плейлист?")
            .setMessage("Плейлист '${playlist.name}' будет удален")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    database.playlistDao().delete(playlist)
                    Toast.makeText(this@PlaylistActivity, "Плейлист удален", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}