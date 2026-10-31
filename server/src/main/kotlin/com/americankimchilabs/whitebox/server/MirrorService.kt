package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.data.EntryCodec
import com.americankimchilabs.whitebox.data.MirrorProtocol
import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import com.americankimchilabs.whitebox.data.MirrorProtocol.Record
import com.americankimchilabs.whitebox.data.SealStore
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.DriveEvent
import com.americankimchilabs.whitebox.domain.EngineOutput
import com.americankimchilabs.whitebox.domain.EscalationEngine
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.Tuning
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The mirror's logic, independent of the web framework: one [Session] per car connection, plus the dead-man tick.
 *
 * A car proves it holds its device key (challenge signed with it); the device id is derived from that key, so no
 * car can write into another's stream. Records are checked against their own hashes and against their held
 * neighbours before they are stored. Each acknowledged batch anchors the stream's contiguous head with a
 * server-signed line (so the car's chain head is held outside the car), and the dead-man switch seals what was
 * received and records a severe event that went silent (for the family's page; nobody is texted).
 */
private const val WINDOW_SCAN = 2_000L

/** Stream logs: escrowed minute keys of severe capsules, a severe event that went silent, who opened a crash record. */
const val ESCROW = "escrow"
const val SEVERE_SILENT = "severe-silent"
const val OPENED = "opened"
const val SHARES = "shares"
private const val EVENTS_KEPT = 30

class MirrorService(
    private val store: StreamStore,
    private val key: ServerKey,
    /** Phone claims and key wraps; absent in tests that only exercise the mirror. */
    val identity: Identity? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val deadMan: DeadMan = DeadMan(),
) {
    private val random = SecureRandom()
    private val events = HashMap<StreamId, ArrayDeque<Pair<Long, String>>>()
    private val lastLevel = HashMap<StreamId, EscalationLevel>()

    /** A readable history per stream for the dashboard (the durable record is the stream's logs). */
    @Synchronized
    private fun note(stream: StreamId, atMs: Long, text: String) {
        val q = events.getOrPut(stream) { ArrayDeque() }
        q.addLast(atMs to text)
        while (q.size > EVENTS_KEPT) q.removeFirst()
    }

    @Synchronized
    private fun levelSeen(stream: StreamId, level: EscalationLevel, atMs: Long) {
        if (lastLevel.put(stream, level) == level) return
        note(stream, atMs, when (level) {
            EscalationLevel.NORMAL -> "정상 (경보 해제)"
            EscalationLevel.ALERT -> "경보: 급제동 등 위험 신호 — 기록을 즉시 전송 중"
            EscalationLevel.TRIGGERED -> "사고 감지: 충돌 수준 — 차량이 봉인 후 전송"
        })
    }

    private val live = HashMap<StreamId, Session>()

    inner class Session {
        /** Frames the server sends without being asked (a finished claim). */
        val pushes = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        private val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes))
        var stream: StreamId? = null
            private set
        private var publicKey: ByteArray? = null

        /** First frame to send on a new connection. */
        fun greeting(): String = MirrorProtocol.encode(Frame.Challenge(nonce))

        /** Frames to send back for one received text frame; null closes the connection. */
        fun onText(text: String): List<String>? {
            val f = MirrorProtocol.decode(text) ?: return emptyList()
            val s = stream
            if (s == null) {
                if (f !is Frame.Auth) return null
                return authenticate(f)
            }
            val now = clock()
            return when (f) {
                is Frame.Event -> {
                    val r = Record.parse(f.record) ?: return emptyList()
                    conflicts(s, store.put(s, listOf(r), now), now)
                    f.serverCopy?.let { keepCopy(s, r, it) }
                    deadMan.onFrame(s, f.level, severe(r), now)
                    levelSeen(s, f.level, now)
                    emptyList()
                }
                is Frame.Batch -> {
                    val records = f.records.mapNotNull(Record::parse)
                    conflicts(s, store.put(s, records, now), now)
                    deadMan.onFrame(s, f.level, records.any(::severe), now)
                    levelSeen(s, f.level, now)
                    val head = store.contiguousHead(s)
                    anchor(s, head, now)
                    listOf(MirrorProtocol.encode(Frame.Ack(head)))
                }
                is Frame.ClaimRequest -> {
                    val code = identity?.claimCode(s) ?: return emptyList()
                    note(s, now, "차량 화면에 휴대폰 연결 QR 표시")
                    listOf(MirrorProtocol.encode(Frame.ClaimCode(code)))
                }
                is Frame.TransferAck -> {
                    identity?.transferAcked(s.deviceId, f.transferId)
                    emptyList()
                }
                is Frame.Wrap -> {
                    val id = identity ?: return emptyList()
                    // The car wraps only to its current owner (it checked the claim's MAC); anything else is ignored.
                    if (id.ownerOf(s.deviceId)?.id != f.recipientId) return emptyList()
                    id.putWrap(s.deviceId, f.recipientId, f.epoch, f.sealed)
                    note(s, now, "차량 키(${f.epoch}번)를 소유자 휴대폰만 열 수 있게 봉인해 보관")
                    emptyList()
                }
                is Frame.Escrow -> {
                    // Kept only if it opens with this server's key; released only to the owner or a registered contact.
                    val minuteKey = runCatching { key.openEscrow(f.sealed) }.getOrNull()?.takeIf { it.size == 32 } ?: return emptyList()
                    // One line per capsule and minute: a key resent before its acknowledgement arrived is not stored again.
                    val held = store.log(s, ESCROW).any { it.startsWith("${f.capsuleId}|${f.epoch}|${f.minute}|") }
                    if (!held && store.appendLog(s, ESCROW, "${f.capsuleId}|${f.epoch}|${f.minute}|${f.sealed}")) {
                        note(s, now, "사고 구간 열쇠 보관 (${f.capsuleId}, ${f.minute}분): 비상 연락처에 저장된 분이 이 사고 기록만 열 수 있음")
                    }
                    minuteKey.fill(0)
                    listOf(MirrorProtocol.encode(Frame.EscrowAck(f.epoch, f.minute)))
                }
                is Frame.Share -> {
                    if (!f.codeHash.matches(Regex("[0-9a-f]{64}"))) return emptyList()
                    if (store.log(s, SHARES).none { it.startsWith("${f.capsuleId}|${f.codeHash}|") }) {
                        store.appendLog(s, SHARES, "${f.capsuleId}|${f.codeHash}|${f.expiresMs}")
                        note(s, now, "증거 요약 QR 공유 (${f.capsuleId}): QR을 가진 사람이 이 기록만 열 수 있음")
                    }
                    listOf(MirrorProtocol.encode(Frame.ShareAck(f.capsuleId)))
                }
                is Frame.Window -> {
                    val plain = runCatching { String(key.open(f.sealed)) }.getOrNull() ?: return emptyList()
                    // Stored under the pseudonym alone: no device, no time of arrival, no stream.
                    store.putLabeled(f.pseudonym, key.encryptAtRest(plain, "window|${f.pseudonym}"))
                    note(s, now, "사고 구간 신호 수신 (가명 처리, 기기와 분리 보관)")
                    listOf(MirrorProtocol.encode(Frame.WindowAck(f.pseudonym)))
                }
                is Frame.WindowDelete -> {
                    if (!store.deleteLabeled(f.pseudonym)) return emptyList()
                    note(s, now, "운전자가 사고 데이터 제공을 그만둠: 보냈던 구간 신호 삭제")
                    listOf(MirrorProtocol.encode(Frame.WindowDeleted(f.pseudonym)))
                }
                is Frame.Seal -> {
                    val seal = runCatching { SealStore.parse(f.sealLine) }.getOrNull() ?: return emptyList()
                    if (!seal.publicKey.contentEquals(publicKey) || !seal.signatureValid()) {
                        note(s, now, "봉인 거부: 이 차량의 키로 서명되지 않음")
                        return emptyList()
                    }
                    if (store.appendLog(s, "seals", f.sealLine, unique = true)) {
                        note(s, now, "차량 봉인 수신: ${seal.capsule.id} ${seal.capsule.trigger} (#${seal.capsule.firstSeq}–#${seal.capsule.lastSeq}, ${if (seal.final) "최종" else "임시"})")
                    }
                    if (seal.capsule.trigger.severe) deadMan.onFrame(s, EscalationLevel.TRIGGERED, true, now)
                    listOf(MirrorProtocol.encode(Frame.SealAck(MirrorProtocol.sealKey(seal))))
                }
                else -> emptyList()
            }
        }

        private fun authenticate(f: Frame.Auth): List<String>? {
            if (f.stream != MirrorProtocol.STREAM_LIVE && f.stream != MirrorProtocol.STREAM_REPLAY) {
                return listOf(MirrorProtocol.encode(Frame.Denied("unknown stream")))
            }
            val valid = runCatching {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(f.publicKey)))
                    update(MirrorProtocol.authText(f.stream, nonce).toByteArray())
                    verify(f.signature)
                }
            }.getOrDefault(false)
            if (!valid) return listOf(MirrorProtocol.encode(Frame.Denied("bad signature")))
            val id = StreamId(MirrorProtocol.deviceId(f.publicKey), f.stream)
            stream = id
            publicKey = f.publicKey
            note(id, clock(), "차량 연결 (기기 키 확인)")
            synchronized(live) { live[id] = this }
            // Where the car should resume: authoritative, even if lower than what the car believes was acknowledged.
            return listOf(MirrorProtocol.encode(Frame.Ready(id.deviceId, key.publicKey)), MirrorProtocol.encode(Frame.Ack(store.contiguousHead(id)))) +
                listOfNotNull(
                    identity?.transferFrame(id.deviceId),
                    identity?.pendingClaim(id.deviceId),
                )
        }
    }

    fun session() = Session()

    /** The car's connection ended (normally, or because the car went silent). */
    fun closed(session: Session) {
        session.stream?.let { s ->
            note(s, clock(), "연결 끊김")
            synchronized(live) { if (live[s] === session) live.remove(s) }
        }
    }

    /** A phone finished claiming [deviceId]'s car: tell the car now if it is connected, else when it reconnects. */
    fun claimed(deviceId: String, frame: Frame.Claimed) {
        val text = MirrorProtocol.encode(frame)
        identity?.setPendingClaim(deviceId, text)
        val sessions = synchronized(live) { live.filterKeys { it.deviceId == deviceId }.values.toList() }
        sessions.forEach { it.pushes.trySend(text) }
        sessions.firstOrNull()?.stream?.let { note(it, clock(), "휴대폰 인증 완료 (${frame.maskedPhone}) — 차량의 확인을 기다리는 중") }
    }

    /** A takeover went through: the car gives its new owner the key epoch it started at the request. */
    fun transferred(t: Identity.Transfer) = claimed(t.deviceId, Frame.Claimed(t.toOwnerId, t.phoneKey, t.mac, t.masked))

    /** A takeover was requested or declined: tell the car now if it is connected (else on reconnect, until it acks). */
    fun transferChanged(deviceId: String) {
        val text = identity?.transferFrame(deviceId) ?: return
        synchronized(live) { live.filterKeys { it.deviceId == deviceId }.values.toList() }.forEach { it.pushes.trySend(text) }
    }


    private fun conflicts(stream: StreamId, result: PutResult, now: Long) {
        if (result.conflicts.isNotEmpty()) note(stream, now, "기존 기록과 어긋나는 기록 ${result.conflicts.size}건 거부 (변조 의심)")
    }

    /** One dead-man step; the server calls it about twice a second. */
    fun tick() {
        val now = clock()
        identity?.dueTransfers()?.forEach { transferred(it) }
        for (action in deadMan.tick(now)) when (action) {
            is DeadMan.Action.Attest -> attest(action.stream, action.episodeStartMs, now)
            is DeadMan.Action.ConsiderNotice -> {
                val severe = action.severeSeen || severeSignature(action.stream, action.episodeStartMs)
                if (!severe) {
                    note(action.stream, now, "${Tuning.DEADMAN_NOTIFY_SILENCE_S}초 무응답, 충돌 수준 증거 없음")
                    continue
                }
                // Recorded, never texted: the family can open it on their page, but nobody is alarmed by a detector.
                val attestation = store.log(action.stream, "attested").lastOrNull() ?: attest(action.stream, action.episodeStartMs, now)
                store.appendLog(action.stream, SEVERE_SILENT, "$now|${attestation.substringBefore("|sig=")}")
                note(action.stream, now, "충돌 수준 사고 뒤 ${Tuning.DEADMAN_NOTIFY_SILENCE_S}초 무응답 — 서버 봉인 기록을 가족이 열람할 수 있음 (문자는 보내지 않음)")
                deadMan.notified(action.stream)
            }
        }
    }

    fun serverPublicKey(): ByteArray = key.publicKey

    fun now(): Long = clock()

    fun streamsOf(deviceId: String): List<StreamId> = store.streams().filter { it.deviceId == deviceId }

    fun log(stream: StreamId, name: String): List<String> = store.log(stream, name)

    /** Records [from]..[to] held, as (seq, entry) with values still encrypted. */
    fun recordsBetween(stream: StreamId, from: Long, to: Long): List<Pair<Long, String>> =
        store.records(stream, from, to).mapNotNull { Record.parse(it) }.map { it.seq to it.entry }

    /** Records [from]..[to] held, as stored chain lines (`seq\tprev\tentry\thash`) for checking in the browser. */
    fun recordLines(stream: StreamId, from: Long, to: Long): List<String> = store.records(stream, from, to)

    /** A capsule as the phone pages list it: the car's seals, or the server's own seal of a severe event gone silent. */
    data class CapsuleInfo(
        val id: String,
        val trigger: String,
        val severe: Boolean,
        val firstSeq: Long,
        val lastSeq: Long,
        val final: Boolean,
        val sealedAtMs: Long,
        val seals: List<String>,
        /** The server's signed seal line when the car went silent (no car seal). */
        val attestation: String?,
        /** The car handed over the minute keys (severe capsules): the family can open it. */
        val escrowed: Boolean,
        /** The driver's answer to "보관할까요?": "kept", "released", "unanswered", or null if not asked. */
        val answer: String?,
        /** Key epochs of the encrypted records inside it (whose time it is). */
        val epochs: Set<Int>,
    )

    /**
     * Every capsule of [stream], newest first. Scans the stream's records for the answers and epochs; at the scale of
     * one car that is a few MB, and a cloud store keeps these per batch instead.
     */
    fun capsules(stream: StreamId): List<CapsuleInfo> {
        val version = listOf(store.highestSeq(stream), store.log(stream, "seals").size, store.log(stream, SEVERE_SILENT).size, store.log(stream, ESCROW).size)
        synchronized(capsuleCache) { capsuleCache[stream]?.let { (v, c) -> if (v == version) return c } }
        return scanCapsules(stream).also { synchronized(capsuleCache) { capsuleCache[stream] = version to it } }
    }

    /** Capsule and drive lists per stream, kept until the stream or its logs change (pages and the dashboard poll). */
    private val capsuleCache = HashMap<StreamId, Pair<List<Any>, List<CapsuleInfo>>>()
    private val driveCache = HashMap<StreamId, Pair<List<Any>, List<Pair<Drive, Set<Int>>>>>()

    private fun scanCapsules(stream: StreamId): List<CapsuleInfo> {
        val escrowed = store.log(stream, ESCROW).map { it.substringBefore('|') }.toSet()
        val answers = HashMap<String, String>()
        val epochBySeq = java.util.TreeMap<Long, Int>()
        for ((seq, entry) in recordsBetween(stream, 0, store.highestSeq(stream))) {
            val f = entry.split("|")
            if (f[0] == "E" && f.getOrNull(2) == "ANSWER") {
                answers[f[3]] = when {
                    f.getOrNull(5) == "false" -> "unanswered"
                    f[4] == "true" -> "kept"
                    else -> "released"
                }
            }
            recordEpoch(entry)?.let { epochBySeq[seq] = it }
        }
        fun epochs(from: Long, to: Long) = epochBySeq.subMap(from, true, to, true).values.toSet()
        val fromCar = store.log(stream, "seals").mapNotNull { line -> runCatching { SealStore.parse(line) to line }.getOrNull() }
            .groupBy { it.first.capsule.id }
            .map { (id, seals) ->
                val (latest, _) = seals.maxWith(compareBy({ it.first.final }, { it.first.sealedAtWallMillis }))
                val c = latest.capsule
                CapsuleInfo(
                    id, c.trigger.name, c.trigger.severe, c.firstSeq, c.lastSeq, latest.final, latest.sealedAtWallMillis,
                    seals.map { it.second }, null, id in escrowed, answers[id], epochs(c.firstSeq, c.lastSeq),
                )
            }
        val attested = store.log(stream, "attested")
        val fromServer = store.log(stream, SEVERE_SILENT).mapNotNull { line ->
            val atMs = line.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
            val text = line.substringAfter('|')
            val full = attested.firstOrNull { it.startsWith("$text|sig=") } ?: return@mapNotNull null
            val f = text.split("|")
            val from = f[2].toLong()
            val to = f[3].toLong()
            CapsuleInfo("server-$atMs", "SERVER_SEAL", true, from, to, true, atMs, emptyList(), full, false, null, epochs(from, to))
        }
        return (fromCar + fromServer).sortedByDescending { it.sealedAtMs }
    }

    /** The minute keys the car handed over for a severe capsule, opened: (epoch, minute, base64 key). */
    fun minuteKeys(stream: StreamId, capsuleId: String): List<Triple<Int, Long, String>> =
        store.log(stream, ESCROW).map { it.split("|", limit = 4) }.filter { it[0] == capsuleId }
            .mapNotNull { f -> runCatching { Triple(f[1].toInt(), f[2].toLong(), Base64.getEncoder().encodeToString(key.openEscrow(f[3]))) }.getOrNull() }
            .distinctBy { it.first to it.second }

    /** Whether [code] (from a capsule's QR link) is a live share of [capsuleId]. */
    fun shareValid(stream: StreamId, capsuleId: String, code: String): Boolean {
        val hash = com.americankimchilabs.whitebox.data.CapsuleShares.codeHash(code)
        return store.log(stream, SHARES).any { l ->
            val f = l.split("|")
            f.size == 3 && f[0] == capsuleId && java.security.MessageDigest.isEqual(f[1].toByteArray(), hash.toByteArray()) && (f[2].toLongOrNull() ?: 0) > clock()
        }
    }

    fun logOpened(stream: StreamId, contactId: String, capsuleId: String) {
        store.appendLog(stream, OPENED, "${clock()}|$contactId|$capsuleId")
    }

    /** The server's signed anchors of the chain head at or after [seq]: the earliest and the newest. */
    fun anchorsCovering(stream: StreamId, seq: Long): List<String> {
        val covering = store.log(stream, "anchors").filter { (it.split("|").getOrNull(2)?.toLongOrNull() ?: -1) >= seq }
        return listOfNotNull(covering.firstOrNull(), covering.lastOrNull()).distinct()
    }

    /** The crash-signal copies the car sealed to the server for records [from]..[to], opened. */
    fun signalCopies(stream: StreamId, from: Long, to: Long): List<Pair<Long, String>> =
        store.log(stream, "signal-copies").mapNotNull { line ->
            val seq = line.substringBefore('|').toLongOrNull()?.takeIf { it in from..to } ?: return@mapNotNull null
            runCatching { seq to key.decryptAtRest(line.substringAfter('|'), "copy|$stream|$seq") }.getOrNull()
        }.distinctBy { it.first }

    /** One continuous stretch of recording. [odometer]: its first and last odometer records (encrypted). */
    data class Drive(
        val firstSeq: Long,
        val lastSeq: Long,
        val startMs: Long,
        val endMs: Long,
        val records: Int,
        val odometer: List<Pair<Long, String>>,
        val capsules: List<String>,
        val bookmarks: Int,
        val hardBrakes: Int,
    )

    /**
     * The stream cut into drives at recording stops and at gaps of [Tuning.DRIVE_GAP_MS] or more, by the records'
     * own (readable) times; only drives with records in an epoch [mine] accepts (a sold car's other owner's time is
     * not listed). Newest first.
     */
    fun drives(stream: StreamId, mine: (Int) -> Boolean): List<Drive> {
        val version = listOf(store.highestSeq(stream), store.log(stream, "seals").size)
        val all = synchronized(driveCache) { driveCache[stream]?.takeIf { it.first == version }?.second }
            ?: scanDrives(stream).also { synchronized(driveCache) { driveCache[stream] = version to it } }
        return all.filter { (_, epochs) -> epochs.any(mine) }.map { it.first }
    }

    /** Every drive of the stream with the key epochs of its records (whose time it is), newest first. */
    private fun scanDrives(stream: StreamId): List<Pair<Drive, Set<Int>>> {
        val capsulesBySeq = store.log(stream, "seals").mapNotNull { runCatching { SealStore.parse(it).capsule }.getOrNull() }.associateBy { it.id }.values
        val out = mutableListOf<Pair<Drive, Set<Int>>>()
        var cur: MutableList<Pair<Long, String>>? = null
        var lastAt = Long.MIN_VALUE
        fun close() {
            val d = cur ?: return
            cur = null
            val epochs = d.mapNotNull { recordEpoch(it.second) }.toSet()
            if (epochs.isEmpty()) return
            val times = d.mapNotNull { runCatching { EntryCodec.timestamp(it.second).wallMillis }.getOrNull() }
            val odo = d.filter { it.second.startsWith("S|ODOMETER|") }
            val first = d.first().first
            val last = d.last().first
            out += Drive(
                first, last, times.min(), times.max(), d.size, listOfNotNull(odo.firstOrNull(), odo.lastOrNull()).distinct(),
                capsulesBySeq.filter { it.firstSeq in first..last }.map { it.id },
                d.count { it.second.contains("|TRIGGER|MANUAL|") }, d.count { it.second.contains("|TRIGGER|HARD_BRAKE|") },
            ) to epochs
        }
        for (r in recordsBetween(stream, 0, store.highestSeq(stream))) {
            val at = runCatching { EntryCodec.timestamp(r.second).wallMillis }.getOrNull() ?: continue
            if (cur != null && at - lastAt >= Tuning.DRIVE_GAP_MS) close()
            (cur ?: mutableListOf<Pair<Long, String>>().also { cur = it }) += r
            lastAt = at
            if (r.second.startsWith("R|") && r.second.contains("|STOPPED")) close()
        }
        close()
        return out.reversed()
    }

    /** The newest [n] records of [stream] held, as (seq, entry) with values still encrypted. */
    fun lastRecords(stream: StreamId, n: Int): List<Pair<Long, String>> {
        val hi = store.highestSeq(stream)
        if (hi < 0) return emptyList()
        return store.records(stream, maxOf(0, hi - n + 1), hi).mapNotNull { Record.parse(it) }.map { it.seq to it.entry }
    }

    /** What the dashboard shows for each mirrored stream. */
    data class Status(
        val stream: StreamId,
        val level: EscalationLevel?,
        val silentForMs: Long?,
        val records: Int,
        val contiguousHead: Long,
        val highestSeq: Long,
        val conflicts: Int,
        val seals: List<String>,
        val attested: List<String>,
        /** A severe event went silent (`atMs|attestation`): recorded for the family's page, never texted. */
        val severeSilent: List<String>,
        val lastAnchor: String?,
        val events: List<Pair<Long, String>>,
    )

    fun statuses(): List<Status> {
        val now = clock()
        return store.streams().map { s ->
            Status(
                s, deadMan.level(s), deadMan.lastFrameMs(s)?.let { now - it }, store.count(s), store.contiguousHead(s),
                store.highestSeq(s), store.conflicts(s), store.log(s, "seals"), store.log(s, "attested"), store.log(s, SEVERE_SILENT),
                store.log(s, "anchors").lastOrNull(), synchronized(this) { events[s]?.toList() ?: emptyList() },
            )
        }
    }

    /**
     * Server-attested seal over what was received of the episode, from [Tuning.CAPSULE_PRE_WINDOW_S] before it began:
     * `ATTEST|stream|firstSeq|lastSeq|lastHash|contiguousHead|missing|reason|serverMillis|sig=…`.
     */
    private fun attest(stream: StreamId, episodeStartMs: Long, now: Long): String {
        val to = store.highestSeq(stream)
        val from = windowStart(stream, episodeStartMs, now)
        val held = store.records(stream, from, to).size
        val text = "ATTEST|$stream|$from|$to|${store.hashAt(stream, to)}|${store.contiguousHead(stream)}|${(to - from + 1) - held}|DEADMAN|$now"
        val line = "$text|sig=${key.sign(text)}"
        store.appendLog(stream, "attested", line)
        note(stream, now, "서버 봉인: ${Tuning.DEADMAN_SEAL_SILENCE_S}초 무응답 — 받은 기록 #$from–#$to 에 서버가 서명")
        return line
    }

    private fun anchor(stream: StreamId, head: Long, now: Long) {
        if (head < 0) return
        val text = "ANCHOR|$stream|$head|${store.hashAt(stream, head)}|$now"
        store.appendLog(stream, "anchors", "$text|sig=${key.sign(text)}")
    }

    /**
     * First held seq of the episode plus [Tuning.CAPSULE_PRE_WINDOW_S] before it, by the records' own timestamps (the
     * car's clock): arrival time is no guide, since a backlog drained after a tunnel arrives all at once.
     */
    private fun windowStart(stream: StreamId, episodeStartMs: Long, now: Long): Long {
        val to = store.highestSeq(stream)
        val toAt = carMs(store.records(stream, to, to).singleOrNull()) ?: return to
        val cutoff = toAt - (now - episodeStartMs) - Tuning.CAPSULE_PRE_WINDOW_S * 1_000L
        var from = to
        var upper = to
        while (upper >= 0) {
            val chunk = store.records(stream, maxOf(0, upper - WINDOW_SCAN), upper).asReversed()
            if (chunk.isEmpty()) break
            for (line in chunk) {
                val at = carMs(line) ?: continue
                if (at < cutoff) return from
                from = Record.parse(line)?.seq ?: from
            }
            upper -= WINDOW_SCAN + 1
        }
        return from
    }

    /** The car's elapsed-time clock of a record, in ms (readable without the record's key). */
    private fun carMs(line: String?): Long? = line?.let { Record.parse(it) }
        ?.let { runCatching { EntryCodec.timestamp(it.entry).elapsedNanos / 1_000_000 }.getOrNull() }

    /**
     * A crash-signal copy the car sealed to this server, kept (encrypted at rest) only if it matches the record it came
     * with, so a copy cannot claim something the chain does not hold.
     */
    private fun keepCopy(stream: StreamId, r: Record, sealed: String) {
        val plain = runCatching { String(key.open(sealed)) }.getOrNull() ?: return
        if (EntryCodec.timestamp(plain) != EntryCodec.timestamp(r.entry) || plain.substringBefore('|') != r.entry.substringBefore('|')) return
        store.appendLog(stream, "signal-copies", "${r.seq}|${key.encryptAtRest(plain, "copy|$stream|${r.seq}")}")
    }

    /** A crash-level trigger recorded by the car. */
    private fun severe(r: Record): Boolean {
        val e = runCatching { EntryCodec.decode(r.entry) }.getOrNull() as? ChainEntry.Escalation ?: return false
        return (e.event as? EscalationEvent.Triggered)?.trigger?.severe == true
    }

    /**
     * The car may lose power between the crash-level speed drop and writing its own trigger: replay what was
     * received of the episode through the same escalation engine the car runs.
     */
    private fun severeSignature(stream: StreamId, episodeStartMs: Long): Boolean {
        val from = windowStart(stream, episodeStartMs, clock())
        val engine = EscalationEngine()
        // Records are end-to-end encrypted; plain ones (older chains) are read directly, encrypted ones through the
        // crash-signal copies the car sealed to this server.
        val copies = store.log(stream, "signal-copies").mapNotNull { l ->
            val seq = l.substringBefore('|').toLong()
            runCatching { seq to key.decryptAtRest(l.substringAfter('|'), "copy|$stream|$seq") }.getOrNull()
        }.toMap()
        return store.records(stream, from, store.highestSeq(stream)).asSequence()
            .mapNotNull { Record.parse(it) }
            .mapNotNull { r -> runCatching { DriveEvent(r.seq, EntryCodec.decode(copies[r.seq] ?: r.entry)) }.getOrNull() }
            .any { event ->
                engine.onEvent(event).any { o ->
                    o is EngineOutput.Append && (o.event as? EscalationEvent.Triggered)?.trigger?.severe == true
                }
            }
    }
}

/** The key epoch of a record's encrypted value, or null for a plain record (markers). */
internal fun recordEpoch(entry: String): Int? {
    val f = entry.split("|")
    val field = when (f[0]) {
        "S" -> f.getOrNull(2)
        "L" -> f.getOrNull(1)
        else -> null
    } ?: return null
    return if (com.americankimchilabs.whitebox.data.RecordCipher.isSealed(field)) com.americankimchilabs.whitebox.data.RecordCipher.epochOf(field) else null
}
