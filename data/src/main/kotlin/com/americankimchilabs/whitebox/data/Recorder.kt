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

    private val _incident = MutableStateFlow(restorePendingIncident())
    /**
     * ALERT ended in stillness: "방금 상황을 보관할까요?" waits here until answered (shown parked only). Offer and answer
     * are chain records, so an unanswered offer is restored after process death.
     */
    val pendingIncident: StateFlow<IncidentCapsule?> = _incident.asStateFlow()

    val eventStore: EventStore get() = store

    suspend fun record(entry: ChainEntry): DriveEvent = mutex.withLock {
        val event = append(entry)
        for (output in engine.onEvent(event)) {
            when (output) {
                is EngineOutput.Append -> append(ChainEntry.Escalation(output.event, clock.now()))
                is EngineOutput.Seal -> seal(
                    IncidentCapsule(output.capsuleId, output.trigger, output.firstSeq, store.head()!!.lastSeq),
                    output.final,
                )
                is EngineOutput.IncidentPrompt -> {
                    append(ChainEntry.Escalation(EscalationEvent.IncidentOffered(output.capsule), clock.now()))
                    _incident.value = output.capsule
                }
            }
        }
        _level.value = engine.level
        event
    }

    /** The driver's answer to the incident prompt. Declined incidents simply age out with the rolling stream. */
    suspend fun answerIncident(keep: Boolean) = mutex.withLock {
        val capsule = _incident.value ?: return@withLock
        append(ChainEntry.Escalation(EscalationEvent.IncidentAnswered(capsule.id, keep), clock.now()))
        _incident.value = null
        if (keep) seal(capsule, final = true)
    }

    /** The last offer in the recent chain with no answer after it (≈ the last hour of records is scanned). */
    private fun restorePendingIncident(): IncidentCapsule? {
        val head = store.head() ?: return null
        val recent = store.read(maxOf(0, head.lastSeq - RESTORE_SCAN), head.lastSeq)
            .mapNotNull { (it.entry as? ChainEntry.Escalation)?.event }
        val offer = recent.filterIsInstance<EscalationEvent.IncidentOffered>().lastOrNull() ?: return null
        val answered = recent.filterIsInstance<EscalationEvent.IncidentAnswered>().any { it.capsuleId == offer.capsule.id }
        return if (answered) null else offer.capsule
    }

    private fun append(entry: ChainEntry) = store.append(entry).also { _events.tryEmit(it) }

    private suspend fun seal(capsule: IncidentCapsule, final: Boolean) {
        val seal = CapsuleSeal.create(capsule, store.hashAt(capsule.lastSeq), final, clock.now().wallMillis, signer)
        seals.append(seal)
        _capsules.value = seals.all()
        mirror.uploadSealManifest(capsule, seal.headHash)
    }
}

private const val RESTORE_SCAN = 50_000L
