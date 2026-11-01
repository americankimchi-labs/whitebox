package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.data.AesGcmRecordCipher
import com.americankimchilabs.whitebox.data.CapsuleSeal
import com.americankimchilabs.whitebox.data.EntryCodec
import com.americankimchilabs.whitebox.data.EpochRecordCipher
import com.americankimchilabs.whitebox.data.FileEventStore
import com.americankimchilabs.whitebox.data.MemoryKeyRing
import com.americankimchilabs.whitebox.data.RecordCipher
import com.americankimchilabs.whitebox.data.SealStore
import com.americankimchilabs.whitebox.data.Signer
import com.americankimchilabs.whitebox.domain.AudioEvent
import com.americankimchilabs.whitebox.domain.BookmarkSource
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.Chirp
import com.americankimchilabs.whitebox.domain.ControlLine
import com.americankimchilabs.whitebox.domain.CruiseState
import com.americankimchilabs.whitebox.domain.DriveEvent
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.EvidenceReadings
import com.americankimchilabs.whitebox.domain.EvidenceTimeline
import com.americankimchilabs.whitebox.domain.Gap
import com.americankimchilabs.whitebox.domain.GapCause
import com.americankimchilabs.whitebox.domain.GearPosition
import com.americankimchilabs.whitebox.domain.HandoverEndReason
import com.americankimchilabs.whitebox.domain.HandoverEvent
import com.americankimchilabs.whitebox.domain.LibraryEvent
import com.americankimchilabs.whitebox.domain.Tier
import com.americankimchilabs.whitebox.domain.HandoverEventKind
import com.americankimchilabs.whitebox.domain.HandoverSource
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import com.americankimchilabs.whitebox.domain.Lamp
import com.americankimchilabs.whitebox.domain.LocationFix
import com.americankimchilabs.whitebox.domain.RecorderEvent
import com.americankimchilabs.whitebox.domain.RecorderPermission
import com.americankimchilabs.whitebox.domain.RoadContextType
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.StartReason
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.TurnSignalState
import com.americankimchilabs.whitebox.domain.VehicleSignal
import com.americankimchilabs.whitebox.domain.VoiceLine
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Writes what the car's own code produces — chain lines in every record format, their decoded values, an evidence
 * timeline, seal and anchor signatures, minute keys, the sync chirp — to build/web-vectors/vectors.json. The phone
 * pages' JavaScript is tested against it (src/test/web, run by the webTest task), so the browser decodes, checks and
 * draws exactly what the car wrote.
 */
class WebVectorsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ts(ms: Long) = Timestamp(1_000_000_000_000L + ms * 1_000_000, 1_700_000_000_000 + ms)

    private class JdkSigner : Signer {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        override val publicKey: ByteArray = pair.public.encoded
        override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(data)
            sign()
        }
    }

    private fun shapes(): List<ChainEntry> = listOf(
        ChainEntry.Recorder(RecorderEvent.RecordingStarted(StartReason.BOOT, null, 0), ts(0)),
        ChainEntry.Recorder(RecorderEvent.RecordingStarted(StartReason.STICKY_RESTART, Gap(GapCause.PROCESS_DIED, 1, 2, 3, false), 2), ts(1)),
        ChainEntry.Signal(SignalSample(VehicleSignal.BrakePedal(true), ts(2))),
        ChainEntry.Signal(SignalSample(VehicleSignal.TurnSignal(TurnSignalState.LEFT), ts(3))),
        ChainEntry.Signal(SignalSample(VehicleSignal.SteeringAngle(-12.5f), ts(4))),
        ChainEntry.Signal(SignalSample(VehicleSignal.Gear(GearPosition.DRIVE), ts(5), sourceElapsedNanos = 3_999_000)),
        ChainEntry.Signal(SignalSample(VehicleSignal.Speed(16.666668f), ts(6), sourceElapsedNanos = 4_900_000)),
        ChainEntry.Signal(SignalSample(VehicleSignal.AirbagDeployed("ROW_1_LEFT", true), ts(7))),
        ChainEntry.Location(LocationFix(37.4395, 127.127, 5f, 1_700_000_000_007, ts(8))),
        ChainEntry.Location(LocationFix(37.4395, 127.127, null, 1_700_000_000_008, ts(9))),
        ChainEntry.Recorder(RecorderEvent.RecordingStopped, ts(10)),
        ChainEntry.Recorder(RecorderEvent.PermissionDegraded(RecorderPermission.BACKGROUND_LOCATION), ts(11)),
        ChainEntry.Recorder(RecorderEvent.PermissionRestored(RecorderPermission.BACKGROUND_LOCATION), ts(11)),
        ChainEntry.Escalation(EscalationEvent.LevelChanged(EscalationLevel.ALERT, 5), ts(12)),
        ChainEntry.Escalation(EscalationEvent.Triggered(CapsuleTrigger.REAR_SHOVE, 41, 7.2f), ts(13)),
        ChainEntry.Escalation(EscalationEvent.IncidentOffered(IncidentCapsule("I7", CapsuleTrigger.HARD_BRAKE, 7, 99)), ts(14)),
        ChainEntry.Escalation(EscalationEvent.IncidentAnswered("I7", false), ts(15)),
        ChainEntry.Escalation(EscalationEvent.IncidentAnswered("I8", true, answered = false), ts(15)),
        ChainEntry.Audio(AudioEvent.Bookmark(BookmarkSource.ASSISTANT), ts(16)),
        ChainEntry.Audio(AudioEvent.Spoke(VoiceLine.KEEP_QUESTION, complete = false), ts(16)),
        ChainEntry.Audio(AudioEvent.Beacon(3, ts(15)), ts(16)),
        ChainEntry.Handover(HandoverEvent.Armed(HandoverSource.PHONE, 1_700_000_000_017), ts(17)),
        ChainEntry.Handover(HandoverEvent.Started(7, HandoverSource.PHONE, 1_700_000_000_017), ts(17)),
        ChainEntry.Handover(HandoverEvent.Observed(HandoverEventKind.SPEEDING, 95.5f, 80f), ts(17)),
        ChainEntry.Handover(HandoverEvent.Observed(HandoverEventKind.NO_ROUTE, 0f, null), ts(17)),
        ChainEntry.Handover(HandoverEvent.Ending(HandoverEndReason.IGNITION_OFF), ts(17)),
        ChainEntry.Handover(HandoverEvent.Resumed, ts(17)),
        ChainEntry.Handover(HandoverEvent.Ended(HandoverEndReason.ARRIVED, "H7"), ts(17)),
        ChainEntry.Library(LibraryEvent.PlanChanged(Tier.FREE, Tier.PRO), ts(18)),
        ChainEntry.Library(LibraryEvent.PlanChanged(Tier.PRO, null), ts(18)),
        ChainEntry.Library(LibraryEvent.Left("C3"), ts(18)),
        ChainEntry.Library(LibraryEvent.Returned("C3"), ts(18)),
        ChainEntry.Library(LibraryEvent.Extended("C4", 1_800_000_000_000), ts(18)),
        ChainEntry.Recorder(RecorderEvent.Pruned(120, 1_700_000_000_001, unsent = true), ts(18)),
        ChainEntry.Recorder(RecorderEvent.Slept(754_000), ts(18)),
        ChainEntry.Signal(SignalSample(VehicleSignal.AirbagDeployed("ROW_1_LEFT", true, test = true), ts(18))),
        ChainEntry.Escalation(EscalationEvent.Triggered(CapsuleTrigger.TEST_AIRBAG, 40, 0f), ts(18)),
        ChainEntry.Handover(HandoverEvent.Cancelled(HandoverSource.CAR, HandoverEndReason.EXPIRED), ts(17)),
    ) + listOf(
        VehicleSignal.CruiseControl(CruiseState.ACTIVATED),
        VehicleSignal.CruiseTargetSpeed(13.8889f),
        VehicleSignal.Odometer(12345.6f),
        VehicleSignal.Door("ROW_1_LEFT", true),
        VehicleSignal.SeatBelt("ROW_1_RIGHT", false),
        VehicleSignal.SeatOccupancy("ROW_2_LEFT", true),
        VehicleSignal.Light(Lamp.HIGH_BEAM, true),
        VehicleSignal.Wiper("FRONT_WINDSHIELD", "CONTINUOUS_LEVEL_1"),
        VehicleSignal.Window("ROW_1_LEFT", 7),
        VehicleSignal.ParkingBrake(true),
        VehicleSignal.RoadContext(RoadContextType.SPEED_LIMIT, "50"),
        VehicleSignal.RoadContext(RoadContextType.CAUTION_POINT, "SCHOOL_ZONE"),
        VehicleSignal.RoadGrade(-6.25f),
        VehicleSignal.EvEfficiency(5.4f),
        VehicleSignal.AcceleratorPedal(42.5f),
        VehicleSignal.BrakePressure(87.5f),
    ).mapIndexed { i, s -> ChainEntry.Signal(SignalSample(s, ts(17L + i))) }

    /** A capsule worth drawing: speed and steering lanes, brake, turn signal, gear, a flash, context, the impact. */
    private fun crash(): List<ChainEntry> = buildList {
        add(ChainEntry.Recorder(RecorderEvent.RecordingStarted(StartReason.BOOT, null, 0), ts(0)))
        add(ChainEntry.Signal(SignalSample(VehicleSignal.Gear(GearPosition.DRIVE), ts(100))))
        add(ChainEntry.Signal(SignalSample(VehicleSignal.BrakePedal(false), ts(150))))
        add(ChainEntry.Signal(SignalSample(VehicleSignal.SeatBelt("ROW_1_LEFT", true), ts(200))))
        add(ChainEntry.Signal(SignalSample(VehicleSignal.SeatOccupancy("ROW_1_RIGHT", true), ts(205))))
        add(ChainEntry.Signal(SignalSample(VehicleSignal.Door("ROW_1_LEFT", false), ts(210))))
        add(ChainEntry.Signal(SignalSample(VehicleSignal.Light(Lamp.LOW_BEAM, true), ts(220))))
        add(ChainEntry.Signal(SignalSample(VehicleSignal.RoadContext(RoadContextType.SPEED_LIMIT, "50"), ts(230))))
        add(ChainEntry.Signal(SignalSample(VehicleSignal.Wiper("FRONT_WINDSHIELD", "AUTO"), ts(240))))
        for (i in 0 until 160) {
            val ms = 5_000L + i * 100
            add(ChainEntry.Signal(SignalSample(VehicleSignal.Speed((60f - (if (ms > 18_000) (ms - 18_000) / 100f * 4 else 0f)).coerceAtLeast(0f) / 3.6f), ts(ms))))
            add(ChainEntry.Signal(SignalSample(VehicleSignal.SteeringAngle(if (ms in 16_000..17_500) (ms - 16_000) / 10f else 0f), ts(ms + 50))))
            if (ms == 12_000L) add(ChainEntry.Signal(SignalSample(VehicleSignal.TurnSignal(TurnSignalState.LEFT), ts(ms + 10))))
            if (ms == 15_000L) add(ChainEntry.Signal(SignalSample(VehicleSignal.Light(Lamp.HIGH_BEAM, true), ts(ms + 20))))
            if (ms == 15_500L) add(ChainEntry.Signal(SignalSample(VehicleSignal.Light(Lamp.HIGH_BEAM, false), ts(ms + 20))))
            if (ms == 16_500L) add(ChainEntry.Signal(SignalSample(VehicleSignal.BrakePedal(true), ts(ms + 30))))
            if (ms == 19_000L) add(ChainEntry.Signal(SignalSample(VehicleSignal.TurnSignal(TurnSignalState.OFF), ts(ms + 10))))
            if (ms == 20_000L) add(ChainEntry.Signal(SignalSample(VehicleSignal.Light(Lamp.HAZARD, true), ts(ms + 40))))
            if (ms == 20_500L) add(ChainEntry.Signal(SignalSample(VehicleSignal.Door("ROW_1_LEFT", true), ts(ms + 60))))
        }
    }

    @Test
    fun writeVectorsForTheViewer() {
        val ring = MemoryKeyRing()
        ring.rotate("o1")
        var wall = 1_700_000_000_000L
        val chain = FileEventStore(tmp.newFolder(), cipher = EpochRecordCipher(ring) { wall })
        val entries = shapes()
        entries.forEachIndexed { i, e ->
            if (i == entries.size / 2) wall += 60_000 // a second minute, with its own key
            chain.append(e)
        }
        val lines = chain.records(0, entries.size - 1L).toMutableList()
        // Older record formats the car may still hold: v1 (epoch 0's root key) and v2 (a named epoch, no minute).
        val legacy = listOf(
            EntryCodec.encode(ChainEntry.Signal(SignalSample(VehicleSignal.BrakePedal(true), ts(90))), AesGcmRecordCipher(ring.key(0)!!)),
            EntryCodec.encode(ChainEntry.Signal(SignalSample(VehicleSignal.SteeringAngle(3.5f), ts(91))), AesGcmRecordCipher(ring.key(1)!!))
                .split("|").toMutableList().also { it[2] = "v2.1." + it[2].removePrefix("v1.") }.joinToString("|"),
        )

        // The crash capsule: its chain, the car's seal, the server's anchor.
        val signer = JdkSigner()
        val capsuleStore = FileEventStore(tmp.newFolder(), cipher = EpochRecordCipher(ring) { wall })
        val crash = crash()
        crash.forEach { capsuleStore.append(it) }
        val last = crash.size - 1L
        val causeSeq = (0..last).first { (crash[it.toInt()] as? ChainEntry.Signal)?.sample?.signal.let { s -> s is VehicleSignal.Speed && s.metersPerSecond * 3.6f < 50f } }.toLong()
        val timeline = EvidenceTimeline.build(crash.mapIndexed { i, e -> DriveEvent(i.toLong(), e) }, causeSeq)
        val capsule = IncidentCapsule("C1", CapsuleTrigger.DELTA_V, 0, last)
        val seal = CapsuleSeal.create(capsule, capsuleStore.hashAt(last), true, 1_700_000_100_000, signer)
        val server = ServerKey(tmp.newFolder())
        val anchorText = "ANCHOR|car:live|$last|${capsuleStore.hashAt(last).joinToString("") { "%02x".format(it) }}|1700000100500"
        val minute = 1_700_000_000_000L / 60_000

        val b64 = Base64.getEncoder()
        val vectors = obj(
            "roots" to obj(*(0..ring.current).map { it.toString() to b64.encodeToString(ring.key(it)) }.toTypedArray()),
            "lines" to lines,
            "decoded" to entries.map(::decodedJson),
            "legacy" to legacy,
            "legacyDecoded" to listOf(obj("kind" to "BRAKE_PEDAL", "value" to true), obj("kind" to "STEERING_ANGLE", "value" to 3.5)),
            "minute" to obj("epoch" to ring.current, "minute" to minute, "key" to b64.encodeToString(RecordCipher.minuteKey(ring.key(ring.current)!!, minute))),
            "capsule" to obj(
                "records" to capsuleStore.records(0, last),
                "causeSeq" to causeSeq,
                "timeline" to timelineJson(timeline),
                // The plain readings the car shows (its top three) and the phone shows (all): the same sentences.
                "readings" to EvidenceReadings.of(crash.mapIndexed { i, e -> DriveEvent(i.toLong(), e) }, causeSeq, timeline.context)
                    .map { obj("text" to it.text, "note" to it.note) },
                "seal" to SealStore.line(seal),
                "anchor" to "$anchorText|sig=${server.sign(anchorText)}",
                "serverKey" to b64.encodeToString(server.publicKey),
            ),
            "clip" to run {
                // A clip as the car encrypts it (built-in camera): the phone must open it with the same derivation.
                val plain = java.io.File.createTempFile("clip", ".bin").apply { writeBytes(ByteArray(5_000) { (it % 253).toByte() }); deleteOnExit() }
                val sealed = java.io.File.createTempFile("clip", ".enc").apply { deleteOnExit() }
                val minuteKey = ByteArray(32) { 9 }
                com.americankimchilabs.whitebox.data.ClipFormat.encrypt(com.americankimchilabs.whitebox.data.ClipFormat.key(minuteKey, "carClip0001xyz"), plain, sealed)
                obj("minuteKey" to b64.encodeToString(minuteKey), "clipId" to "carClip0001xyz", "sealed" to b64.encodeToString(sealed.readBytes()), "plain" to b64.encodeToString(plain.readBytes()))
            },
            "chirp" to obj("sampleRate" to 48_000, "length" to Chirp.samples(48_000).size, "head" to Chirp.samples(48_000).take(400).map { it.toDouble() }),
        )
        val out = File("build/web-vectors").apply { mkdirs() }.resolve("vectors.json")
        out.writeText(vectors.text)
        assertTrue(out.length() > 10_000)
    }

    private fun at(t: Timestamp) = obj("ns" to t.elapsedNanos, "wall" to t.wallMillis)

    /** A chain entry in the shape the browser's codec decodes it to. */
    private fun decodedJson(e: ChainEntry): Json = when (e) {
        is ChainEntry.Signal -> obj("type" to "S", "kind" to e.sample.signal.kind.name, "value" to signalJson(e.sample.signal), "at" to at(e.at), "src" to e.sample.sourceElapsedNanos)
        is ChainEntry.Location -> with(e.fix) { obj("type" to "L", "lat" to latitude, "lon" to longitude, "acc" to accuracyMeters, "gps" to gpsTimeMillis, "at" to at(e.at)) }
        is ChainEntry.Recorder -> when (val r = e.event) {
            is RecorderEvent.RecordingStarted -> obj(
                "type" to "R", "event" to "STARTED", "reason" to r.reason.name, "restartCount" to r.restartCount, "at" to at(e.at),
                "gap" to r.gap?.let { obj("cause" to it.cause.name, "fromWall" to it.fromWallMillis, "untilWall" to it.untilWallMillis, "durationMs" to it.durationMillis, "acrossBoots" to it.acrossBoots) },
            )
            RecorderEvent.RecordingStopped -> obj("type" to "R", "event" to "STOPPED", "at" to at(e.at))
            is RecorderEvent.PermissionDegraded -> obj("type" to "R", "event" to "DEGRADED", "permission" to r.permission.name, "at" to at(e.at))
            is RecorderEvent.PermissionRestored -> obj("type" to "R", "event" to "RESTORED", "permission" to r.permission.name, "at" to at(e.at))
            is RecorderEvent.Pruned -> obj("type" to "R", "event" to "PRUNED", "throughSeq" to r.throughSeq, "throughWall" to r.throughWall, "unsent" to r.unsent, "at" to at(e.at))
            is RecorderEvent.Slept -> obj("type" to "R", "event" to "SLEPT", "sleptMs" to r.sleptMillis, "at" to at(e.at))
        }
        is ChainEntry.Escalation -> when (val v = e.event) {
            is EscalationEvent.LevelChanged -> obj("type" to "E", "event" to "LEVEL", "level" to v.level.name, "causeSeq" to v.causeSeq, "at" to at(e.at))
            is EscalationEvent.Triggered -> obj("type" to "E", "event" to "TRIGGER", "trigger" to v.trigger.name, "causeSeq" to v.causeSeq, "measured" to v.measured, "at" to at(e.at))
            is EscalationEvent.IncidentOffered -> obj("type" to "E", "event" to "OFFER", "capsule" to with(v.capsule) { obj("id" to id, "trigger" to trigger.name, "firstSeq" to firstSeq, "lastSeq" to lastSeq) }, "at" to at(e.at))
            is EscalationEvent.IncidentAnswered -> obj("type" to "E", "event" to "ANSWER", "capsuleId" to v.capsuleId, "keep" to v.keep, "answered" to v.answered, "at" to at(e.at))
        }
        is ChainEntry.Audio -> when (val a = e.event) {
            is AudioEvent.Bookmark -> obj("type" to "A", "event" to "BOOKMARK", "source" to a.source.name, "at" to at(e.at))
            is AudioEvent.Spoke -> obj("type" to "A", "event" to "SPOKE", "line" to a.line.name, "complete" to a.complete, "at" to at(e.at))
            is AudioEvent.Beacon -> obj("type" to "A", "event" to "BEACON", "chirpId" to a.chirpId, "playedAt" to at(a.playedAt), "at" to at(e.at))
        }
        is ChainEntry.Handover -> when (val h = e.event) {
            is HandoverEvent.Armed -> obj("type" to "H", "event" to "ARMED", "by" to h.by.name, "requestedAtWall" to h.requestedAtWall, "at" to at(e.at))
            is HandoverEvent.Cancelled -> obj("type" to "H", "event" to "CANCELLED", "by" to h.by.name, "reason" to h.reason.name, "at" to at(e.at))
            is HandoverEvent.Started -> obj("type" to "H", "event" to "STARTED", "fromSeq" to h.fromSeq, "by" to h.by.name, "requestedAtWall" to h.requestedAtWall, "at" to at(e.at))
            is HandoverEvent.Observed -> obj("type" to "H", "event" to "EVENT", "kind" to h.kind.name, "value" to h.value, "limit" to h.limit, "at" to at(e.at))
            is HandoverEvent.Ending -> obj("type" to "H", "event" to "ENDING", "reason" to h.reason.name, "at" to at(e.at))
            HandoverEvent.Resumed -> obj("type" to "H", "event" to "RESUMED", "at" to at(e.at))
            is HandoverEvent.Ended -> obj("type" to "H", "event" to "ENDED", "reason" to h.reason.name, "capsuleId" to h.capsuleId, "at" to at(e.at))
        }
        is ChainEntry.Library -> when (val k = e.event) {
            is LibraryEvent.PlanChanged -> obj("type" to "K", "event" to "PLAN", "tier" to k.tier.name, "previous" to k.previous?.name, "at" to at(e.at))
            is LibraryEvent.Left -> obj("type" to "K", "event" to "LEFT", "capsuleId" to k.capsuleId, "at" to at(e.at))
            is LibraryEvent.Returned -> obj("type" to "K", "event" to "RETURNED", "capsuleId" to k.capsuleId, "at" to at(e.at))
            is LibraryEvent.Extended -> obj("type" to "K", "event" to "EXTENDED", "capsuleId" to k.capsuleId, "untilWall" to k.untilWall, "at" to at(e.at))
        }
    }

    private fun signalJson(s: VehicleSignal): Any = when (s) {
        is VehicleSignal.BrakePedal -> s.pressed
        is VehicleSignal.TurnSignal -> s.state.name
        is VehicleSignal.SteeringAngle -> s.degrees
        is VehicleSignal.Gear -> s.position.name
        is VehicleSignal.Speed -> s.metersPerSecond
        is VehicleSignal.Ignition -> s.state.name
        is VehicleSignal.AirbagDeployed -> obj("seat" to s.seat, "deployed" to s.deployed, "test" to s.test)
        is VehicleSignal.Telltale -> obj("name" to s.name, "state" to s.state.name)
        is VehicleSignal.CruiseControl -> s.state.name
        is VehicleSignal.CruiseTargetSpeed -> s.metersPerSecond
        is VehicleSignal.Odometer -> s.distance
        is VehicleSignal.Door -> obj("area" to s.area, "open" to s.open)
        is VehicleSignal.SeatBelt -> obj("seat" to s.seat, "buckled" to s.buckled)
        is VehicleSignal.SeatOccupancy -> obj("seat" to s.seat, "occupied" to s.occupied)
        is VehicleSignal.Light -> obj("lamp" to s.lamp.name, "on" to s.on)
        is VehicleSignal.Wiper -> obj("area" to s.area, "mode" to s.mode)
        is VehicleSignal.Window -> obj("area" to s.area, "position" to s.position)
        is VehicleSignal.ParkingBrake -> s.engaged
        is VehicleSignal.RoadContext -> obj("type" to s.type.name, "value" to s.value)
        is VehicleSignal.RoadGrade -> s.percent
        is VehicleSignal.EvEfficiency -> s.value
        is VehicleSignal.AcceleratorPedal -> s.percent
        is VehicleSignal.BrakePressure -> s.percent
    }

    private fun timelineJson(t: EvidenceTimeline): Json {
        fun points(p: List<com.americankimchilabs.whitebox.domain.LanePoint>) = p.map { obj("atMs" to it.atMs, "value" to it.value) }
        fun <T> steps(s: List<com.americankimchilabs.whitebox.domain.LaneStep<T>>) = s.map { obj("atMs" to it.atMs, "value" to (it.value as Any).let { v -> if (v is Enum<*>) v.name else v }) }
        fun map(m: Map<*, *>) = obj(*m.map { (k, v) -> (if (k is Enum<*>) k.name else k.toString()) to v }.toTypedArray())
        val c = t.context
        return obj(
            "speedKmh" to points(t.speedKmh), "steering" to points(t.steering), "brake" to steps(t.brake), "turn" to steps(t.turn), "gear" to steps(t.gear),
            "lines" to t.lines.map { l ->
                when (l) {
                    is ControlLine.Brake -> obj("type" to "BRAKE", "value" to l.pressed, "beforeMs" to l.beforeMs)
                    is ControlLine.Turn -> obj("type" to "TURN", "value" to l.state.name, "beforeMs" to l.beforeMs)
                    is ControlLine.Gear -> obj("type" to "GEAR", "value" to l.position.name, "beforeMs" to l.beforeMs)
                    is ControlLine.SteeringPeak -> obj("type" to "STEERING_PEAK", "value" to l.degrees, "beforeMs" to l.beforeMs)
                    is ControlLine.HighBeamFlash -> obj("type" to "HIGH_BEAM_FLASH", "beforeMs" to l.beforeMs)
                }
            },
            "context" to obj(
                "seatBelts" to map(c.seatBelts), "occupancy" to map(c.occupancy), "doors" to map(c.doors), "windows" to map(c.windows),
                "lamps" to map(c.lamps), "wipers" to map(c.wipers), "cruise" to c.cruise?.name, "cruiseTargetMps" to c.cruiseTargetMps,
                "parkingBrake" to c.parkingBrake, "speedLimitKmh" to c.speedLimitKmh, "roadAlerts" to c.roadAlerts.map { listOf(it.first.name, it.second) },
            ),
            "startMs" to t.startMs, "endMs" to t.endMs,
        )
    }
}
