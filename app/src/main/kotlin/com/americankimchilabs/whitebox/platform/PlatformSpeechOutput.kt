package com.americankimchilabs.whitebox.platform

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.americankimchilabs.whitebox.core.SpeakResult
import com.americankimchilabs.whitebox.core.SpeechOutput
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

private const val TAG = "WhiteBox/Speech"

/** Android platform TTS: the fallback behind [SpeechOutput] when the car's own voice engine is unavailable. */
class PlatformSpeechOutput(context: Context) : SpeechOutput, Closeable {

    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CancellableContinuation<SpeakResult>>()
    private val one = Mutex()

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }.apply {
        setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finish(utteranceId, SpeakResult.DONE)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finish(utteranceId, SpeakResult.CUT_OFF)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish(utteranceId, SpeakResult.CUT_OFF)
            override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId, SpeakResult.CUT_OFF)
        })
    }

    override suspend fun speak(text: String): SpeakResult = one.withLock {
        if (!ready.await()) return SpeakResult.UNAVAILABLE
        if (tts.setLanguage(Locale.KOREAN) < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "platform TTS has no Korean voice")
            return SpeakResult.UNAVAILABLE
        }
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            pending[id] = cont
            cont.invokeOnCancellation { pending.remove(id); tts.stop() }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id, SpeakResult.UNAVAILABLE)
        }
    }

    override fun stop() {
        tts.stop()
    }

    override fun close() {
        tts.shutdown()
    }

    private fun finish(utteranceId: String, result: SpeakResult) {
        pending.remove(utteranceId)?.resume(result)
    }
}
