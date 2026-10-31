package com.americankimchilabs.whitebox.vehicle.pleos

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.OnServerConnectionListener
import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import com.americankimchilabs.whitebox.core.SpeakResult
import com.americankimchilabs.whitebox.core.SpeechOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.Locale

private const val TAG = "WhiteBox/Tts"

/** The speech service has not initialised by then: treat as unavailable (the platform fallback speaks instead). */
private const val INIT_TIMEOUT_MS = 5_000L

/** No onStart this long after speak(): the service dropped the request. The first line loads the model (≈ 0.7 s). */
private const val START_TIMEOUT_MS = 4_000L

/** Longest line we speak, with margin; a line that never reports its end is stopped. */
private const val LINE_TIMEOUT_MS = 20_000L

/**
 * Pleos TextToSpeech in HYBRID mode: the platform speech service's on-device voice, and its server voice once the
 * app is registered with Pleos ([PleosCredentials]); without credentials HYBRID falls back to on-device by itself.
 *
 * The SDK object lives as long as this adapter (the service drops callbacks once its client is gone), so the
 * recording service owns one. A failed initialisation is retried on the next line. The service plays as
 * USAGE_ASSISTANT and does not take audio focus itself: the caller holds focus around each line.
 */
class PleosSpeechOutput(context: Context, private val credentials: PleosCredentials?) : SpeechOutput, Closeable {

    private val app = context.applicationContext
    private val one = Mutex()
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private var failed = false
    @Volatile private var line: Line? = null

    private class Line {
        val started = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<SpeakResult>()
    }

    private val events = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() {
            line?.started?.complete(Unit)
        }
        override fun onDone() {
            line?.ended?.complete(SpeakResult.DONE)
        }
        override fun onStop() {
            line?.ended?.complete(SpeakResult.CUT_OFF)
        }
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "line failed: $errorType")
            line?.ended?.complete(SpeakResult.CUT_OFF)
        }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private fun connect(): CompletableDeferred<Boolean> = synchronized(this) {
        ready?.takeIf { !failed }?.let { return it }
        failed = false
        tts?.runCatching { release() }
        val deferred = CompletableDeferred<Boolean>()
        val t = TextToSpeech(app, Mode.HYBRID)
        t.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                t.setLocale(Locale.KOREA)
                t.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                t.addEventListener(events)
                credentials?.let { c ->
                    t.registerApp(c.clientId, c.clientSecret, object : OnServerConnectionListener {
                        override fun onConnected() {
                            Log.i(TAG, "server voice registered")
                        }
                        override fun onFailed(errorType: ErrorType) {
                            Log.w(TAG, "server voice registration failed: $errorType")
                        }
                    })
                }
                deferred.complete(true)
            }

            override fun onFailure(errorType: ErrorType) {
                Log.w(TAG, "voice engine unavailable: $errorType")
                synchronized(this@PleosSpeechOutput) { failed = true }
                deferred.complete(false)
            }
        })
        tts = t
        ready = deferred
        deferred
    }

    override suspend fun speak(text: String): SpeakResult = one.withLock {
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { connect().await() } != true) return SpeakResult.UNAVAILABLE
        val t = tts ?: return SpeakResult.UNAVAILABLE
        val l = Line().also { line = it }
        try {
            t.speak(text, true)
            if (withTimeoutOrNull(START_TIMEOUT_MS) { l.started.await() } == null && !l.ended.isCompleted) {
                Log.w(TAG, "line never started; re-initialising")
                synchronized(this) { failed = true }
                return SpeakResult.UNAVAILABLE
            }
            withTimeoutOrNull(LINE_TIMEOUT_MS) { l.ended.await() } ?: SpeakResult.CUT_OFF.also { t.stop() }
        } finally {
            line = null
        }
    }

    override fun stop() {
        tts?.runCatching { stop() }
    }

    override fun close() {
        tts?.runCatching { release() }
        tts = null
    }
}

/** Pleos My Project credentials for the server voice and recognition; absent until Pleos opens app registration. */
class PleosCredentials(val clientId: String, val clientSecret: String)
