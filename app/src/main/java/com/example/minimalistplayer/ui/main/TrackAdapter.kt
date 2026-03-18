package com.example.minimalistplayer.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.minimalistplayer.R
import com.example.minimalistplayer.data.Track
import com.example.minimalistplayer.databinding.ItemTrackBinding

class TrackAdapter(
    private var tracks: List<Track>,
    private val onItemClick: (Track) -> Unit,
    private val onFavoriteClick: (Track, Int) -> Unit,
    private val onItemLongClick: (Track) -> Unit
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    fun updateTracks(newTracks: List<Track>) {
        tracks = newTracks
        notifyDataSetChanged()
    }

    fun updateTrack(track: Track, position: Int) {
        if (position in 0 until tracks.size) {
            tracks = tracks.toMutableList().apply {
                set(position, track)
            }
            notifyItemChanged(position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val binding = ItemTrackBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TrackViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(tracks[position], position)
    }

    override fun getItemCount() = tracks.size

    inner class TrackViewHolder(
        private val binding: ItemTrackBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(track: Track, position: Int) {
            binding.apply {
                trackTitle.text = track.title
                trackArtist.text = track.artist

                val favoriteIcon = if (track.isFavorite) {
                    R.drawable.ic_favorite
                } else {
                    R.drawable.ic_favorite_border
                }
                favoriteButton.setImageResource(favoriteIcon)

                // Клик по всей карточке
                root.setOnClickListener {
                    onItemClick(track)
                }

                // Долгое нажатие на всю карточку
                root.setOnLongClickListener {
                    onItemLongClick(track)
                    true
                }

                // Клик по контейнеру с информацией (на случай если root не сработает)
                trackInfoContainer.setOnClickListener {
                    onItemClick(track)
                }

                // Клик по кнопке избранного (не должен перехватывать события карточки)
                favoriteButton.setOnClickListener {
                    track.isFavorite = !track.isFavorite
                    onFavoriteClick(track, position)
                }
            }
        }
    }
}