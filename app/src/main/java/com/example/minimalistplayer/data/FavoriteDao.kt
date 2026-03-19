package com.example.minimalistplayer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY addedAt ASC")  // ASC = новые в конце
    fun getAllFavorites(): Flow<List<FavoriteTrack>>

    @Query("SELECT * FROM favorites WHERE trackId = :trackId")
    suspend fun getFavorite(trackId: Long): FavoriteTrack?

    @Insert
    suspend fun insert(favorite: FavoriteTrack)

    @Delete
    suspend fun delete(favorite: FavoriteTrack)

    @Query("DELETE FROM favorites WHERE trackId = :trackId")
    suspend fun deleteByTrackId(trackId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE trackId = :trackId)")
    suspend fun isFavorite(trackId: Long): Boolean
}