package com.example.minimalistplayer.ui.visualizer

import android.content.Context
import android.graphics.*
import android.media.audiofx.Visualizer
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "AudioVisualizerView"
        private const val BAR_COUNT = 24
        private const val MIN_AMPLITUDE = 0.05f
        private const val SMOOTHING_FACTOR = 0.4f
    }

    // Цвета
    private val barColor = Color.parseColor("#FFFF4081")
    private val barBackgroundColor = Color.parseColor("#33FFFFFF")

    // Кисти
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = barColor
        style = Paint.Style.FILL
    }

    private val barBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = barBackgroundColor
        style = Paint.Style.FILL
    }

    // Массивы амплитуд
    private val amplitudes = FloatArray(BAR_COUNT) { MIN_AMPLITUDE }
    private val targetAmplitudes = FloatArray(BAR_COUNT) { MIN_AMPLITUDE }
    private val barSpeeds = FloatArray(BAR_COUNT) { 0.05f } // Скорость изменения каждой полоски

    // Визуализатор
    private var visualizer: Visualizer? = null
    private var isVisualizerActive = false
    private var audioSessionId = 0

    // Флаг визуализации
    var isVisualizing = false
        set(value) {
            field = value
            if (value) {
                startVisualizer()
            } else {
                stopVisualizer()
                amplitudes.fill(MIN_AMPLITUDE)
                targetAmplitudes.fill(MIN_AMPLITUDE)
                invalidate()
            }
        }

    init {
        startSmoothAnimation()
    }

    private fun startSmoothAnimation() {
        post(object : Runnable {
            override fun run() {
                // Плавно приближаем текущие амплитуды к целевым
                for (i in amplitudes.indices) {
                    // Каждая полоска движется с разной скоростью для эффекта пульсации
                    val speed = barSpeeds[i]
                    amplitudes[i] += (targetAmplitudes[i] - amplitudes[i]) * speed

                    // Добавляем небольшую случайную вариацию
                    if (!isVisualizerActive) {
                        // В тестовом режиме создаем волны
                        val time = System.currentTimeMillis() / 500.0
                        val wave = (sin(time + i * 0.5) * 0.3 + 0.5).toFloat()
                        targetAmplitudes[i] = wave
                    }
                }
                invalidate()
                postDelayed(this, 16)
            }
        })
    }

    fun setAudioSessionId(sessionId: Int) {
        // Теперь управление визуалайзером полностью в MusicService
        // Этот метод можно оставить пустым или для совместимости
        Log.d(TAG, "setAudioSessionId: $sessionId")
    }

    /**
     * Публичный метод для получения данных от Visualizer API
     * Вызывается из NowPlayingActivity при получении данных от сервиса
     */
    fun updateVisualizerData(waveform: ByteArray) {
        if (isVisualizing) {
            processWaveformData(waveform)
        }
    }

    private fun startVisualizer() {
        if (audioSessionId == 0) {
            Log.w(TAG, "Cannot start visualizer: invalid audio session")
            return
        }

        try {
            visualizer = Visualizer(audioSessionId)

            val captureSizeRange = Visualizer.getCaptureSizeRange()
            visualizer?.captureSize = captureSizeRange[1]

            visualizer?.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (isVisualizing && waveform != null) {
                            processWaveformData(waveform)
                        }
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {}
                },
                Visualizer.getMaxCaptureRate() / 2,
                true,
                false
            )

            visualizer?.enabled = true
            isVisualizerActive = true
            Log.d(TAG, "Visualizer started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting visualizer", e)
            isVisualizerActive = false
        }
    }

    private fun stopVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
            isVisualizerActive = false
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping visualizer", e)
        }
    }

    private fun processWaveformData(waveform: ByteArray) {
        val waveformSize = waveform.size
        val samplesPerBar = waveformSize / BAR_COUNT

        // Сначала собираем сырые данные
        val rawAmplitudes = FloatArray(BAR_COUNT) { MIN_AMPLITUDE }

        for (i in 0 until BAR_COUNT) {
            var maxAmplitude = 0
            var sumAmplitude = 0
            var count = 0

            val startIdx = i * samplesPerBar
            val endIdx = (i + 1) * samplesPerBar

            for (j in startIdx until endIdx.coerceAtMost(waveformSize)) {
                val absValue = abs(waveform[j].toInt())
                if (absValue > maxAmplitude) {
                    maxAmplitude = absValue
                }
                sumAmplitude += absValue
                count++
            }

            // Используем комбинацию максимума и среднего для лучшего эффекта
            val avgAmplitude = if (count > 0) sumAmplitude / count else 0
            val combinedAmplitude = (maxAmplitude * 0.7f + avgAmplitude * 0.3f) / 128f

            // Применяем логарифмическую шкалу
            val logAmplitude = (log10(1.0 + 9.0 * combinedAmplitude)).toFloat()

            // Сохраняем сырое значение
            rawAmplitudes[i] = logAmplitude.coerceIn(MIN_AMPLITUDE, 1.0f)
        }

        // Добавляем эффект "бегущей волны" - соседние полоски влияют друг на друга
        for (i in rawAmplitudes.indices) {
            var smoothedValue = rawAmplitudes[i] * 0.5f

            // Влияние соседей
            if (i > 0) smoothedValue += rawAmplitudes[i - 1] * 0.25f
            if (i < rawAmplitudes.size - 1) smoothedValue += rawAmplitudes[i + 1] * 0.25f

            // Обновляем целевую амплитуду
            targetAmplitudes[i] = smoothedValue.coerceIn(MIN_AMPLITUDE, 1.0f)

            // Динамически меняем скорость в зависимости от изменения
            val change = abs(targetAmplitudes[i] - amplitudes[i])
            barSpeeds[i] = (0.3f + change * 0.5f).coerceIn(0.2f, 0.8f)
        }
    }

    // Тестовый метод
    fun updateTestAmplitude(amplitude: Float) {
        if (!isVisualizerActive) {
            // Создаем волнообразный паттерн
            val time = System.currentTimeMillis() / 300.0
            for (i in targetAmplitudes.indices) {
                val freq = (i + 1) * 0.2f
                val value = (sin(time * freq) * 0.5 + 0.5).toFloat()
                targetAmplitudes[i] = value.coerceIn(MIN_AMPLITUDE, 1.0f)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        if (width == 0f || height == 0f) return

        val spacing = width * 0.02f
        val totalSpacing = spacing * (BAR_COUNT - 1)
        val barWidth = (width - totalSpacing) / BAR_COUNT

        for (i in 0 until BAR_COUNT) {
            val left = i * (barWidth + spacing)
            val right = left + barWidth

            // Высота полоски от 15% до 95% высоты
            val barHeight = height * (0.15f + amplitudes[i] * 0.8f)

            // Рисуем фон
            canvas.drawRect(
                left,
                height - 4.dpToPx(),
                right,
                height - 2.dpToPx(),
                barBackgroundPaint
            )

            // Рисуем активную полоску
            canvas.drawRect(
                left,
                height - barHeight,
                right,
                height - 2.dpToPx(),
                barPaint
            )

            // Меняем прозрачность в зависимости от высоты
            barPaint.alpha = (150 + (amplitudes[i] * 105).toInt()).coerceIn(150, 255)
        }

        barPaint.alpha = 255
    }

    private fun Int.dpToPx(): Float {
        return this * resources.displayMetrics.density
    }

    fun release() {
        stopVisualizer()
    }
}