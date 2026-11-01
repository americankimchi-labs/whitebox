package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.data.MirrorProtocol
import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import com.americankimchilabs.whitebox.domain.Entitlements
import com.americankimchilabs.whitebox.domain.Extension
import com.americankimchilabs.whitebox.domain.Retention
import com.americankimchilabs.whitebox.domain.Tier
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.Base64

/**
 * Taking money. White Box has no checkout of its own: a payment provider plugs in here. Card details never pass
 * through White Box; the provider's own page or sheet collects them and reports the result.
 */
interface PaymentPort {
    sealed interface Result {
        data class Paid(val receipt: String) : Result
        data class Failed(val reason: String) : Result
    }

    /** A one-off purchase ([what] for the receipt). */
    fun charge(ownerId: String, what: String, amountWon: Int): Result

    /** A monthly plan (0 won: back to Free). */
    fun subscribe(ownerId: String, tier: Tier, monthlyWon: Int): Result

    /** Shown beside a purchase: empty for a real provider; the demonstration says it took no money. */
    val notice: String
}

/** For the demonstration: grants at once and takes no money (said on every purchase, like the dashboard's codes). */
class DemoPayment : PaymentPort {
    data class Purchase(val atMs: Long, val ownerId: String, val what: String, val amountWon: Int)

    private val made = ArrayDeque<Purchase>()

    @Synchronized
    override fun charge(ownerId: String, what: String, amountWon: Int): PaymentPort.Result {
        made.addLast(Purchase(System.currentTimeMillis(), ownerId, what, amountWon))
        while (made.size > 50) made.removeFirst()
        return PaymentPort.Result.Paid("DEMO-" + System.nanoTime().toString(36))
    }

    override fun subscribe(ownerId: String, tier: Tier, monthlyWon: Int) = charge(ownerId, "plan $tier", monthlyWon)

    override val notice = "시연용 결제: 실제로 돈이 나가지 않습니다."

    @Synchronized
    fun recent(): List<Purchase> = made.toList()
}

/**
 * Plans and extensions, and the commands that tell each car about them (resent until the car acknowledges, like
 * handover commands). Plans belong to the owner (the phone identity, never the car): selling the car never moves
 * them. A car without an owner, or an owner with no plan on record, has [defaultTier].
 *
 * Log lines (`plans.log`, append-only): `T|owner|tier|previous|changedAt|how|receipt`,
 * `X|device|capsule|until|years|paidAt|receipt`, `C|device|commandId|frame`, `D|device|commandId`.
 */
class Plans(
    dir: File,
    val defaultTier: Tier = Entitlements.DEFAULT,
    val payments: PaymentPort = DemoPayment(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Plan(val tier: Tier, val previous: Tier?, val changedAtMs: Long)

    private val file = File(dir.apply { mkdirs() }, "plans.log")
    private val plans = HashMap<String, Plan>()
    private val extensions = HashMap<Pair<String, String>, Long>()
    private val commands = HashMap<String, LinkedHashMap<String, String>>() // device → command id → frame, until acked
    private val lastPlanSent = HashMap<String, String>() // device → tier last told to the car
    private val random = SecureRandom()

    init {
        if (file.exists()) file.readLines().filter { it.isNotBlank() }.forEach(::apply)
    }

    private fun apply(line: String) {
        val f = line.split("|")
        when (f[0]) {
            "T" -> plans[f[1]] = Plan(Tier.valueOf(f[2]), f[3].takeIf { it != "-" }?.let(Tier::valueOf), f[4].toLong())
            "X" -> extensions[f[1] to f[2]] = maxOf(extensions[f[1] to f[2]] ?: 0, f[3].toLong())
            "C" -> {
                val frame = line.substringAfter("C|${f[1]}|${f[2]}|")
                commands.getOrPut(f[1]) { LinkedHashMap() }[f[2]] = frame
                (MirrorProtocol.decode(frame) as? Frame.Plan)?.let { lastPlanSent[f[1]] = it.tier }
            }
            "D" -> commands[f[1]]?.remove(f[2])
        }
    }

    @Synchronized
    private fun record(vararg fields: Any) {
        val line = fields.joinToString("|")
        FileOutputStream(file, true).use {
            it.write((line + "\n").toByteArray())
            it.fd.sync()
        }
        apply(line)
    }

    private fun id() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(9).also(random::nextBytes))

    @Synchronized
    fun plan(ownerId: String?): Plan = ownerId?.let { plans[it] } ?: Plan(defaultTier, null, 0)

    fun tier(ownerId: String?): Tier = plan(ownerId).tier

    /** Days of everyday driving to keep for this owner now (a smaller plan's window applies after the notice period). */
    fun historyDays(ownerId: String?): Int = plan(ownerId).let { Retention.historyDays(it.tier, it.previous, it.changedAtMs, clock()) }

    /**
     * Moves [ownerId] to [tier] and tells each of [devices]. [how] is "admin" (the dashboard switch, no payment) or
     * "purchase" (through [payments]). Returns null on success, else why it failed.
     */
    @Synchronized
    fun setTier(ownerId: String, tier: Tier, devices: List<String>, how: String): String? {
        val current = plan(ownerId)
        if (current.tier == tier) return null
        val receipt = if (how == "purchase") {
            when (val r = payments.subscribe(ownerId, tier, Entitlements(tier).monthlyWon)) {
                is PaymentPort.Result.Paid -> r.receipt
                is PaymentPort.Result.Failed -> return r.reason
            }
        } else "-"
        val now = clock()
        record("T", ownerId, tier, current.tier, now, how, receipt)
        devices.forEach { tellPlan(it, ownerId) }
        return null
    }

    /** Queues the owner's plan for a car if it was never told it (a new car, a new owner, a change). */
    @Synchronized
    fun tellPlan(deviceId: String, ownerId: String?) {
        val p = plan(ownerId)
        if (lastPlanSent[deviceId] == p.tier.name) return
        record("C", deviceId, id().let { cid -> "$cid|" + MirrorProtocol.encode(Frame.Plan(cid, p.tier.name, p.previous?.name ?: "-", p.changedAtMs)) })
    }

    /**
     * Extends [capsuleId] of [deviceId] (event at [eventWall]) by [e], paid through [payments]; returns the new end,
     * or the reason it failed.
     */
    @Synchronized
    fun extend(ownerId: String, deviceId: String, capsuleId: String, eventWall: Long, e: Extension): Result<Long> {
        val receipt = when (val r = payments.charge(ownerId, "extend $capsuleId ${e.years}y", e.priceWon)) {
            is PaymentPort.Result.Paid -> r.receipt
            is PaymentPort.Result.Failed -> return Result.failure(IllegalStateException(r.reason))
        }
        val until = Retention.extend(eventWall, extensions[deviceId to capsuleId], e)
        record("X", deviceId, capsuleId, until, e.years, clock(), receipt)
        record("C", deviceId, id().let { cid -> "$cid|" + MirrorProtocol.encode(Frame.Extend(cid, capsuleId, until)) })
        return Result.success(until)
    }

    @Synchronized
    fun extendedUntil(deviceId: String, capsuleId: String): Long? = extensions[deviceId to capsuleId]

    /** Frames the car has not acknowledged yet, oldest first (sent on connecting and when made). */
    @Synchronized
    fun pending(deviceId: String): List<String> = commands[deviceId]?.values?.toList() ?: emptyList()

    @Synchronized
    fun acked(deviceId: String, commandId: String) {
        if (commands[deviceId]?.containsKey(commandId) == true) record("D", deviceId, commandId)
    }
}
