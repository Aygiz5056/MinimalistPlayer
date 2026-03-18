package com.example.minimalistplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteTrack(
    @PrimaryKey
    val trackId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String,
    val albumArtUri: String?,
    val addedAt: Long = System.currentTimeMillis()
)