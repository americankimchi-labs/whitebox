package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.core.MirrorChannel
import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.Entitlements
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.LibraryEvent
import com.americankimchilabs.whitebox.domain.LibraryItem
import com.americankimchilabs.whitebox.domain.LibraryStatus
import com.americankimchilabs.whitebox.domain.RecorderEvent
import com.americankimchilabs.whitebox.domain.Retention
import com.americankimchilabs.whitebox.domain.Tier
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The driver's library on the car and what the car keeps on disk.
 *
 * Library: every capsule and handover report is an item. Airbag crashes never count and never leave; the others use
 * one of the plan's slots for 30 days, extended ones until their paid end. When a new one comes into a full library
 * one leaves (one in, one out; [LibraryItem.leaving]: never a paid one — with only paid ones left, the new moment
 * itself), recorded in the chain and told to the driver ([notices]). It is held, hidden, until 30 days after its event,
 * and comes back if an upgrade makes room before then ([LibraryEvent.Returned]). The plan and extensions come from the owner's account in
 * the cloud ([MirrorProtocol.Frame.Plan], [MirrorProtocol.Frame.Extend]); each is recorded in the chain and
 * acknowledged. A car without the cloud uses [defaultTier].
 *
 * Disk: the cloud is the store. The car keeps what the cloud has confirmed for [Retention.CAR_SENT_DAYS], what it has
 * not for at most [Retention.CAR_UNSENT_MAX_DAYS] (a loss past that is recorded, never hidden), its library's records
 * and any range [holding] names; a car without the cloud keeps [Retention.CAR_ONLY_DAYS] of its own history.
 */
class LibraryController(
    private val recorder: Recorder,
    private val store: FileEventStore,
    dir: File,
    private val clock: Clock,
    private val channel: MirrorChannel? = null,
    /** Highest seq the cloud acknowledged; ignored without a [channel]. */
    private val acked: () -> Long = { -1 },
    /** Ranges that must stay on the car besides the library (a handover trip under way: from its start). */
    private val holding: () -> List<LongRange> = { emptyList() },
    defaultTier: Tier = Entitlements.DEFAULT,
) {
    private val planFile = File(dir, "plan")
    private val itemsFile = File(dir, "library")
    private val doneFile = File(dir, "library-commands")
    private val mutex = Mutex()

    private val _tier = MutableStateFlow(loadPlan()?.first ?: defaultTier)
    val tier: StateFlow<Tier> = _tier.asStateFlow()
    val entitlements: Entitlements get() = Entitlements(_tier.value)

    private val _items = MutableStateFlow(loadItems())
    /** Every item the car knows, oldest first; [LibraryItem.status] says where each stands. */
    val items: StateFlow<List<LibraryItem>> = _items.asStateFlow()

    private val _notices = MutableSharedFlow<LibraryItem>(replay = 1, extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Items that left a full library (the driver is told: by voice once parked, and on the library screen). */
    val notices: SharedFlow<LibraryItem> = _notices.asSharedFlow()

    fun start(scope: CoroutineScope): Job = scope.launch {
        mutex.withLock {
            if (loadPlan() == null) setPlan(_tier.value, null, clock.now().wallMillis)
            // Capsules from before the library existed join it as they are, without anything leaving.
            if (!itemsFile.exists()) save(recorder.capsules.value.map { item(it) }.distinctBy { it.id })
        }
        launch { recorder.capsules.collect { seals -> mutex.withLock { onSeals(seals) } } }
        launch {
            recorder.events.collect { e ->
                val answer = (e.entry as? ChainEntry.Escalation)?.event as? EscalationEvent.IncidentAnswered
                if (answer != null && !answer.keep) mutex.withLock { update(answer.capsuleId) { it.copy(released = true) } }
            }
        }
        channel?.let { ch -> launch { ch.incoming.collect { text -> MirrorProtocol.decode(text)?.let { onFrame(it) } } } }
        launch {
            delay(HOUSEKEEPING_FIRST_MS)
            while (isActive) {
                runCatching { housekeep() }
                delay(HOUSEKEEPING_MS)
            }
        }
    }

    private suspend fun onSeals(seals: List<CapsuleSeal>) {
        val known = _items.value.associateBy { it.id }
        // A moment re-sealed under a graver trigger (a hard brake that became a crash, a crash whose airbag fired) takes it.
        var items = _items.value.map { i -> seals.firstOrNull { it.capsule.id == i.id && it.capsule.trigger != i.trigger }?.let { i.copy(trigger = it.capsule.trigger, severe = i.severe || it.capsule.trigger.severe) } ?: i }
        val fresh = seals.filter { it.capsule.id !in known }.map { item(it) }.distinctBy { it.id }
        if (fresh.isEmpty()) {
            if (items != _items.value) save(items)
            return
        }
        items = items + fresh
        val now = clock.now().wallMillis
        for (new in fresh) {
            val leaving = LibraryItem.leaving(items, new.id, entitlements.librarySlots, now) ?: continue
            recorder.record(ChainEntry.Library(LibraryEvent.Left(leaving.id), clock.now()))
            items = items.map { if (it.id == leaving.id) it.copy(left = true) else it }
            _notices.tryEmit(leaving.copy(left = true))
        }
        save(items)
    }

    private suspend fun onFrame(f: Frame) {
        val id = when (f) {
            is Frame.Plan -> f.commandId
            is Frame.Extend -> f.commandId
            else -> return
        }
        mutex.withLock {
            if (id !in done()) {
                when (f) {
                    // The cloud confirming the plan the car already has is not a change.
                    is Frame.Plan -> if (Tier.valueOf(f.tier) != _tier.value) setPlan(Tier.valueOf(f.tier), f.previous.takeIf { it != "-" }?.let(Tier::valueOf), f.changedAtWall)
                    is Frame.Extend -> {
                        recorder.record(ChainEntry.Library(LibraryEvent.Extended(f.capsuleId, f.untilWall), clock.now()))
                        update(f.capsuleId) { it.copy(extendedUntil = maxOf(it.extendedUntil ?: 0, f.untilWall)) }
                    }
                    else -> Unit
                }
                doneFile.appendText(id + "\n")
            }
        }
        channel?.send(MirrorProtocol.encode(Frame.LibraryAck(id)))
    }

    /** A plan set on the car itself (a car without the cloud, for testing); recorded like any change. */
    suspend fun overridePlan(tier: Tier) = mutex.withLock {
        if (tier != _tier.value) setPlan(tier, _tier.value, clock.now().wallMillis)
    }

    private suspend fun setPlan(tier: Tier, previous: Tier?, changedAtWall: Long) {
        recorder.record(ChainEntry.Library(LibraryEvent.PlanChanged(tier, previous), clock.now()))
        val tmp = File(planFile.parentFile, planFile.name + ".tmp")
        tmp.writeText("$tier|${previous ?: "-"}|$changedAtWall")
        tmp.renameTo(planFile)
        _tier.value = tier
        // A bigger plan makes room: items that left and are still held come back, newest first.
        val back = LibraryItem.returning(_items.value, Entitlements(tier).librarySlots, clock.now().wallMillis).map { it.id }.toSet()
        if (back.isNotEmpty()) {
            back.forEach { recorder.record(ChainEntry.Library(LibraryEvent.Returned(it), clock.now())) }
            save(_items.value.map { if (it.id in back) it.copy(left = false) else it })
        }
    }

    /**
     * Removes from the car what it no longer needs (closed files only; the open one is never touched). Returns the
     * last removed seq, or null if nothing was.
     */
    suspend fun housekeep(): Long? {
        val now = clock.now().wallMillis
        val ack = acked()
        var through = -1L
        var lost = false
        for ((_, last, wall) in store.closedRanges()) {
            val removable = if (channel == null) {
                wall < now - Retention.CAR_ONLY_DAYS * Retention.DAY_MS
            } else {
                (wall < now - Retention.CAR_SENT_DAYS * Retention.DAY_MS && last <= ack) ||
                    wall < now - Retention.CAR_UNSENT_MAX_DAYS * Retention.DAY_MS
            }
            if (!removable) break
            through = last
            if (channel != null && last > ack) lost = true
        }
        val seals = recorder.capsules.value.associateBy { it.capsule.id }
        val keep = _items.value.filter { it.visible(now) }.mapNotNull { seals[it.id]?.capsule?.let { c -> c.firstSeq..c.lastSeq } } + holding()
        val cp = store.prune(through, keep) ?: return null
        recorder.record(ChainEntry.Recorder(RecorderEvent.Pruned(cp.seq, cp.wall, unsent = lost), clock.now()))
        return cp.seq
    }

    /** Its time is when the car first saw it sealed: the provisional seal at the trigger, as it happens. */
    private fun item(seal: CapsuleSeal) = LibraryItem(seal.capsule.id, seal.capsule.trigger, seal.sealedAtWallMillis)

    private fun update(id: String, change: (LibraryItem) -> LibraryItem) {
        if (_items.value.none { it.id == id }) return
        save(_items.value.map { if (it.id == id) change(it) else it })
    }

    private fun save(items: List<LibraryItem>) {
        val tmp = File(itemsFile.parentFile, itemsFile.name + ".tmp")
        tmp.writeText(items.joinToString("") { "${it.id}|${it.trigger}|${it.eventWall}|${it.released}|${it.left}|${it.extendedUntil ?: "-"}\n" })
        tmp.renameTo(itemsFile)
        _items.value = items
    }

    private fun loadItems(): List<LibraryItem> = itemsFile.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.mapNotNull { l ->
        runCatching {
            val f = l.split("|")
            LibraryItem(f[0], CapsuleTrigger.valueOf(f[1]), f[2].toLong(), f[3].toBooleanStrict(), f[4].toBooleanStrict(), f[5].takeIf { it != "-" }?.toLong())
        }.getOrNull()
    } ?: emptyList()

    private fun loadPlan(): Triple<Tier, Tier?, Long>? = runCatching {
        val f = planFile.readText().trim().split("|")
        Triple(Tier.valueOf(f[0]), f[1].takeIf { it != "-" }?.let(Tier::valueOf), f[2].toLong())
    }.getOrNull()

    private fun done(): Set<String> = doneFile.takeIf { it.exists() }?.readLines()?.toSet() ?: emptySet()

    /** Items using a slot now, kept or extended (for "보관 N/M"). */
    fun slotsUsed(now: Long = clock.now().wallMillis) = _items.value.count { it.usesSlot(now) }

    private companion object {
        /** After start, once the drive's first minutes are written (housekeeping never competes with recording). */
        const val HOUSEKEEPING_FIRST_MS = 60_000L
        const val HOUSEKEEPING_MS = 3_600_000L
    }
}
