package com.americankimchilabs.whitebox.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import com.americankimchilabs.whitebox.core.AudioFocusPort
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "WhiteBox/Focus"

/** A refused request (not even delayed) is asked again this often while the collector waits. */
private const val RETRY_MS = 1_000L

/**
 * Audio focus for our voice lines, per the head unit's interaction rules: transient focus that lets media duck
 * (GAIN_TRANSIENT_MAY_DUCK, USAGE_ASSISTANT) with delayed grant accepted, so a request made during a call is granted
 * when the call ends. A transient or permanent loss (a call, the car assistant) emits false; a duck request from
 * others is ignored (we keep speaking, quieter). The speech service plays without taking focus itself, so holding
 * it here covers the whole line.
 */
class PlatformAudioFocus(context: Context) : AudioFocusPort {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun hold(): Flow<Boolean> = callbackFlow {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> trySend(true)
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> trySend(false)
                    else -> Unit // LOSS_TRANSIENT_CAN_DUCK: keep going
                }
            }
            .build()
        launch {
            while (isActive) {
                when (val r = audio.requestAudioFocus(request)) {
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> { trySend(true); break }
                    AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> { trySend(false); break } // the GAIN callback follows
                    else -> {
                        Log.i(TAG, "focus refused ($r); retrying")
                        trySend(false)
                        delay(RETRY_MS)
                    }
                }
            }
        }
        awaitClose { audio.abandonAudioFocusRequest(request) }
    }.distinctUntilChanged()
}
