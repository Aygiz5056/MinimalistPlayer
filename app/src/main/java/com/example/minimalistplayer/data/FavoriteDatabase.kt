package com.example.minimalistplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FavoriteTrack::class,
        Playlist::class,           // Добавляем Playlist
        PlaylistTrack::class       // Добавляем PlaylistTrack
    ],
    version = 2,  // Увеличиваем версию!
    exportSchema = false
)
abstract class FavoriteDatabase : RoomDatabase() {

    abstract fun favoriteDao(): FavoriteDao
    abstract fun playlistDao(): PlaylistDao  // Добавляем этот метод

    companion object {
        @Volatile
        private var INSTANCE: FavoriteDatabase? = null

        fun getInstance(context: Context): FavoriteDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FavoriteDatabase::class.java,
                    "favorite_database"
                )
                    .fallbackToDestructiveMigration()  // Важно для обновления версии!
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}