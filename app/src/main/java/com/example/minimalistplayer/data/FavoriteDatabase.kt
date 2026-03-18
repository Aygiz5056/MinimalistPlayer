package com.example.minimalistplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FavoriteTrack::class,
        Playlist::class,           // Добавили
        PlaylistTrack::class       // Добавили
    ],
    version = 2,  // Увеличили версию!
    exportSchema = false
)
abstract class FavoriteDatabase : RoomDatabase() {

    abstract fun favoriteDao(): FavoriteDao
    abstract fun playlistDao(): PlaylistDao  // Добавили

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
                    .fallbackToDestructiveMigration()  // Добавили для миграции
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}