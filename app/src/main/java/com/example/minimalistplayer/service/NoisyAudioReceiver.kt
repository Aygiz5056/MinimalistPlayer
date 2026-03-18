package com.example.minimalistplayer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class NoisyAudioReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NoisyAudioReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
            Log.d(TAG, "Наушники отключены - ставим на паузу")

            // Отправляем команду в сервис
            val serviceIntent = Intent(context, MusicService::class.java).apply {
                action = "PAUSE"
            }
            context.startService(serviceIntent)
        }
    }
}