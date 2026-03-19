package com.example.minimalistplayer.data

import android.net.Uri

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String,
    val albumArtUri: Uri?,
    var isFavorite: Boolean = false
) {
    fun getFormattedDuration(): String {
        val seconds = (duration / 1000) % 60
        val minutes = (duration / (1000 * 60)) % 60
        val hours = (duration / (1000 * 60 * 60))

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    fun toFavoriteTrack(): FavoriteTrack {
        return FavoriteTrack(
            trackId = id,
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            path = path,
            albumArtUri = albumArtUri?.toString()
        )
    }
}