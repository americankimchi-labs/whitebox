package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.MirrorChannel
import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.SignalKind
import com.americankimchilabs.whitebox.domain.Tuning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Store-and-forward from the local chain (the original) to the cloud mirror, by escalation level:
 * - NORMAL: a batch every [Tuning.NORMAL_BATCH_INTERVAL_S] of everything recorded since the last acknowledged seq
 *   (KB-scale; a backlog after a tunnel drains in consecutive batches).
 * - ALERT / TRIGGERED: each record of the core tuple (brake, speed, steering, gear) and every escalation marker is
 *   sent the moment it is recorded, twice, without waiting for an acknowledgement, at most
 *   [Tuning.ALERT_FORWARD_MAX_HZ] per kind; the rest follows in batches every second. The level change back to NORMAL
 *   goes out the same way and is the all-clear for the server's dead-man switch.
 * - A seal goes out at once, ahead of everything, followed immediately by the unsent tail it covers.
 *
 * The acknowledged seq and the acknowledged seals are persisted, so nothing is lost across restarts and nothing is
 * resent forever; the server is idempotent by seq, so resending after a reconnect is always safe.
 */
class Outbox(
    private val store: FileEventStore,
    private val seals: SealStore,
    private val channel: MirrorChannel,
    dir: File,
) {
    private val ackFile = File(dir, "mirror-acked")
    private val sealAckFile = File(dir, "mirror-seals")

    @Volatile var acked: Long = ackFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: -1L
        private set
    private val sealsAcked = sealAckFile.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
    @Volatile private var sentUpTo = acked

    /** The first acknowledgement on a connection is where the server stands, even below what was acknowledged before. */
    @Volatile private var resync = false
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val lastForwardNs = HashMap<String, Long>()

    /** Called by the recorder after a seal is durable locally: it goes out first. */
    fun onSeal(seal: CapsuleSeal) {
        send(Frame.Seal(SealStore.line(seal)))
        kick.trySend(Unit)
    }

    fun start(scope: CoroutineScope, recorder: Recorder): Job = scope.launch {
        launch { channel.incoming.collect(::onIncoming) }
        launch {
            channel.connected.filter { it }.collect {
                // A new connection: everything after the last acknowledgement may not have arrived.
                sentUpTo = acked
                resync = true
                seals.all().filter { MirrorProtocol.sealKey(it) !in sealsAcked }.forEach { send(Frame.Seal(SealStore.line(it))) }
                kick.trySend(Unit)
            }
        }
        launch {
            // The level in chain order: set by each level-change record as it passes, so a frame never carries a
            // level the chain had not reached yet (or had already left) at that record.
            var level = recorder.level.value
            recorder.events.collect { e ->
                val change = levelChange(e.entry)
                if (change != null) level = change
                if (level == EscalationLevel.NORMAL && change == null) return@collect
                // The record that raised the level precedes the change in the chain: it goes out with it, at once.
                val cause = ((e.entry as? ChainEntry.Escalation)?.event as? EscalationEvent.LevelChanged)?.causeSeq
                if (change != null && change != EscalationLevel.NORMAL && cause != null && cause < e.seq) {
                    store.records(cause, cause).singleOrNull()?.let { r -> repeat(2) { send(Frame.Event(change, r)) } }
                }
                if (!forwardsImmediately(e.entry, e.entry.at.elapsedNanos)) return@collect
                val record = store.records(e.seq, e.seq).single()
                repeat(2) { send(Frame.Event(level, record)) }
            }
        }
        launch {
            while (isActive) {
                if (channel.connected.value) drain(recorder.level.value)
                val interval = if (recorder.level.value == EscalationLevel.NORMAL) Tuning.NORMAL_BATCH_INTERVAL_S * 1_000L else 1_000L
                val backlog = (store.head()?.lastSeq ?: -1L) > sentUpTo && sentUpTo - acked < WINDOW
                withTimeoutOrNull(if (backlog && channel.connected.value) BACKLOG_PAUSE_MS else interval) { kick.receive() }
            }
        }
    }

    /** One batch of the unsent tail, if any and if the window of unacknowledged records allows. */
    private fun drain(level: EscalationLevel) {
        val head = store.head()?.lastSeq ?: return
        if (head <= sentUpTo || sentUpTo - acked >= WINDOW) return
        val to = minOf(head, sentUpTo + MAX_BATCH)
        if (send(Frame.Batch(level, store.records(sentUpTo + 1, to)))) sentUpTo = to
    }

    private fun onIncoming(text: String) {
        when (val f = MirrorProtocol.decode(text)) {
            is Frame.Ack -> if (f.seq > acked || resync) {
                if (resync) sentUpTo = f.seq
                resync = false
                acked = f.seq
                if (sentUpTo < acked) sentUpTo = acked
                ackFile.writeText(acked.toString())
                kick.trySend(Unit)
            }
            is Frame.SealAck -> if (sealsAcked.add(f.sealKey)) sealAckFile.appendText(f.sealKey + "\n")
            else -> Unit
        }
    }

    private fun send(f: Frame) = channel.send(MirrorProtocol.encode(f))

    private fun levelChange(e: ChainEntry) = ((e as? ChainEntry.Escalation)?.event as? EscalationEvent.LevelChanged)?.level

    /** Core tuple and escalation markers, rate-capped per kind. */
    private fun forwardsImmediately(e: ChainEntry, nowNs: Long): Boolean {
        val kind = when (e) {
            is ChainEntry.Escalation -> return true
            is ChainEntry.Signal -> e.sample.signal.kind.takeIf { it in CORE }?.name ?: return false
            else -> return false
        }
        val last = lastForwardNs[kind]
        if (last != null && nowNs - last < 1_000_000_000L / Tuning.ALERT_FORWARD_MAX_HZ) return false
        lastForwardNs[kind] = nowNs
        return true
    }

    private companion object {
        val CORE = setOf(SignalKind.BRAKE_PEDAL, SignalKind.SPEED, SignalKind.STEERING_ANGLE, SignalKind.GEAR)

        /** ~30–60 KB: a few hundred records per batch. */
        const val MAX_BATCH = 300L

        /** Records sent but not yet acknowledged before the outbox waits for the server. */
        const val WINDOW = 1_500L

        /** Between consecutive backlog batches. */
        const val BACKLOG_PAUSE_MS = 100L
    }
}
