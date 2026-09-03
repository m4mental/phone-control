package com.example.phonecontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.util.Log

/**
 * System-wide BroadcastReceiver for Android's OPEN/CLOSE audio effect control sessions.
 * Allows Phone Control's Studio DSP Engine to immediately latch onto active playback
 * from external media players (Spotify, YouTube Music, local players, etc.) in the background
 * without requiring the user to open the Phone Control UI.
 */
class AudioEffectSessionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AudioEffectReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
        Log.d(TAG, "AudioEffect Broadcast: $action -> Session ID: $sessionId, Package: $pkg")

        if (sessionId > 0) {
            when (action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    StudioDspManager.onAudioSessionOpened(context, sessionId, pkg)
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    StudioDspManager.onAudioSessionClosed(sessionId)
                }
            }
        }
    }
}
