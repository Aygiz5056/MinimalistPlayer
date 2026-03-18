package com.example.minimalistplayer.ui.settings

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.example.minimalistplayer.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    companion object {
        const val PREF_NAME = "player_settings"
        const val KEY_VOICE_CONTROL = "voice_control"
        const val KEY_CROSSFADE = "crossfade"
        const val KEY_REPEAT_MODE = "repeat_mode"
        const val KEY_SHUFFLE_MODE = "shuffle_mode"
        const val KEY_DARK_THEME = "dark_theme"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        setupToolbar()
        loadSettings()
        setupListeners()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Настройки"

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun loadSettings() {
        // Голосовое управление
        binding.switchVoiceControl.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_VOICE_CONTROL, isChecked).apply()

            if (isChecked) {
                // Запрашиваем разрешение на запись аудио
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        // Кроссфейд
        val crossfadeValue = prefs.getInt(KEY_CROSSFADE, 0)
        updateCrossfadeText(crossfadeValue)

        // Режимы
        binding.switchRepeat.isChecked = prefs.getBoolean(KEY_REPEAT_MODE, false)
        binding.switchShuffle.isChecked = prefs.getBoolean(KEY_SHUFFLE_MODE, false)

        // Тема
        binding.switchDarkTheme.isChecked = prefs.getBoolean(KEY_DARK_THEME, true)
    }

    // Добавь обработчик разрешений
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Голосовое управление включено", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Нужно разрешение на запись аудио", Toast.LENGTH_LONG).show()
            binding.switchVoiceControl.isChecked = false
        }
    }

    private fun setupListeners() {
        binding.switchVoiceControl.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_VOICE_CONTROL, isChecked).apply()
            Toast.makeText(this,
                if (isChecked) "Голосовое управление включено" else "Голосовое управление отключено",
                Toast.LENGTH_SHORT).show()
        }

        binding.layoutCrossfade.setOnClickListener {
            val current = prefs.getInt(KEY_CROSSFADE, 0)
            val newValue = when (current) {
                0 -> 3
                3 -> 5
                5 -> 8
                8 -> 0
                else -> 0
            }
            prefs.edit().putInt(KEY_CROSSFADE, newValue).apply()
            updateCrossfadeText(newValue)
        }

        binding.switchRepeat.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_REPEAT_MODE, isChecked).apply()
        }

        binding.switchShuffle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SHUFFLE_MODE, isChecked).apply()
        }

        binding.switchDarkTheme.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_DARK_THEME, isChecked).apply()
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
    }

    private fun updateCrossfadeText(value: Int) {
        binding.tvCrossfadeValue.text = when (value) {
            0 -> "Выкл"
            3 -> "3 сек"
            5 -> "5 сек"
            8 -> "8 сек"
            else -> "Выкл"
        }
    }
}