package com.americankimchilabs.whitebox.core

import com.americankimchilabs.whitebox.domain.DriveEvent
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import com.americankimchilabs.whitebox.domain.LocationFix
import com.americankimchilabs.whitebox.domain.MotionWindow
import com.americankimchilabs.whitebox.domain.SignalKind
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.Timestamp
import kotlinx.coroutines.flow.Flow

/** Monotonic + wall time, read together. */
fun interface Clock {
    fun now(): Timestamp
}

/**
 * Vehicle signals from one backend (Pleos + AAOS, or the scripted fake).
 *
 * [samples] is cold: collecting subscribes to the vehicle, cancelling unsubscribes.
 * A signal the vehicle doesn't support is simply absent from [supportedKinds] and never emitted.
 */
interface VehicleSignalSource {
    suspend fun supportedKinds(): Set<SignalKind>

    fun samples(): Flow<SignalSample>
}

/**
 * The head unit's own accelerometer, averaged into short windows (cold flow; collecting starts the sensor).
 * Empty when the unit has no accelerometer.
 */
interface MotionSource {
    fun windows(): Flow<MotionWindow>
}

/** Cold flow of location fixes; collecting starts updates. */
interface LocationSource {
    fun fixes(): Flow<LocationFix>
}

enum class SpeakResult {
    /** Played to the end. */
    DONE,

    /** Started but stopped before the end (stop(), or the engine cut it off). */
    CUT_OFF,

    /** No voice engine (not bound, failed to initialise); a fallback may take over. */
    UNAVAILABLE,
}

/** Spoken output in Korean. One line at a time; a second call waits for the first. */
interface SpeechOutput {
    suspend fun speak(text: String): SpeakResult

    /** Stops the line being spoken (its [speak] returns [SpeakResult.CUT_OFF]). */
    fun stop()
}

/**
 * Speech recognition from the car's own microphone. [utterances] is cold: collecting opens the microphone and emits
 * the text of each finished utterance; cancelling closes it. Only one collection at a time.
 */
interface PhraseListener {
    fun utterances(): Flow<String>
}

/**
 * The car's audio for our voice lines. [hold] is cold: collecting asks for transient focus that lets other audio
 * duck, accepting a delayed grant; it emits true when held and false when another app (a call, the car assistant)
 * takes it for now. Cancelling gives it back.
 */
interface AudioFocusPort {
    fun hold(): Flow<Boolean>
}

/** Whether the car has a working internet connection (the car assistant needs one). Emits on change. */
interface ConnectivityPort {
    fun online(): Flow<Boolean>
}

/**
 * Voice commands from the car assistant. Each bookmark request must be answered with [BookmarkRequest.respond]
 * within a few seconds (the assistant gives up and tells the driver after 7 s).
 */
interface AssistantCommands {
    fun bookmarks(): Flow<BookmarkRequest>
}

class BookmarkRequest(val respond: (saved: Boolean) -> Unit)

/** Plays the acoustic sync chirp and reports when its first frame actually played (null if it could not play). */
interface AudioBeaconPort {
    suspend fun emitChirp(chirpId: Int): Timestamp?
}

/**
 * Cloud mirror: store-and-forward batches and the seal manifest. Writes are idempotent by [DriveEvent.seq].
 */
interface MirrorStore {
    /** Store-and-forward batch. Returns false if it should be retried later. */
    suspend fun uploadBatch(events: List<DriveEvent>): Boolean

    suspend fun uploadSealManifest(capsule: IncidentCapsule, headHash: ByteArray): Boolean
}

/** Push a viewer link to registered emergency contacts. */
interface EmergencyNotifier {
    suspend fun notifyContacts(capsule: IncidentCapsule): Boolean
}
