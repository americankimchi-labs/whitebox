package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.core.MirrorStore
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.DriveEvent
import com.americankimchilabs.whitebox.domain.EngineOutput
import com.americankimchilabs.whitebox.domain.EscalationEngine
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import com.americankimchilabs.whitebox.domain.Tuning
import com.americankimchilabs.whitebox.domain.VoiceLine
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The record pipeline: every entry is appended durably first, then shown to the escalation engine, whose markers are
 * appended next and whose seals are signed over the head that includes them. Local seal before any upload
 * (durability wins); the mirror is told afterwards.
 *
 * Calls are serialised; feed [record] from one coroutine so the chain order is the arrival order.
 */
class Recorder(
    private val store: FileEventStore,
    private val seals: SealStore,
    private val signer: Signer,
    private val clock: Clock,
    private val mirror: MirrorStore,
) {
    private val engine = EscalationEngine()
    private val mutex = Mutex()

    private val _events = MutableSharedFlow<DriveEvent>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Every appended event, live (UI timeline). Slow collectors lose old items, never block recording. */
    val events: SharedFlow<DriveEvent> = _events.asSharedFlow()

    private val _level = MutableStateFlow(EscalationLevel.NORMAL)
    val level: StateFlow<EscalationLevel> = _level.asStateFlow()

    private val _capsules = MutableStateFlow(seals.all())
    val capsules: StateFlow<List<CapsuleSeal>> = _capsules.asStateFlow()

    private val _incident = MutableStateFlow<IncidentCapsule?>(null)
    private var incidentAskedNs = 0L
    /**
     * A sealed near-miss capsule whose car drove on: the keep question is asked by voice and waits here until the
     * voice side answers it (an answer or silence). Silence keeps the capsule, so an offer is not carried across a
     * process restart, and one nobody resolves expires after the longest the question can take.
     */
    val pendingIncident: StateFlow<IncidentCapsule?> = _incident.asStateFlow()

    private val _say = MutableSharedFlow<VoiceLine>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Lines the engine asks the car to speak (post-crash guidance). */
    val say: SharedFlow<VoiceLine> = _say.asSharedFlow()

    val eventStore: EventStore get() = store

    suspend fun record(entry: ChainEntry): DriveEvent = mutex.withLock {
        val event = append(entry)
        for (output in engine.onEvent(event)) {
            when (output) {
                is EngineOutput.Append -> append(ChainEntry.Escalation(output.event, clock.now()))
                is EngineOutput.Seal -> {
                    // A crash signature inside the asked-about capsule: it is no longer the driver's to release.
                    if (output.trigger.severe && _incident.value?.id == output.capsuleId) _incident.value = null
                    seal(IncidentCapsule(output.capsuleId, output.trigger, output.firstSeq, store.head()!!.lastSeq), output.final)
                }
                is EngineOutput.IncidentPrompt -> {
                    append(ChainEntry.Escalation(EscalationEvent.IncidentOffered(output.capsule), clock.now()))
                    _incident.value = output.capsule
                    incidentAskedNs = event.entry.at.elapsedNanos
                }
                is EngineOutput.Say -> _say.tryEmit(output.line)
            }
        }
        expireIncident(event.entry.at.elapsedNanos)
        _level.value = engine.level
        event
    }

    /**
     * How the keep question ended for [capsuleId]. The capsule is already sealed and stays in the chain either way; the
     * answer is recorded as its label ("아니" releases it from the driver's library; silence keeps it, unanswered).
     */
    suspend fun answerIncident(capsuleId: String, keep: Boolean, answered: Boolean) = mutex.withLock {
        val capsule = _incident.value?.takeIf { it.id == capsuleId } ?: return@withLock
        append(ChainEntry.Escalation(EscalationEvent.IncidentAnswered(capsule.id, keep, answered), clock.now()))
        _incident.value = null
    }

    /** Nobody resolved the question in the longest time it can take: silence, the capsule stays in the library. */
    private fun expireIncident(nowNs: Long) {
        val maxNs = (Tuning.KEEP_QUESTION_MAX_S + Tuning.KEEP_ANSWER_WINDOW_S) * 1_000_000_000L
        if (_incident.value != null && nowNs - incidentAskedNs > maxNs) _incident.value = null
    }

        private fun append(entry: ChainEntry) = store.append(entry).also { _events.tryEmit(it) }

    private suspend fun seal(capsule: IncidentCapsule, final: Boolean) {
        val seal = CapsuleSeal.create(capsule, store.hashAt(capsule.lastSeq), final, clock.now().wallMillis, signer)
        seals.append(seal)
        _capsules.value = seals.all()
        mirror.uploadSealManifest(capsule, seal.headHash)
    }
}
