package com.example.minimalistplayer.ui.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.NumberPicker
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.example.minimalistplayer.R
import com.example.minimalistplayer.databinding.DialogSleepTimerBinding
import com.example.minimalistplayer.service.MusicService

class SleepTimerDialog : DialogFragment() {

    private var _binding: DialogSleepTimerBinding? = null
    private val binding get() = _binding!!

    private var musicService: MusicService? = null
    private var timer: CountDownTimer? = null
    private var remainingTime = 0L

    interface SleepTimerListener {
        fun onTimerSet(minutes: Int)
        fun onTimerCancel()
    }

    private var listener: SleepTimerListener? = null

    fun setMusicService(service: MusicService) {
        musicService = service
    }

    fun setListener(listener: SleepTimerListener) {
        this.listener = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogSleepTimerBinding.inflate(layoutInflater)

        // Настраиваем NumberPicker программно
        binding.timePicker.apply {
            minValue = 1
            maxValue = 120
            value = 30
            wrapSelectorWheel = true
        }

        val builder = AlertDialog.Builder(requireActivity())
            .setTitle("Таймер сна")
            .setView(binding.root)
            .setPositiveButton("Установить") { _, _ ->
                val minutes = binding.timePicker.value
                if (minutes > 0) {
                    listener?.onTimerSet(minutes)
                    startTimer(minutes)
                } else {
                    Toast.makeText(requireContext(), "Выберите время", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Отключить") { _, _ ->
                listener?.onTimerCancel()
                cancelTimer()
            }

        return builder.create()
    }

    private fun startTimer(minutes: Int) {
        val millisInFuture = (minutes * 60 * 1000).toLong()

        timer = object : CountDownTimer(millisInFuture, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingTime = millisUntilFinished
                updateTimerText()
            }

            override fun onFinish() {
                musicService?.pause()
                Toast.makeText(requireContext(), "Таймер сна сработал", Toast.LENGTH_LONG).show()
                dismiss()
            }
        }.start()
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }

    private fun updateTimerText() {
        // Можно обновить какой-нибудь TextView, если добавить
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        cancelTimer()
    }

    companion object {
        const val TAG = "SleepTimerDialog"
    }
}