package com.americankimchilabs.whitebox.platform

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.americankimchilabs.whitebox.core.SpeechPort
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

private const val TAG = "WhiteBox/Speech"

/**
 * Android platform TTS behind [SpeechPort]: the fallback behind the same port as the Pleos TextToSpeech SDK.
 * A Pleos adapter replaces this behind the same port when they open.
 */
class PlatformSpeechPort(context: Context) : SpeechPort, Closeable {

    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CancellableContinuation<Unit>>()

    private val tts = TextToSpeech(appContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId)
        })
    }

    override suspend fun speak(text: String) {
        if (!ready.await()) {
            Log.w(TAG, "TTS engine unavailable; dropping: $text")
            return
        }
        val language = tts.setLanguage(Locale.KOREAN)
        if (language < TextToSpeech.LANG_AVAILABLE) Log.w(TAG, "Korean voice unavailable ($language)")

        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            pending[id] = cont
            cont.invokeOnCancellation { pending.remove(id); tts.stop() }
            if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) finish(id)
        }
    }

    override fun isOfflineRecognitionAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    override fun close() {
        tts.shutdown()
    }

    private fun finish(utteranceId: String) {
        pending.remove(utteranceId)?.resume(Unit)
    }
}
