package com.example.minimalistplayer.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.minimalistplayer.R
import com.example.minimalistplayer.service.MusicService
import com.example.minimalistplayer.ui.main.MainActivity

class PlayerWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_PLAY_PAUSE = "com.example.minimalistplayer.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.minimalistplayer.ACTION_NEXT"
        const val ACTION_PREV = "com.example.minimalistplayer.ACTION_PREV"
        const val ACTION_FAVORITE = "com.example.minimalistplayer.ACTION_FAVORITE"
        const val ACTION_OPEN_APP = "com.example.minimalistplayer.ACTION_OPEN_APP"

        fun updateWidget(context: Context, trackTitle: String, trackArtist: String, isPlaying: Boolean, isFavorite: Boolean) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, PlayerWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId, trackTitle, trackArtist, isPlaying, isFavorite)
            }
        }

        private fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            trackTitle: String,
            trackArtist: String,
            isPlaying: Boolean,
            isFavorite: Boolean
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_player)

            // Устанавливаем текст
            views.setTextViewText(R.id.widget_track_title, trackTitle)
            views.setTextViewText(R.id.widget_track_artist, trackArtist)

            // Устанавливаем иконку play/pause
            val playIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            views.setImageViewResource(R.id.widget_play_pause, playIcon)

            // Устанавливаем иконку избранного
            val favIcon = if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
            views.setImageViewResource(R.id.widget_favorite, favIcon)

            // Создаем PendingIntent для открытия приложения
            val openIntent = Intent(context, MainActivity::class.java)
            val openPendingIntent = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_album_art, openPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_track_title, openPendingIntent)

            // Создаем PendingIntent для play/pause
            val playPauseIntent = Intent(context, PlayerWidgetProvider::class.java).apply {
                action = ACTION_PLAY_PAUSE
            }
            val playPausePendingIntent = PendingIntent.getBroadcast(
                context, 1, playPauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_play_pause, playPausePendingIntent)

            // Создаем PendingIntent для next
            val nextIntent = Intent(context, PlayerWidgetProvider::class.java).apply {
                action = ACTION_NEXT
            }
            val nextPendingIntent = PendingIntent.getBroadcast(
                context, 2, nextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_next, nextPendingIntent)

            // Создаем PendingIntent для prev
            val prevIntent = Intent(context, PlayerWidgetProvider::class.java).apply {
                action = ACTION_PREV
            }
            val prevPendingIntent = PendingIntent.getBroadcast(
                context, 3, prevIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_prev, prevPendingIntent)

            // Создаем PendingIntent для favorite
            val favIntent = Intent(context, PlayerWidgetProvider::class.java).apply {
                action = ACTION_FAVORITE
            }
            val favPendingIntent = PendingIntent.getBroadcast(
                context, 4, favIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_favorite, favPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            // Показываем заглушку, пока сервис не обновит
            updateAppWidget(
                context, appWidgetManager, appWidgetId,
                "Нет трека", "Неизвестно", false, false
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_PLAY_PAUSE -> {
                // Отправляем команду в сервис
                val serviceIntent = Intent(context, MusicService::class.java).apply {
                    action = "PLAY_PAUSE"
                }
                context.startService(serviceIntent)
            }
            ACTION_NEXT -> {
                val serviceIntent = Intent(context, MusicService::class.java).apply {
                    action = "NEXT"
                }
                context.startService(serviceIntent)
            }
            ACTION_PREV -> {
                val serviceIntent = Intent(context, MusicService::class.java).apply {
                    action = "PREV"
                }
                context.startService(serviceIntent)
            }
            ACTION_FAVORITE -> {
                val serviceIntent = Intent(context, MusicService::class.java).apply {
                    action = "FAVORITE"
                }
                context.startService(serviceIntent)
            }
        }
    }
}