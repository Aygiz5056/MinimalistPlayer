package com.example.minimalistplayer.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.minimalistplayer.service.MusicService
import java.util.Locale

class VoiceController(
    private val context: Context,
    private val musicService: MusicService?
) {

    private var isListening = false
    private var speechRecognizer: SpeechRecognizer? = null

    fun startListening() {
        if (!checkPermission()) {
            Toast.makeText(context, "Нет разрешения на запись аудио", Toast.LENGTH_SHORT).show()
            return
        }

        if (isListening) {
            stopListening()
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    Toast.makeText(context, "Слушаю...", Toast.LENGTH_SHORT).show()
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Не распознано"
                        SpeechRecognizer.ERROR_NETWORK -> "Ошибка сети"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Таймаут сети"
                        SpeechRecognizer.ERROR_AUDIO -> "Ошибка аудио"
                        SpeechRecognizer.ERROR_SERVER -> "Ошибка сервера"
                        SpeechRecognizer.ERROR_CLIENT -> "Ошибка клиента"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Таймаут речи"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознаватель занят"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешений"
                        else -> "Ошибка $error"
                    }
                    Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        processCommand(matches[0])
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processCommand(command: String) {
        val lowerCommand = command.lowercase(Locale.getDefault())

        when {
            lowerCommand.contains("плей") || lowerCommand.contains("play") ||
                    lowerCommand.contains("воспроиз") -> {
                musicService?.play()
                Toast.makeText(context, "Воспроизведение", Toast.LENGTH_SHORT).show()
            }
            lowerCommand.contains("пауза") || lowerCommand.contains("pause") ||
                    lowerCommand.contains("стоп") || lowerCommand.contains("stop") -> {
                musicService?.pause()
                Toast.makeText(context, "Пауза", Toast.LENGTH_SHORT).show()
            }
            lowerCommand.contains("следующий") || lowerCommand.contains("next") ||
                    lowerCommand.contains("дальше") || lowerCommand.contains("след") -> {
                musicService?.playNext()
                Toast.makeText(context, "Следующий трек", Toast.LENGTH_SHORT).show()
            }
            lowerCommand.contains("предыдущий") || lowerCommand.contains("previous") ||
                    lowerCommand.contains("назад") || lowerCommand.contains("пред") -> {
                musicService?.playPrevious()
                Toast.makeText(context, "Предыдущий трек", Toast.LENGTH_SHORT).show()
            }
            lowerCommand.contains("перемешай") || lowerCommand.contains("shuffle") -> {
                Toast.makeText(context, "Перемешивание", Toast.LENGTH_SHORT).show()
            }
            lowerCommand.contains("повтор") || lowerCommand.contains("repeat") -> {
                Toast.makeText(context, "Повтор", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Toast.makeText(context, "Неизвестная команда: $command", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListening = false
    }

    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun destroy() {
        stopListening()
    }
}