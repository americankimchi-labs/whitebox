package com.americankimchilabs.whitebox.core

import com.americankimchilabs.whitebox.domain.DriveEvent
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import com.americankimchilabs.whitebox.domain.LocationFix
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

/** Cold flow of location fixes; collecting starts updates. */
interface LocationSource {
    fun fixes(): Flow<LocationFix>
}

/** Voice output and offline speech recognition (it must work in tunnels and underground parking). */
interface SpeechPort {
    suspend fun speak(text: String)

    /** True only if recognition works without a network (tunnels, underground parking). */
    fun isOfflineRecognitionAvailable(): Boolean
}

/** Plays the acoustic sync chirp and reports when it actually started playing. */
interface AudioBeaconPort {
    suspend fun emitChirp(chirpId: Int): Timestamp
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
