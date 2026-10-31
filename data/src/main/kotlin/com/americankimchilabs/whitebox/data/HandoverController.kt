package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.core.MirrorChannel
import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.HandoverEndReason
import com.americankimchilabs.whitebox.domain.HandoverMode
import com.americankimchilabs.whitebox.domain.HandoverOutput
import com.americankimchilabs.whitebox.domain.HandoverPhase
import com.americankimchilabs.whitebox.domain.HandoverSource
import com.americankimchilabs.whitebox.domain.HandoverState
import com.americankimchilabs.whitebox.domain.VoiceLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Handover mode (대리 운전) on the car: runs [HandoverMode] over every recorded event and a one-second tick, records
 * what it decides in the chain, seals the trip as its report, and keeps its state in [dir] so a restart (or a suspend)
 * of the head unit mid-trip does not lose it. Commands from the owner's phone arrive over [channel] and are
 * acknowledged once carried out; the car tells the phone where things stand on every change.
 */
class HandoverController(
    private val recorder: Recorder,
    private val store: FileEventStore,
    dir: File,
    private val clock: Clock,
    private val channel: MirrorChannel? = null,
    /** How long a trip may pause before it is final; shorter only for a demonstration. */
    val graceMs: Long = com.americankimchilabs.whitebox.domain.Tuning.HANDOVER_GRACE_MS,
) {
    private val stateFile = File(dir, "handover")
    /** Phone commands already carried out (a resend after a lost acknowledgement must not repeat them). */
    private val doneFile = File(dir, "handover-commands")
    private var saved = load()
    private val mode = HandoverMode(saved, graceMs)
    private val mutex = Mutex()

    private val _phase = MutableStateFlow(mode.phase)
    val phase: StateFlow<HandoverPhase> = _phase.asStateFlow()

    /** When the current phase began (wall ms): for "ending in N minutes" on the screen. */
    private val _since = MutableStateFlow(mode.state.endingSinceWall.takeIf { it > 0 } ?: mode.state.requestedAtWall)
    val since: StateFlow<Long> = _since.asStateFlow()

    private val _say = MutableSharedFlow<VoiceLine>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Lines for the driver. */
    val say: SharedFlow<VoiceLine> = _say.asSharedFlow()

    private val _openMaps = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** The maps app should come forward (the screen does it when it is showing; a background service cannot). */
    val openMaps: SharedFlow<Unit> = _openMaps.asSharedFlow()

    fun start(scope: CoroutineScope): Job = scope.launch {
        // What the car was doing just before: a drive under way, a route set, an enforcement ahead.
        val head = store.head()
        if (head != null) mode.warm(runCatching { store.read(maxOf(0, head.lastSeq - WARM_RECORDS), head.lastSeq) }.getOrDefault(emptyList()))
        mutex.withLock { apply(mode.restored(clock.now().wallMillis)) }
        launch { recorder.events.collect { e -> if (e.entry !is ChainEntry.Handover) mutex.withLock { apply(mode.onEvent(e)) } } }
        launch {
            while (isActive) {
                delay(TICK_MS)
                mutex.withLock { apply(mode.tick(clock.now().wallMillis)) }
            }
        }
        channel?.let { ch ->
            launch { ch.incoming.collect { text -> (MirrorProtocol.decode(text) as? Frame.HandoverCommand)?.let { command(it) } } }
            launch { ch.connected.filter { it }.collect { status() } }
        }
    }

    /** Turned on from the car screen (parked). */
    suspend fun armOnCar() = mutex.withLock {
        val now = clock.now().wallMillis
        apply(mode.arm(HandoverSource.CAR, now, now))
    }

    /** Turned off on the car screen: only before the trip started (the hired driver cannot stop it). */
    suspend fun cancelOnCar(): Boolean = mutex.withLock {
        if (!mode.carMayCancel()) return@withLock false
        apply(mode.end(HandoverSource.CAR, clock.now().wallMillis))
        true
    }

    fun carMayCancel() = mode.carMayCancel()

    private suspend fun command(c: Frame.HandoverCommand) = mutex.withLock {
        if (c.commandId !in done()) {
            val now = clock.now().wallMillis
            apply(if (c.arm) mode.arm(HandoverSource.PHONE, c.requestedAtWall, now) else mode.end(HandoverSource.PHONE, now))
            doneFile.appendText(c.commandId + "\n")
        }
        channel?.send(MirrorProtocol.encode(Frame.HandoverAck(c.commandId)))
        status()
    }

    private fun done(): Set<String> = doneFile.takeIf { it.exists() }?.readLines()?.toSet() ?: emptySet()

    private suspend fun apply(outputs: List<HandoverOutput>) {
        for (o in outputs) when (o) {
            is HandoverOutput.Append -> recorder.record(ChainEntry.Handover(o.event, clock.now()))
            is HandoverOutput.Say -> _say.tryEmit(o.line)
            HandoverOutput.OpenMaps -> _openMaps.tryEmit(Unit)
            is HandoverOutput.SealTrip -> recorder.sealRange(o.capsuleId, CapsuleTrigger.HANDOVER, o.fromSeq, o.final)
        }
        if (mode.state != saved) save(mode.state)
        if (_phase.value != mode.phase) {
            _phase.value = mode.phase
            _since.value = clock.now().wallMillis
            status()
        }
    }

    private fun status() {
        val s = mode.state
        channel?.send(MirrorProtocol.encode(Frame.HandoverStatus(s.phase.name, s.requestedAtWall, _since.value, s.startSeq?.let { "H$it" } ?: "-")))
    }

    private fun save(s: HandoverState) {
        val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
        tmp.writeText(listOf(s.phase, s.armedBy ?: "-", s.requestedAtWall, s.startSeq ?: "-", s.endingReason ?: "-", s.endingSinceWall).joinToString("|"))
        tmp.renameTo(stateFile)
        saved = s
    }

    private fun load(): HandoverState = runCatching {
        val f = stateFile.readText().trim().split("|")
        HandoverState(
            HandoverPhase.valueOf(f[0]), f[1].takeIf { it != "-" }?.let(HandoverSource::valueOf), f[2].toLong(),
            f[3].takeIf { it != "-" }?.toLong(), f[4].takeIf { it != "-" }?.let(HandoverEndReason::valueOf), f[5].toLong(),
        )
    }.getOrDefault(HandoverState())

    private companion object {
        const val TICK_MS = 1_000L

        /** Enough of the recent chain to know the current drive (a few minutes of driving). */
        const val WARM_RECORDS = 5_000L
    }
}
