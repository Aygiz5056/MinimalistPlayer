package com.example.minimalistplayer.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.collection.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicRepository(private val context: Context) {

    private val database = FavoriteDatabase.getInstance(context)

    // ✅ ТОЛЬКО ОДНО ОБЪЯВЛЕНИЕ КЭША
    private val albumArtCache = LruCache<Long, Uri?>(50)

    suspend fun getAllTracks(): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        val favorites = getFavoriteIds()

        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"
        val selection = "${MediaStore.Audio.Media.DURATION} > ? AND ${MediaStore.Audio.Media.IS_MUSIC} = 1"
        val selectionArgs = arrayOf("30000")

        val query = context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )

        query?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "Unknown"
                val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                val album = cursor.getString(albumColumn) ?: "Unknown Album"
                val duration = cursor.getLong(durationColumn)
                val path = cursor.getString(pathColumn)
                val albumId = cursor.getLong(albumIdColumn)

                val albumArtUri = getAlbumArtUri(albumId)

                val file = java.io.File(path)
                if (file.exists() && duration > 0) {
                    tracks.add(
                        Track(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            duration = duration,
                            path = path,
                            albumArtUri = albumArtUri,
                            isFavorite = id in favorites
                        )
                    )
                }
            }
        }

        if (tracks.isEmpty()) {
            return@withContext getMockTracks()
        }

        return@withContext tracks
    }

    // ✅ ТОЛЬКО ОДИН МЕТОД getAlbumArtUri
    fun getAlbumArtUri(albumId: Long): Uri? {
        return try {
            // Проверяем кэш
            albumArtCache.get(albumId) ?: run {
                val uri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )
                // Проверяем, существует ли файл
                val fileExists = try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    inputStream?.close()
                    true
                } catch (e: Exception) {
                    false
                }

                if (fileExists) {
                    albumArtCache.put(albumId, uri)
                    uri
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Error getting album art URI", e)
            null
        }
    }

    // Временный метод для отладки - можно вызвать где-нибудь
    fun testAlbumArtLoading() {
        GlobalScope.launch {
            val tracks = getAllTracks()
            tracks.forEach { track ->
                if (track.albumArtUri != null) {
                    Log.d("AlbumArt", "Track: ${track.title} - has album art: ${track.albumArtUri}")
                } else {
                    Log.d("AlbumArt", "Track: ${track.title} - NO album art")
                }
            }
        }
    }

    // Получить список ID избранных треков
    private suspend fun getFavoriteIds(): Set<Long> {
        return try {
            val favorites = database.favoriteDao().getAllFavorites()
            val favoriteList = favorites.first()
            favoriteList.map { it.trackId }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    // Добавить в избранное
    suspend fun addToFavorites(track: Track) {
        database.favoriteDao().insert(track.toFavoriteTrack())
    }

    // Удалить из избранного
    suspend fun removeFromFavorites(track: Track) {
        database.favoriteDao().deleteByTrackId(track.id)
    }

    // Проверить, в избранном ли трек
    suspend fun isFavorite(trackId: Long): Boolean {
        return database.favoriteDao().isFavorite(trackId)
    }

    // Получить все избранные треки как Track
    suspend fun getFavoriteTracks(): List<Track> = withContext(Dispatchers.IO) {
        val favorites = database.favoriteDao().getAllFavorites()
        val favoriteList = favorites.first()

        favoriteList.map { favorite ->
            Track(
                id = favorite.trackId,
                title = favorite.title,
                artist = favorite.artist,
                album = favorite.album,
                duration = favorite.duration,
                path = favorite.path,
                albumArtUri = favorite.albumArtUri?.let { Uri.parse(it) },
                isFavorite = true
            )
        }
    }

    // Тестовые треки
    fun getMockTracks(): List<Track> {
        return listOf(
            Track(
                id = 1,
                title = "Тестовый трек 1",
                artist = "Исполнитель 1",
                album = "Альбом 1",
                duration = 210000,
                path = "",
                albumArtUri = null,
                isFavorite = false
            ),
            Track(
                id = 2,
                title = "Тестовый трек 2",
                artist = "Исполнитель 2",
                album = "Альбом 2",
                duration = 185000,
                path = "",
                albumArtUri = null,
                isFavorite = false
            ),
            Track(
                id = 3,
                title = "Тестовый трек 3",
                artist = "Исполнитель 3",
                album = "Альбом 3",
                duration = 245000,
                path = "",
                albumArtUri = null,
                isFavorite = false
            )
        )
    }
}