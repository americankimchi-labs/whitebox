package com.americankimchilabs.whitebox.recorder

import android.util.Log
import com.americankimchilabs.whitebox.core.AssistantCommands
import com.americankimchilabs.whitebox.core.AudioBeaconPort
import com.americankimchilabs.whitebox.core.AudioFocusPort
import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.core.ConnectivityPort
import com.americankimchilabs.whitebox.core.PhraseListener
import com.americankimchilabs.whitebox.core.SpeakResult
import com.americankimchilabs.whitebox.core.SpeechOutput
import com.americankimchilabs.whitebox.data.Recorder
import com.americankimchilabs.whitebox.domain.AudioEvent
import com.americankimchilabs.whitebox.domain.BookmarkSource
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.GearPosition
import com.americankimchilabs.whitebox.domain.IgnitionState
import com.americankimchilabs.whitebox.domain.LibraryItem
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import com.americankimchilabs.whitebox.domain.KeepQuestion
import com.americankimchilabs.whitebox.domain.KeepQuestion.Action
import com.americankimchilabs.whitebox.domain.Tuning
import com.americankimchilabs.whitebox.domain.VehicleSignal
import com.americankimchilabs.whitebox.domain.VoiceLine
import com.americankimchilabs.whitebox.domain.VoicePhrases
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import com.americankimchilabs.whitebox.domain.HandoverPhase
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "WhiteBox/Voice"

/** Silence before a line once the audio is ours, so ducking settles and the first syllable is not clipped. */
private const val PRE_ROLL_MS = 200L

/** A line other than the keep question waits this long for the audio before it is dropped (its moment passed). */
private const val LINE_FOCUS_WAIT_MS = 30_000L

/** A bookmark counts as saved once its entry is in the chain; the assistant needs our answer within seconds. */
private const val BOOKMARK_WRITE_TIMEOUT_MS = 3_000L

private const val RESTART_DELAY_MS = 5_000L

/**
 * Everything the car says and hears for one recording session. One line or listening window at a time (the car's
 * speech service serves one request per client), and the offline bookmark listener pauses while the car speaks.
 *
 * - The keep question ([KeepQuestion]): asked when the recorder offers a near-miss capsule, answered within
 *   [Tuning.KEEP_ANSWER_WINDOW_S], restarted after interruptions; the result labels the capsule.
 * - Post-crash guidance, when the engine asks for it.
 * - Voice bookmarks: from the car assistant while online; from our own on-device listener while offline (the
 *   assistant does not work without a network, and the microphone stays closed while it does).
 * - The dashcam sync chirp while the car is out of Park.
 *
 * Every spoken line and every bookmark goes into the chain through [send], like any other entry.
 */
class VoiceController(
    private val recorder: Recorder,
    private val send: suspend (ChainEntry) -> Unit,
    private val clock: Clock,
    private val speech: SpeechOutput,
    private val answers: PhraseListener,
    private val offlineListener: PhraseListener,
    private val focus: AudioFocusPort,
    private val connectivity: ConnectivityPort,
    private val assistant: AssistantCommands,
    private val beacon: AudioBeaconPort,
    private val text: (VoiceLine) -> String,
    /** Handover mode: its lines to the hired driver, and its phase (the keep question is not theirs to answer). */
    private val handoverLines: Flow<VoiceLine> = emptyFlow(),
    private val handoverPhase: StateFlow<HandoverPhase> = MutableStateFlow(HandoverPhase.OFF),
    /** Kept moments that left a full library: the driver is told once parked (never while driving). */
    private val libraryNotices: Flow<LibraryItem> = emptyFlow(),
) {
    private val voice = Mutex()
    private val busy = MutableStateFlow(false)

    /**
     * The engine is off (ignition OFF or LOCK): a real car suspends soon after, freezing the app. Voice stops at once and
     * the audio is given back before that; nothing asked before the car slept is asked again after it wakes.
     */
    private val engineOff = MutableStateFlow(false)
    private val lines = Channel<VoiceLine>(Channel.UNLIMITED)

    fun start(scope: CoroutineScope): Job = scope.launch {
        resilient("ignition") {
            recorder.events.mapNotNull { ((it.entry as? ChainEntry.Signal)?.sample?.signal as? VehicleSignal.Ignition)?.state }
                .collect { engineOff.value = it == IgnitionState.OFF || it == IgnitionState.LOCK }
        }
        resilient("keep question") { keepQuestions() }
        resilient("lines") { recorder.say.collect { lines.send(it) } }
        resilient("handover lines") { handoverLines.collect { lines.send(it) } }
        resilient("speaker") { for (line in lines) speakLine(line) }
        resilient("assistant") { assistant.bookmarks().collect { request -> request.respond(bookmark(BookmarkSource.ASSISTANT)) } }
        resilient("offline listener") { offlineBookmarks() }
        resilient("chirp") { chirps() }
        resilient("library notice") { libraryNotices() }
    }

    private suspend fun libraryNotices() = coroutineScope {
        val parked = MutableStateFlow(false)
        launch {
            recorder.events.mapNotNull { ((it.entry as? ChainEntry.Signal)?.sample?.signal as? VehicleSignal.Gear)?.position }
                .collect { parked.value = it == GearPosition.PARK }
        }
        // Several while driving: one line once parked.
        libraryNotices.conflate().collect {
            parked.first { p -> p }
            lines.send(VoiceLine.LIBRARY_FULL)
        }
    }

    /** Voice is never allowed to stop recording: a failing part is logged and restarted on its own. */
    private fun CoroutineScope.resilient(name: String, block: suspend () -> Unit) = launch {
        while (isActive) {
            try {
                block()
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$name failed; restarting", e)
                delay(RESTART_DELAY_MS)
            }
        }
    }

    private suspend fun keepQuestions() {
        recorder.pendingIncident.filterNotNull().distinctUntilChanged { a, b -> a.id == b.id }.collect { capsule ->
            // A hired driver at the wheel: the moment is the owner's, kept without asking (as if unanswered).
            if (handoverPhase.value == HandoverPhase.ACTIVE || handoverPhase.value == HandoverPhase.ENDING) {
                recorder.answerIncident(capsule.id, keep = true, answered = false)
            } else {
                exclusive { askToKeep(capsule) }
            }
        }
    }

    private suspend fun <T> exclusive(block: suspend () -> T): T = voice.withLock {
        busy.value = true
        try {
            block()
        } finally {
            busy.value = false
        }
    }

    private sealed interface Input {
        data class Focus(val held: Boolean) : Input
        data class Spoke(val complete: Boolean) : Input
        data class Heard(val text: String) : Input
        data object Tick : Input
        data object Withdrawn : Input
        data object EngineOff : Input
    }

    private sealed interface Outcome {
        data class Finished(val finish: Action.Finish) : Outcome
        data object Withdrawn : Outcome
    }

    private suspend fun askToKeep(capsule: IncidentCapsule) = coroutineScope {
        val inputs = Channel<Input>(Channel.UNLIMITED)
        val q = KeepQuestion(nowMs())
        var held = false
        var speaking: Job? = null
        var listening: Job? = null
        val focusJob = launch { focus.hold().collect { inputs.send(Input.Focus(it)) } }
        val ticker = launch { while (isActive) { delay(250); inputs.send(Input.Tick) } }
        // A crash-level trigger inside the capsule withdraws the question (it is no longer the driver's to release).
        val withdrawn = launch { recorder.pendingIncident.first { it?.id != capsule.id }; inputs.send(Input.Withdrawn) }
        val engine = launch { engineOff.first { it }; inputs.send(Input.EngineOff) }

        var outcome: Outcome? = null
        fun act(actions: List<Action>) {
            for (a in actions) when (a) {
                Action.RequestFocus -> if (held) inputs.trySend(Input.Focus(true))
                Action.Speak -> speaking = launch {
                    delay(PRE_ROLL_MS)
                    val result = speech.speak(text(VoiceLine.KEEP_QUESTION))
                    send(ChainEntry.Audio(AudioEvent.Spoke(VoiceLine.KEEP_QUESTION, result == SpeakResult.DONE), clock.now()))
                    inputs.send(Input.Spoke(result == SpeakResult.DONE))
                }
                Action.StopSpeaking -> speech.stop()
                is Action.Listen -> listening = launch {
                    Log.i(TAG, "listening for the answer until ${a.untilMs}")
                    answers.utterances().collect { inputs.send(Input.Heard(it)) }
                }
                Action.StopListening -> listening?.cancel()
                is Action.Finish -> outcome = Outcome.Finished(a)
            }
        }

        act(q.start())
        while (outcome == null) {
            when (val input = inputs.receive()) {
                is Input.Focus -> {
                    if (input.held != held) Log.i(TAG, if (input.held) "audio is ours" else "audio taken by another app")
                    held = input.held
                    act(if (input.held) q.focusGranted(nowMs()) else q.focusLost())
                }
                is Input.Spoke -> act(q.spoke(input.complete, nowMs()))
                is Input.Heard -> act(q.heard(input.text))
                Input.Tick -> act(q.tick(nowMs()))
                Input.Withdrawn -> outcome = Outcome.Withdrawn
                // Engine off before an answer: kept, as for silence, and never asked again after the car wakes.
                Input.EngineOff -> {
                    speech.stop()
                    outcome = Outcome.Finished(Action.Finish(keep = true, answered = false, confirm = null))
                }
            }
        }
        withdrawn.cancel()
        engine.cancel()
        ticker.cancel()
        listening?.cancel()
        val done = (outcome as? Outcome.Finished)?.finish
        if (done == null) {
            Log.i(TAG, "keep question for ${capsule.id} withdrawn")
            speech.stop()
            speaking?.join()
            focusJob.cancel()
            return@coroutineScope
        }
        speaking?.join()
        recorder.answerIncident(capsule.id, done.keep, done.answered)
        Log.i(TAG, "keep question for ${capsule.id}: keep=${done.keep} answered=${done.answered}")
        done.confirm?.let { line ->
            if (held) {
                delay(PRE_ROLL_MS)
                val result = speech.speak(text(line))
                send(ChainEntry.Audio(AudioEvent.Spoke(line, result == SpeakResult.DONE), clock.now()))
            }
        }
        focusJob.cancel()
    }

    /** Speaks one line once the audio is ours (waiting out a call or the assistant), then gives the audio back. */
    private suspend fun speakLine(line: VoiceLine) = exclusive {
        coroutineScope {
            val granted = MutableStateFlow(false)
            val focusJob = launch { focus.hold().collect { granted.value = it } }
            if (engineOff.value || withTimeoutOrNull(LINE_FOCUS_WAIT_MS) { granted.first { it } } == null) {
                Log.i(TAG, "no audio for $line (or the engine is off); dropped")
            } else {
                delay(PRE_ROLL_MS)
                // Losing the audio mid-line stops it, so a call is not talked over; so does the engine going off.
                val stopper = launch { combine(granted, engineOff) { g, off -> !g || off }.first { it }; speech.stop() }
                val result = speech.speak(text(line))
                stopper.cancel()
                send(ChainEntry.Audio(AudioEvent.Spoke(line, result == SpeakResult.DONE), clock.now()))
            }
            focusJob.cancel()
        }
    }

    /** Records a bookmark; true once its entry is in the chain (the engine then seals the last minutes). */
    private suspend fun bookmark(source: BookmarkSource): Boolean {
        val entry = ChainEntry.Audio(AudioEvent.Bookmark(source), clock.now())
        val written = withTimeoutOrNull(BOOKMARK_WRITE_TIMEOUT_MS) {
            coroutineScope {
                val seen = async(start = CoroutineStart.UNDISPATCHED) { recorder.events.first { it.entry == entry } }
                send(entry)
                seen.await()
            }
        }
        Log.i(TAG, "bookmark from $source written=${written != null}")
        return written != null
    }

    /** While offline and nothing else is speaking or listening, listen on-device for "방금 저장해". */
    private suspend fun offlineBookmarks() {
        combine(connectivity.online(), busy, engineOff) { online, b, off -> !online && !b && !off }
            .distinctUntilChanged()
            .collectLatest { listen ->
                if (!listen) return@collectLatest
                Log.i(TAG, "offline: listening for a voice bookmark")
                offlineListener.utterances().collect { heard ->
                    // The confirmation pauses this listener (busy) while it is spoken.
                    if (VoicePhrases.isBookmark(heard) && bookmark(BookmarkSource.OFFLINE_LISTENER)) lines.send(VoiceLine.BOOKMARKED)
                }
            }
    }

    /** One chirp every [Tuning.CHIRP_PERIOD_S] while the car is out of Park; each is recorded with its played time. */
    private suspend fun chirps() {
        var id = 0
        recorder.events
            .mapNotNull { ((it.entry as? ChainEntry.Signal)?.sample?.signal as? VehicleSignal.Gear)?.position }
            .distinctUntilChanged { a, b -> (a == GearPosition.PARK) == (b == GearPosition.PARK) }
            .collectLatest { gear ->
                if (gear == GearPosition.PARK) return@collectLatest
                while (true) {
                    beacon.emitChirp(id)?.let { playedAt -> send(ChainEntry.Audio(AudioEvent.Beacon(id, playedAt), clock.now())) }
                    id++
                    delay(Tuning.CHIRP_PERIOD_S * 1_000L)
                }
            }
    }

    private fun nowMs() = clock.now().elapsedNanos / 1_000_000
}
