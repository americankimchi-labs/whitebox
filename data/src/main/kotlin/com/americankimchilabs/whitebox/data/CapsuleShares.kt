package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.MirrorChannel
import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import com.americankimchilabs.whitebox.data.MirrorProtocol.Record
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The evidence packet's QR: a link anyone the driver shows it to (an insurer, the police) can open on their phone to
 * see that one capsule and check it themselves. The link carries, after `#` (never sent to any server), a random
 * share code and the keys of the minutes the capsule covers; the server learns only a hash of the code, so it hands
 * out that capsule's encrypted records to whoever holds the link and still cannot read them. Nothing else of the car
 * opens with it. A share lasts [VALID_MS]; the same capsule shows the same QR until then.
 */
class CapsuleShares(
    private val store: FileEventStore,
    private val channel: MirrorChannel,
    dir: File,
    private val deviceId: String,
    private val stream: String,
    /** The key of one minute of the car's records ((epoch, minute) → key). */
    private val minuteKey: (Int, Long) -> ByteArray?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Share(val capsuleId: String, val code: String, val expiresMs: Long, var acked: Boolean)

    /** `capsuleId|code|expiresMs|acked` per line. */
    private val file = File(dir, "shares")
    private val random = SecureRandom()

    private fun shares(): MutableList<Share> = file.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }
        ?.map { it.split("|").let { f -> Share(f[0], f[1], f[2].toLong(), f[3] == "1") } }?.toMutableList() ?: mutableListOf()

    private fun save(all: List<Share>) = file.writeText(all.joinToString("") { "${it.capsuleId}|${it.code}|${it.expiresMs}|${if (it.acked) 1 else 0}\n" })

    /**
     * The QR link for [capsule] on [webBase]; null if none of its records use per-minute keys (older chains), since
     * a link must never carry a key that opens more than the capsule.
     */
    @Synchronized
    fun link(capsule: IncidentCapsule, webBase: String): String? {
        val keys = minutes(capsule).mapNotNull { (e, m) -> minuteKey(e, m)?.let { "$e.$m.${b64url(it)}" } }
        if (keys.isEmpty()) return null
        val all = shares()
        val now = clock()
        val share = all.firstOrNull { it.capsuleId == capsule.id && it.expiresMs > now + MIN_LEFT_MS }
            ?: Share(capsule.id, b64url(ByteArray(16).also(random::nextBytes)), now + VALID_MS, false).also {
                all.removeAll { s -> s.capsuleId == capsule.id }
                all += it
                save(all)
            }
        if (!share.acked) announce(share)
        return "$webBase/capsule#d=$deviceId&s=$stream&id=${capsule.id}&t=${share.code}&k=${keys.joinToString(",")}"
    }

    /** Tells the server the hash of the share code (again on every connection until it acknowledges). */
    fun start(scope: CoroutineScope): Job = scope.launch {
        launch {
            channel.incoming.collect { text ->
                val f = MirrorProtocol.decode(text) as? Frame.ShareAck ?: return@collect
                synchronized(this@CapsuleShares) { shares().also { all -> all.filter { it.capsuleId == f.capsuleId }.forEach { it.acked = true }; save(all) } }
            }
        }
        launch {
            channel.connected.filter { it }.collect {
                val now = clock()
                synchronized(this@CapsuleShares) { shares() }.filter { !it.acked && it.expiresMs > now }.forEach(::announce)
            }
        }
    }

    private fun announce(s: Share) {
        channel.send(MirrorProtocol.encode(Frame.Share(s.capsuleId, codeHash(s.code), s.expiresMs)))
    }

    private fun minutes(c: IncidentCapsule): Set<Pair<Int, Long>> = store.records(c.firstSeq, c.lastSeq)
        .mapNotNull { Record.parse(it)?.entry }
        .flatMap { it.split("|") }
        .filter { it.startsWith(RecordCipher.PREFIX_V3) }
        .mapTo(linkedSetOf()) { RecordCipher.epochOf(it) to RecordCipher.minuteOf(it)!! }

    companion object {
        /** Long enough for an insurance claim or a police report to run its course. */
        const val VALID_MS = 90L * 24 * 3600_000

        /** A share about to run out is replaced rather than shown. */
        private const val MIN_LEFT_MS = 7L * 24 * 3600_000

        /** What the server keeps of a share code: SHA-256, hex (the code itself is 128 random bits). */
        fun codeHash(code: String): String = MessageDigest.getInstance("SHA-256").digest(code.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}
