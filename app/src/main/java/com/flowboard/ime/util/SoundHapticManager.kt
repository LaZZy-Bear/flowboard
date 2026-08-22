package com.flowboard.ime.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.flowboard.ime.R

class SoundHapticManager(private val context: Context) {

    var isSoundEnabled: Boolean = false
    var isVibrationEnabled: Boolean = false

    private var soundPool: SoundPool? = null
    private var soundTapId: Int = 0
    private var soundSwipeId: Int = 0

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    init {
        initSoundPool()
    }

    private fun initSoundPool() {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            soundPool = SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(audioAttributes)
                .build()

            soundPool?.let { pool ->
                soundTapId = pool.load(context, R.raw.sound_tap, 1)
                soundSwipeId = pool.load(context, R.raw.sound_swipe, 1)
            }
        } catch (_: Exception) {
            soundPool = null
        }
    }

    fun playTap() {
        if (isSoundEnabled) {
            if (soundTapId != 0 && soundPool != null) {
                soundPool?.play(soundTapId, 0.25f, 0.25f, 1, 0, 1.0f)
            } else {
                audioManager?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
            }
        }
        if (isVibrationEnabled) {
            performTapVibration()
        }
    }

    fun playSwipe() {
        if (isSoundEnabled) {
            if (soundSwipeId != 0 && soundPool != null) {
                soundPool?.play(soundSwipeId, 0.25f, 0.25f, 1, 0, 1.0f)
            } else {
                audioManager?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
            }
        }
        if (isVibrationEnabled) {
            performSwipeVibration()
        }
    }

    fun playSpace() {
        playTap()
    }

    fun playDelete() {
        playTap()
    }

    fun playReturn() {
        playTap()
    }

    private fun performTapVibration() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(12)
            }
        } catch (_: Exception) {}
    }

    private fun performSwipeVibration() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 10, 12, 18)
                val amplitudes = intArrayOf(0, 120, 0, 200)
                vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(25)
            }
        } catch (_: Exception) {}
    }

    fun release() {
        soundPool?.release()
        soundPool = null
    }
}
