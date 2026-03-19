package com.example.minimalistplayer.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.collection.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class MusicRepository(private val context: Context) {

    private val database = FavoriteDatabase.getInstance(context)
    private val albumArtCache = LruCache<Long, Uri?>(50)

    // Кэш для треков
    private var tracksCache: List<Track>? = null
    private var fastTracksCache: List<Track>? = null
    private var lastCacheTime = 0L
    private val CACHE_DURATION = 60000 // 60 секунд

    /**
     * Получить все треки с полной информацией
     */
    suspend fun getAllTracks(forceRefresh: Boolean = false): List<Track> = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()

        // Возвращаем кэш, если он еще валиден
        if (!forceRefresh && tracksCache != null && (currentTime - lastCacheTime) < CACHE_DURATION) {
            return@withContext tracksCache!!
        }

        val tracks = mutableListOf<Track>()
        val favorites = getFavoriteIdsSet() // Используем Set для быстрой проверки

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

        val selection = "${MediaStore.Audio.Media.DURATION} > ? AND ${MediaStore.Audio.Media.IS_MUSIC} = 1"
        val selectionArgs = arrayOf("30000")

        val query = context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            null // Без сортировки для скорости
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

                tracks.add(
                    Track(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        duration = duration,
                        path = path,
                        albumArtUri = albumArtUri,
                        isFavorite = id in favorites // Быстрая проверка через Set
                    )
                )
            }
        }

        // Сохраняем в кэш
        tracksCache = tracks
        lastCacheTime = currentTime

        return@withContext tracks
    }

    /**
     * Быстрый метод для получения только треков без обложек и альбомов
     */
    suspend fun getTracksFast(): List<Track> = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()

        // Возвращаем быстрый кэш, если он есть
        if (fastTracksCache != null) {
            return@withContext fastTracksCache!!
        }

        val tracks = mutableListOf<Track>()

        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // Минимальная проекция для скорости
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )

        val selection = "${MediaStore.Audio.Media.DURATION} > ? AND ${MediaStore.Audio.Media.IS_MUSIC} = 1"
        val selectionArgs = arrayOf("30000")

        val query = context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            null
        )

        query?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "Unknown"
                val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                val duration = cursor.getLong(durationColumn)
                val path = cursor.getString(pathColumn)

                tracks.add(
                    Track(
                        id = id,
                        title = title,
                        artist = artist,
                        album = "",
                        duration = duration,
                        path = path,
                        albumArtUri = null,
                        isFavorite = false // Временно false, обновится позже
                    )
                )
            }
        }

        fastTracksCache = tracks
        return@withContext tracks
    }

    /**
     * Получить Set ID избранных треков (для быстрой проверки)
     */
    private suspend fun getFavoriteIdsSet(): Set<Long> {
        return try {
            val favorites = database.favoriteDao().getAllFavorites()
            val favoriteList = favorites.first()
            favoriteList.map { it.trackId }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Получить упорядоченный список ID избранных треков (для сортировки)
     */
    suspend fun getFavoriteIdsOrdered(): List<Long> = withContext(Dispatchers.IO) {
        try {
            val favorites = database.favoriteDao().getAllFavorites()
            val favoriteList = favorites.first()
            favoriteList.map { it.trackId }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Получить все избранные треки как Track с правильной сортировкой
     */
    suspend fun getFavoriteTracks(): List<Track> = withContext(Dispatchers.IO) {
        val favorites = database.favoriteDao().getAllFavorites()
        val favoriteList = favorites.first() // Уже отсортировано по addedAt ASC

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

    /**
     * Получить URI обложки альбома с кэшированием
     */
    fun getAlbumArtUri(albumId: Long): Uri? {
        // Сначала проверяем кэш
        val cached = albumArtCache.get(albumId)
        if (cached != null) {
            return cached
        }

        // Если нет в кэше, создаем новый URI
        val uri = try {
            ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                albumId
            )
        } catch (e: Exception) {
            null
        }

        // Сохраняем в кэш только если uri не null
        if (uri != null) {
            albumArtCache.put(albumId, uri)
        }

        return uri
    }

    /**
     * Добавить трек в избранное
     */
    suspend fun addToFavorites(track: Track) {
        database.favoriteDao().insert(track.toFavoriteTrack())
        // Обновляем кэш
        fastTracksCache?.find { it.id == track.id }?.isFavorite = true
        tracksCache?.find { it.id == track.id }?.isFavorite = true
    }

    /**
     * Удалить трек из избранного
     */
    suspend fun removeFromFavorites(track: Track) {
        database.favoriteDao().deleteByTrackId(track.id)
        // Обновляем кэш
        fastTracksCache?.find { it.id == track.id }?.isFavorite = false
        tracksCache?.find { it.id == track.id }?.isFavorite = false
    }

    /**
     * Проверить, в избранном ли трек
     */
    suspend fun isFavorite(trackId: Long): Boolean {
        return database.favoriteDao().isFavorite(trackId)
    }

    /**
     * Очистить кэш (например, при обновлении данных)
     */
    fun clearCache() {
        tracksCache = null
        fastTracksCache = null
        albumArtCache.evictAll()
    }

    /**
     * Тестовые треки для отладки
     */
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