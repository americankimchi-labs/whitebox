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
 * server-signed line (so the car's chain head is held outside the car), and the dead-man switch seals and notifies
 * from what was received.
 */
private const val WINDOW_SCAN = 2_000L
private const val EVENTS_KEPT = 30

class MirrorService(
    private val store: StreamStore,
    private val key: ServerKey,
    private val notifier: ContactNotifier,
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
                    MirrorProtocol.encode(Frame.Rotate).takeIf { identity?.pendingRotation(id.deviceId) == true },
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

    /** An emergency contact was removed: each of the owner's cars starts a new key epoch (now, or when it reconnects). */
    fun rotate(deviceIds: List<String>) {
        val text = MirrorProtocol.encode(Frame.Rotate)
        synchronized(live) { live.filterKeys { it.deviceId in deviceIds }.values.toList() }.forEach { it.pushes.trySend(text) }
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
                    note(action.stream, now, "${Tuning.DEADMAN_NOTIFY_SILENCE_S}초 무응답, 충돌 수준 증거 없음 — 비상 알림 보내지 않음")
                    continue
                }
                val attestation = store.log(action.stream, "attested").lastOrNull() ?: attest(action.stream, action.episodeStartMs, now)
                notifier.notify(EmergencyNotice(action.stream, attestation, "silent after a severe event", now))
                store.appendLog(action.stream, "notices", "$now|${attestation.substringBefore("|sig=")}")
                note(action.stream, now, "비상 연락처 알림: ${Tuning.DEADMAN_NOTIFY_SILENCE_S}초 무응답 + 충돌 수준 사고 (현재는 notices.log에 기록)")
                deadMan.notified(action.stream)
            }
        }
    }

    fun serverPublicKey(): ByteArray = key.publicKey

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
        val notices: List<String>,
        val lastAnchor: String?,
        val events: List<Pair<Long, String>>,
    )

    fun statuses(): List<Status> {
        val now = clock()
        return store.streams().map { s ->
            Status(
                s, deadMan.level(s), deadMan.lastFrameMs(s)?.let { now - it }, store.count(s), store.contiguousHead(s),
                store.highestSeq(s), store.conflicts(s), store.log(s, "seals"), store.log(s, "attested"), store.log(s, "notices"),
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
