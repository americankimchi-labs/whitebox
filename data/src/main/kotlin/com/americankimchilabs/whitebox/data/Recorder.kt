package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.DriveEvent
import com.americankimchilabs.whitebox.domain.EngineOutput
import com.americankimchilabs.whitebox.domain.EscalationEngine
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import com.americankimchilabs.whitebox.domain.Tuning
import com.americankimchilabs.whitebox.domain.VehicleSignal
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
 * (durability wins); [onSeal] is told afterwards.
 *
 * Calls are serialised; feed [record] from one coroutine so the chain order is the arrival order.
 */
class Recorder(
    private val store: FileEventStore,
    private val seals: SealStore,
    private val signer: Signer,
    private val clock: Clock,
    /** Told about each seal after it is durable locally (the mirror sends it first). */
    private val onSeal: suspend (CapsuleSeal) -> Unit = {},
) {
    private val engine = EscalationEngine()
    private val mutex = Mutex()

    private val _events = MutableSharedFlow<DriveEvent>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Every appended event, live (UI timeline). Slow collectors lose old items, never block recording. */
    val events: SharedFlow<DriveEvent> = _events.asSharedFlow()

    private val _level = MutableStateFlow(EscalationLevel.NORMAL)
    val level: StateFlow<EscalationLevel> = _level.asStateFlow()

    /** Capsules whose carried-in states are written (once each, when the capsule first seals). */
    private val carriedFor = HashSet<String>()

    private val _sealing = MutableStateFlow<CapsuleTrigger?>(null)
    /** While [level] is TRIGGERED: what is being sealed (the newest crash trigger, e.g. an airbag taking over). */
    val sealing: StateFlow<CapsuleTrigger?> = _sealing.asStateFlow()

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

    /**
     * An unchanged speed between heartbeats: written only if it turns out to be the last one before a change (or
     * before any other record), so the moment a value starts changing keeps its full resolution.
     */
    private var pendingSpeed: ChainEntry.Signal? = null
    private var storedSpeed: Float? = null
    private var storedSpeedNs = 0L

    suspend fun record(entry: ChainEntry): DriveEvent = mutex.withLock {
        if (thinnable(entry)) {
            // The engine sees every sample. Only if nothing at all follows from it does it stay unwritten for now;
            // it would take the next seq, and nothing else is written before it (any write flushes it first).
            val peek = DriveEvent(store.head()?.lastSeq?.plus(1) ?: 0, entry)
            val outputs = engine.onEvent(peek)
            if (outputs.isEmpty()) {
                pendingSpeed = entry as ChainEntry.Signal
                expireIncident(entry.at.elapsedNanos)
                return@withLock peek
            }
            pendingSpeed = null // an older equal value; this later one stands for it
            return@withLock handle(append(entry), outputs)
        }
        // A heartbeat (or an alert sample) of the same value stands for the one held back: nothing changed between them.
        if (speedOf(entry) != null && speedOf(entry) == storedSpeed) pendingSpeed = null
        val event = append(entry)
        handle(event, engine.onEvent(event))
    }

    private fun speedOf(entry: ChainEntry) = ((entry as? ChainEntry.Signal)?.sample?.signal as? VehicleSignal.Speed)?.metersPerSecond

    /**
     * Speed is reported about ten times a second even when it does not change (standing still, cruising on a
     * constant reading). In normal driving an unchanged value is written at most once a second; every change, the
     * last unchanged value before it, and everything during an alert or a crash are written as they come.
     */
    private fun thinnable(entry: ChainEntry): Boolean {
        val speed = speedOf(entry) ?: return false
        return engine.level == EscalationLevel.NORMAL && speed == storedSpeed &&
            entry.at.elapsedNanos - storedSpeedNs < Tuning.SPEED_HEARTBEAT_MS * 1_000_000L
    }

    private suspend fun handle(event: DriveEvent, outputs: List<EngineOutput>): DriveEvent {
        for (output in outputs) {
            when (output) {
                is EngineOutput.Append -> {
                    append(ChainEntry.Escalation(output.event, clock.now()))
                    (output.event as? EscalationEvent.Triggered)?.trigger?.takeIf { it.crash }?.let { _sealing.value = it }
                }
                is EngineOutput.Seal -> {
                    // A crash signature inside the asked-about capsule: it is no longer the driver's to release.
                    if (output.trigger.crash && _incident.value?.id == output.capsuleId) _incident.value = null
                    if (carriedFor.add(output.capsuleId)) writeCarried(output.firstSeq)
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
        if (engine.level != EscalationLevel.TRIGGERED) _sealing.value = null
        return event
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

    /**
     * Seals the records from [firstSeq] to the head as one capsule (a handover trip's report): provisionally while it
     * may still go on, [final] once it is over. Signed over the head like every seal, and told to [onSeal].
     */
    suspend fun sealRange(id: String, trigger: CapsuleTrigger, firstSeq: Long, final: Boolean) = mutex.withLock {
        val head = store.head() ?: return@withLock
        seal(IncidentCapsule(id, trigger, firstSeq, head.lastSeq), final)
    }

    /** Nobody resolved the question in the longest time it can take: silence, the capsule stays in the library. */
    private fun expireIncident(nowNs: Long) {
        val maxNs = (Tuning.KEEP_QUESTION_MAX_S + Tuning.KEEP_ANSWER_WINDOW_S) * 1_000_000_000L
        if (_incident.value != null && nowNs - incidentAskedNs > maxNs) _incident.value = null
    }

    private fun append(entry: ChainEntry): DriveEvent {
        pendingSpeed?.let { held ->
            pendingSpeed = null
            write(held)
        }
        return write(entry)
    }

    private fun write(entry: ChainEntry): DriveEvent {
        ((entry as? ChainEntry.Signal)?.sample?.signal as? VehicleSignal.Speed)?.let {
            storedSpeed = it.metersPerSecond
            storedSpeedNs = entry.at.elapsedNanos
        }
        return store.append(entry).also {
            _events.tryEmit(it)
            remember(it)
        }
    }

    /** Recent values of every state, newest last: enough to know what each was just before a capsule's window. */
    private val states = HashMap<String, ArrayDeque<DriveEvent>>()

    private fun remember(event: DriveEvent) {
        val signal = (event.entry as? ChainEntry.Signal)?.sample?.signal ?: return
        val key = carriedKey(signal) ?: return
        val list = states.getOrPut(key) { ArrayDeque() }
        list.addLast(event)
        // Keep the one value in force when the oldest window still open could have started, and every one since.
        val horizon = event.entry.at.elapsedNanos - CARRIED_HORIZON_S * 1_000_000_000L
        while (list.size >= 2 && list[1].entry.at.elapsedNanos < horizon) list.removeFirst()
    }

    /**
     * The newest value of every state recorded since this recorder started (gear, brake, turn signal, lamps…), in
     * no particular order. A screen opened in the middle of a long drive learns the gear from here: its last change
     * may lie further back than any recent stretch of the chain it reads.
     */
    suspend fun currentStates(): List<VehicleSignal> = mutex.withLock {
        states.values.mapNotNull { (it.lastOrNull()?.entry as? ChainEntry.Signal)?.sample?.signal }
    }

    /**
     * When a capsule opens: each state as it stood when its window began ([firstSeq]), for the states that were last
     * recorded before the window and so would be missing from it.
     */
    private fun writeCarried(firstSeq: Long) {
        val at = clock.now()
        for (list in states.values) {
            val before = list.lastOrNull { it.seq < firstSeq } ?: continue
            val signal = (before.entry as ChainEntry.Signal).sample.signal
            write(ChainEntry.Carried(signal, firstSeq, at))
        }
    }

    private suspend fun seal(capsule: IncidentCapsule, final: Boolean) {
        val seal = CapsuleSeal.create(capsule, store.hashAt(capsule.lastSeq), final, clock.now().wallMillis, signer)
        seals.append(seal)
        _capsules.value = seals.all()
        onSeal(seal)
    }
}

/** How far back a state is remembered: longer than any capsule window reaches back from its seal. */
private const val CARRIED_HORIZON_S = Tuning.CAPSULE_PRE_WINDOW_S * 2L

/**
 * One key per state a capsule window can start with (per seat, door, lamp, …). Speed is written at least once a second,
 * so every window has it already.
 */
internal fun carriedKey(s: VehicleSignal): String? = when (s) {
    is VehicleSignal.Speed -> null
    is VehicleSignal.Door -> "DOOR:${s.area}"
    is VehicleSignal.SeatBelt -> "SEAT_BELT:${s.seat}"
    is VehicleSignal.SeatOccupancy -> "SEAT_OCCUPANCY:${s.seat}"
    is VehicleSignal.Light -> "LIGHT:${s.lamp}"
    is VehicleSignal.Wiper -> "WIPER:${s.area}"
    is VehicleSignal.Window -> "WINDOW:${s.area}"
    is VehicleSignal.AirbagDeployed -> "AIRBAG:${s.seat}"
    is VehicleSignal.RoadContext -> "ROAD:${s.type}"
    is VehicleSignal.Telltale -> "TELLTALE:${s.name}"
    else -> s.kind.name
}
