package com.example.minimalistplayer.ui.visualizer

import android.content.Context
import android.graphics.*
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.abs
import kotlin.random.Random

class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "AudioVisualizerView"
        private const val ANIMATION_BATCH_MAX = 10
    }

    private var visualizer: Visualizer? = null
    private var isVisualizerEnabled = false
    private var audioSessionId = 0
    private var isTestMode = false
    private var isPlaying = false  // изменено на false по умолчанию

    // Добавляем свойство isVisualizing
    var isVisualizing: Boolean = false
        set(value) {
            field = value
            isPlaying = value
            if (!value) {
                // При выключении визуализации плавно опускаем полоски
                for (i in srcY.indices) {
                    srcY[i] = destY[i]
                    destY[i] = height.toFloat()
                }
                animationBatchCount = 0
            }
        }

    // Количество баров - фиксированное для красоты
    private val barsCount = 24

    // Данные для визуализации
    private var rawAudioBytes: ByteArray? = null

    // Позиции баров
    private var srcY = FloatArray(barsCount)
    private var destY = FloatArray(barsCount)

    // Для анимации
    private var animationBatchCount = 0
    private val maxBatchCount = ANIMATION_BATCH_MAX

    // Ширина бара
    private var barWidth = -1f

    // Фактор затухания (0-1)
    private var fadeFactor = 1f
    private val fadeSpeed = 0.08f

    // Тёмно-бордовый цвет как в дизайне
    private val barColor = Color.parseColor("#800020") // Бордовый

    // Кисть
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = barColor
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    // Случайный генератор
    private val random = Random

    private val handler = Handler(Looper.getMainLooper())

    // Тестовый режим
    private val testModeRunnable = object : Runnable {
        override fun run() {
            if (!isTestMode || !isPlaying) return

            // Генерируем тестовые данные для красивого эффекта
            val testData = ByteArray(512)
            val time = System.currentTimeMillis() / 300.0
            for (i in testData.indices) {
                val value = (Math.sin(i * 0.1 + time) * 60 +
                        Math.sin(i * 0.3 + time * 2) * 40 +
                        random.nextInt(20) - 10).toInt()
                testData[i] = value.coerceIn(-128, 127).toByte()
            }
            rawAudioBytes = testData

            handler.postDelayed(this, 50)
        }
    }

    init {
        initBars()
        startTestMode()
    }

    private fun initBars() {
        srcY = FloatArray(barsCount)
        destY = FloatArray(barsCount)
    }

    fun setPlaying(playing: Boolean) {
        if (isPlaying == playing) return
        isPlaying = playing
        if (!playing) {
            // При паузе плавно опускаем полоски
            for (i in srcY.indices) {
                srcY[i] = destY[i]
                destY[i] = height.toFloat()
            }
            animationBatchCount = 0
        }
    }

    fun setAudioSessionId(sessionId: Int) {
        Log.d(TAG, "setAudioSessionId: $sessionId")
        audioSessionId = sessionId
        stopTestMode()
        if (sessionId != 0) {
            initVisualizer()
        } else {
            startTestMode()
        }
    }

    private fun startTestMode() {
        if (isTestMode) return

        Log.d(TAG, "Starting test mode")
        isTestMode = true
        isVisualizerEnabled = true
        isPlaying = true
        isVisualizing = true

        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Exception) {
            // Игнорируем
        }
        visualizer = null

        handler.post(testModeRunnable)
    }

    private fun stopTestMode() {
        if (isTestMode) {
            isTestMode = false
            handler.removeCallbacks(testModeRunnable)
        }
    }

    private fun initVisualizer() {
        try {
            stopTestMode()

            visualizer = Visualizer(audioSessionId).apply {
                enabled = false

                val captureSize = Visualizer.getCaptureSizeRange()[1]
                setCaptureSize(captureSize)

                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer,
                            waveform: ByteArray,
                            samplingRate: Int
                        ) {
                            if (isPlaying) {
                                rawAudioBytes = waveform
                            }
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer,
                            fft: ByteArray,
                            samplingRate: Int
                        ) {
                            // Не используем
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    true,
                    false
                )

                enabled = true
                isVisualizerEnabled = true
                isVisualizing = true
                Log.d(TAG, "Visualizer initialized successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing visualizer", e)
            isVisualizerEnabled = false
            startTestMode()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width == 0 || height == 0) return

        // Обновляем фактор затухания
        val targetFade = if (isPlaying) 1f else 0f
        fadeFactor += (targetFade - fadeFactor) * fadeSpeed

        // Инициализируем ширину бара при первом рисовании
        if (barWidth == -1f) {
            barWidth = width.toFloat() / barsCount
            paint.strokeWidth = barWidth * 0.4f // Тонкие элегантные линии

            // Инициализируем позиции баров
            for (i in srcY.indices) {
                val posY = height.toFloat()
                srcY[i] = posY
                destY[i] = posY
            }
        }

        // Обновляем позиции баров если есть данные
        if (isVisualizerEnabled && rawAudioBytes != null && isPlaying) {
            if (animationBatchCount == 0) {
                // Сохраняем текущие позиции
                for (i in srcY.indices) {
                    srcY[i] = destY[i]
                }

                // Рассчитываем новые позиции
                for (i in destY.indices) {
                    val index = (i * rawAudioBytes!!.size / barsCount).coerceIn(0, rawAudioBytes!!.size - 1)
                    val byteValue = abs(rawAudioBytes!![index].toInt())

                    // Высота бара с красивыми пропорциями
                    val barHeight = (byteValue * height / 256f).coerceIn(height * 0.1f, height * 0.9f)

                    destY[i] = height - barHeight
                }

                // Добавляем случайные пики для живости
                if (random.nextInt(100) > 80) {
                    destY[random.nextInt(destY.size)] = height * 0.15f
                }
            }

            animationBatchCount++
        } else if (!isPlaying || fadeFactor < 0.1f) {
            // При паузе линии плавно опускаются
            val targetY = height.toFloat()

            if (animationBatchCount == 0) {
                for (i in srcY.indices) {
                    srcY[i] = destY[i]
                    destY[i] = targetY
                }
            }
            animationBatchCount++
        }

        // Рисуем бары
        for (i in srcY.indices) {
            // Интерполируем позицию между src и dest
            val progress = (animationBatchCount.toFloat() / maxBatchCount).coerceIn(0f, 1f)
            val currentY = srcY[i] + (destY[i] - srcY[i]) * progress

            // Применяем фактор затухания
            val fadeY = currentY + (height.toFloat() - currentY) * (1f - fadeFactor)

            // Позиция по X
            val x = i * barWidth + barWidth / 2f

            // Рисуем линию
            canvas.drawLine(
                x, height.toFloat(),
                x, fadeY.coerceIn(0f, height.toFloat()),
                paint
            )
        }

        // Сбрасываем счетчик анимации
        if (animationBatchCount >= maxBatchCount) {
            animationBatchCount = 0
        }

        // Продолжаем перерисовку
        invalidate()
    }

    fun release() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Exception) {
            // Игнорируем
        }
        visualizer = null
        isVisualizerEnabled = false
        isTestMode = false
        handler.removeCallbacks(testModeRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }
}