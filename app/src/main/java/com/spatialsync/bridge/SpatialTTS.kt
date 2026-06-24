//SpatialTTS.kt
package com.spatialsync.bridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class SpatialTTS(private val context: Context) {

    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)

    // Pan values: -1.0 = full left, 0.0 = centre, 1.0 = full right
    private val panMap = mapOf(
        "Top Left"     to -0.85f,
        "Bottom Left"  to -0.85f,
        "Top Right"    to  0.85f,
        "Bottom Right" to  0.85f,
        "Centre"       to  0.0f
    )

    // Volume scale per distance
    private val volumeMap = mapOf(
        "close"  to 1.0f,
        "nearby" to 0.70f,
        "far"    to 0.45f
    )

    // Speech rate per distance — faster when close, normal otherwise
    private val rateMap = mapOf(
        "close"  to 1.15f,
        "nearby" to 1.0f,
        "far"    to 0.9f
    )

    fun init() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ready.set(true)
                Log.d("SpatialTTS", "TTS ready")
            } else {
                Log.e("SpatialTTS", "TTS init failed: $status")
            }
        }
    }
    fun routeToGlasses() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        Log.d("SpatialTTS", "Audio routed to Bluetooth SCO (glasses)")
    }

    fun speak(className: String, quadrant: String, distance: String) {
        if (!ready.get()) return

        val pan   = panMap[quadrant]   ?: 0.0f
        val vol   = volumeMap[distance] ?: 0.7f
        val rate  = rateMap[distance]   ?: 1.0f

        // Left volume = attenuate if panning right, right volume = attenuate if panning left
        val leftVol  = if (pan <= 0f) vol else vol * (1f - pan)
        val rightVol = if (pan >= 0f) vol else vol * (1f + pan)

        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, vol)
            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, pan)
            putFloat(TextToSpeech.Engine.KEY_PARAM_STREAM,
                AudioManager.STREAM_VOICE_CALL.toFloat())
        }

        tts?.setSpeechRate(rate)
        tts?.speak(
            "$className, $distance, $quadrant",
            TextToSpeech.QUEUE_FLUSH,
            params,
            UUID.randomUUID().toString()
        )

        Log.d("SpatialTTS", "Speaking: $className | $quadrant | $distance | pan=$pan vol=$vol rate=$rate")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        ready.set(false)
    }
}