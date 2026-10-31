package com.americankimchilabs.whitebox.vehicle.pleos

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.OnServerConnectionListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.americankimchilabs.whitebox.core.PhraseListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.Locale

private const val TAG = "WhiteBox/Stt"

private const val INIT_TIMEOUT_MS = 5_000L

/** A request() that the service never acknowledges with onReady this soon has been dropped: re-initialise. */
private const val READY_TIMEOUT_MS = 3_000L

/**
 * A request never ends on silence by itself, so one with no speech this long is stopped and reissued (stop() sends
 * no onEndedRecognition). Each reissue leaves a deaf gap of about [REQUEST_GAP_MS].
 */
private const val RECYCLE_MS = 8_000L
private const val REQUEST_GAP_MS = 200L

/** After an error the next request waits this long, doubling to [ERROR_BACKOFF_MAX_MS] while errors repeat. */
private const val ERROR_BACKOFF_MS = 500L
private const val ERROR_BACKOFF_MAX_MS = 8_000L

/**
 * Pleos SpeechToText from the speech service's own microphone, as a stream of finished utterances.
 *
 * [onDeviceOnly] false (HYBRID) for a short answer window: server recognition once the app is registered with Pleos,
 * on-device otherwise. True (ON_DEVICE) for listening while the car has no network. Rules that come from the platform's behaviour:
 * - the end of an utterance can arrive before a `completed` result, so the answer is the last text seen when
 *   onEndedRecognition comes;
 * - requests are recycled on our own timer, since silence never ends one;
 * - audio is never pushed with sendAudio (the on-device engine crashes when audio continues after an utterance
 *   closed), only the service's microphone is used;
 * - when the service restarts (it does after a client dies) the SDK does not retry: a request that is never
 *   acknowledged re-initialises the client.
 * Callbacks reach only a live client, so the recording service owns this object for its lifetime.
 */
class PleosPhraseListener(
    context: Context,
    onDeviceOnly: Boolean,
    private val credentials: PleosCredentials?,
) : PhraseListener, Closeable {

    private val app = context.applicationContext
    private val mode = if (onDeviceOnly) Mode.ON_DEVICE else Mode.HYBRID
    private var stt: SpeechToText? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private var failed = false
    @Volatile private var sink: ResultListener? = null

    /** Forwards to whichever collection is active; the SDK keeps one listener for the client's lifetime. */
    private val relay = object : ResultListener {
        override fun onReady() { sink?.onReady() }
        override fun onStartedRecognition() { sink?.onStartedRecognition() }
        override fun onUpdated(stt: String, completed: Boolean) { sink?.onUpdated(stt, completed) }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() { sink?.onEndedRecognition() }
        override fun onError(errorType: ErrorType) { sink?.onError(errorType) }
    }

    private fun connect(): CompletableDeferred<Boolean> = synchronized(this) {
        ready?.takeIf { !failed }?.let { return it }
        failed = false
        stt?.runCatching { release() }
        val deferred = CompletableDeferred<Boolean>()
        val s = SpeechToText(app, mode)
        s.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                s.setLocale(Locale.KOREA)
                s.addListener(relay)
                if (mode != Mode.ON_DEVICE) credentials?.let { c ->
                    s.registerApp(c.clientId, c.clientSecret, object : OnServerConnectionListener {
                        override fun onConnected() {
                            Log.i(TAG, "server recognition registered")
                        }
                        override fun onFailed(errorType: ErrorType) {
                            Log.w(TAG, "server recognition registration failed: $errorType")
                        }
                    })
                }
                deferred.complete(true)
            }

            override fun onFailure(errorType: ErrorType) {
                Log.w(TAG, "recognition unavailable ($mode): $errorType")
                synchronized(this@PleosPhraseListener) { failed = true }
                deferred.complete(false)
            }
        })
        stt = s
        ready = deferred
        deferred
    }

    private fun reinitialise() = synchronized(this) { failed = true }

    /** One collection's view of the current request, written from the speech service's callback thread. */
    private class State {
        @Volatile var last = ""
        @Volatile var readyAt = 0L
        @Volatile var heardAt = 0L
        @Volatile var errors = 0
        @Volatile var generation = 0
    }

    override fun utterances(): Flow<String> = callbackFlow {
        val st = State()
        var issue: (suspend () -> Unit)? = null

        fun next(afterMs: Long) = launch {
            delay(afterMs)
            issue?.invoke()
        }

        sink = object : ResultListener {
            override fun onReady() {
                st.readyAt = SystemClock.elapsedRealtime()
            }
            override fun onStartedRecognition() = Unit
            override fun onUpdated(stt: String, completed: Boolean) {
                st.last = stt
                if (stt.isNotBlank()) st.heardAt = SystemClock.elapsedRealtime()
            }
            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onEndedRecognition() {
                st.errors = 0
                st.last.takeIf { it.isNotBlank() }?.let { trySend(it) }
                st.last = ""
                next(REQUEST_GAP_MS)
            }
            override fun onError(errorType: ErrorType) {
                Log.w(TAG, "recognition error: $errorType")
                st.last = ""
                st.errors++
                if (errorType == ErrorType.ON_DEVICE_ENGINE_ERROR || st.errors >= 3) reinitialise()
                next((ERROR_BACKOFF_MS shl (st.errors - 1).coerceIn(0, 4)).coerceAtMost(ERROR_BACKOFF_MAX_MS))
            }
        }

        issue = issue@{
            if (withTimeoutOrNull(INIT_TIMEOUT_MS) { connect().await() } != true) {
                next(ERROR_BACKOFF_MAX_MS)
                return@issue
            }
            val mine = ++st.generation
            st.readyAt = 0L
            st.heardAt = 0L
            stt?.runCatching { request() }?.onFailure { Log.w(TAG, "request failed: $it") }
            delay(READY_TIMEOUT_MS)
            if (st.generation == mine && st.readyAt == 0L) {
                Log.w(TAG, "request never acknowledged; re-initialising")
                reinitialise()
                next(REQUEST_GAP_MS)
            }
        }

        // Silence never ends a request: recycle one that has heard nothing.
        launch {
            while (isActive) {
                delay(1_000)
                val now = SystemClock.elapsedRealtime()
                if (st.readyAt > 0 && now - st.readyAt >= RECYCLE_MS && st.heardAt < st.readyAt && st.last.isBlank()) {
                    st.readyAt = 0L
                    stt?.runCatching { stop() }
                    next(REQUEST_GAP_MS)
                }
            }
        }

        next(0)
        awaitClose {
            sink = null
            issue = null
            stt?.runCatching { stop() }
        }
    }

    override fun close() {
        stt?.runCatching { release() }
        stt = null
    }
}
