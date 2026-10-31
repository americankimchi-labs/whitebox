package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.domain.AudioEvent
import com.americankimchilabs.whitebox.domain.BookmarkSource
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.HandoverEndReason
import com.americankimchilabs.whitebox.domain.HandoverEvent
import com.americankimchilabs.whitebox.domain.HandoverEventKind
import com.americankimchilabs.whitebox.domain.HandoverSource
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.CruiseState
import com.americankimchilabs.whitebox.domain.Lamp
import com.americankimchilabs.whitebox.domain.RoadContextType
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.Gap
import com.americankimchilabs.whitebox.domain.GapCause
import com.americankimchilabs.whitebox.domain.GearPosition
import com.americankimchilabs.whitebox.domain.IgnitionState
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import com.americankimchilabs.whitebox.domain.LocationFix
import com.americankimchilabs.whitebox.domain.RecorderEvent
import com.americankimchilabs.whitebox.domain.RecorderPermission
import com.americankimchilabs.whitebox.domain.SignalKind
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.StartReason
import com.americankimchilabs.whitebox.domain.TelltaleState
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.TurnSignalState
import com.americankimchilabs.whitebox.domain.VehicleSignal
import com.americankimchilabs.whitebox.domain.VoiceLine

/**
 * Canonical text form of a [ChainEntry]: what the hash covers is exactly what is stored, byte for byte.
 * Fields are '|'-separated, never contain tab or newline (the store's separators), and decode back to an equal entry.
 * Floats use Kotlin's shortest round-trip form.
 *
 * With a [RecordCipher], what a record says is encrypted and what it is stays readable: a signal's value
 * (`S|KIND|v1.…|time|source time`) and a location fix as a whole (`L|v1.…|time`), each bound to its record's type and
 * time. Recorder, escalation, audio and handover records are not personal and stay plain, so the server can run the dead-man
 * switch on them. Records written without a cipher (older chains, tests) decode as before.
 */
object EntryCodec {

    fun encode(e: ChainEntry, cipher: RecordCipher? = null): String = when (e) {
        is ChainEntry.Signal -> {
            val kind = e.sample.signal.kind.name
            val value = value(e.sample.signal)
            listOf("S", kind, cipher?.seal(value, "S|$kind|${ts(e.at)}") ?: value, ts(e.at), e.sample.sourceElapsedNanos ?: "-")
        }
        is ChainEntry.Location -> with(e.fix) {
            if (cipher == null) listOf("L", latitude, longitude, accuracyMeters ?: "-", gpsTimeMillis, ts(e.at))
            else listOf("L", cipher.seal("$latitude;$longitude;${accuracyMeters ?: "-"};$gpsTimeMillis", "L|${ts(e.at)}"), ts(e.at))
        }
        is ChainEntry.Recorder -> listOf("R", ts(e.at)) + recorder(e.event)
        is ChainEntry.Escalation -> listOf("E", ts(e.at)) + when (val v = e.event) {
            is EscalationEvent.LevelChanged -> listOf("LEVEL", v.level, v.causeSeq)
            is EscalationEvent.Triggered -> listOf("TRIGGER", v.trigger, v.causeSeq, v.measured)
            is EscalationEvent.IncidentOffered -> with(v.capsule) { listOf("OFFER", id, trigger, firstSeq, lastSeq) }
            // An unanswered flag only when false, so entries written before it existed keep their exact form.
            is EscalationEvent.IncidentAnswered -> listOf("ANSWER", v.capsuleId, v.keep) + if (v.answered) emptyList() else listOf(false)
        }
        is ChainEntry.Audio -> listOf("A", ts(e.at)) + when (val v = e.event) {
            is AudioEvent.Bookmark -> listOf("BOOKMARK", v.source)
            is AudioEvent.Spoke -> listOf("SPOKE", v.line, v.complete)
            is AudioEvent.Beacon -> listOf("BEACON", v.chirpId, ts(v.playedAt))
        }
        is ChainEntry.Handover -> listOf("H", ts(e.at)) + when (val v = e.event) {
            is HandoverEvent.Armed -> listOf("ARMED", v.by, v.requestedAtWall)
            is HandoverEvent.Cancelled -> listOf("CANCELLED", v.by, v.reason)
            is HandoverEvent.Started -> listOf("STARTED", v.fromSeq, v.by, v.requestedAtWall)
            is HandoverEvent.Observed -> listOf("EVENT", v.kind, v.value, v.limit ?: "-")
            is HandoverEvent.Ending -> listOf("ENDING", v.reason)
            HandoverEvent.Resumed -> listOf("RESUMED")
            is HandoverEvent.Ended -> listOf("ENDED", v.reason, v.capsuleId)
        }
    }.joinToString("|") { clean(it.toString()) }

    /** Throws [LockedRecordException] for an encrypted record when [cipher] is null. */
    fun decode(s: String, cipher: RecordCipher? = null): ChainEntry {
        val f = s.split("|")
        return when (f[0]) {
            "S" -> {
                val value = if (RecordCipher.isSealed(f[2])) open(cipher, f[2], "S|${f[1]}|${f[3]}") else f[2]
                ChainEntry.Signal(SignalSample(signal(SignalKind.valueOf(f[1]), value), ts(f[3]), f[4].takeIf { it != "-" }?.toLong()))
            }
            "L" -> if (RecordCipher.isSealed(f[1])) {
                val v = open(cipher, f[1], "L|${f[2]}").split(";")
                ChainEntry.Location(LocationFix(v[0].toDouble(), v[1].toDouble(), v[2].takeIf { it != "-" }?.toFloat(), v[3].toLong(), ts(f[2])))
            } else {
                ChainEntry.Location(LocationFix(f[1].toDouble(), f[2].toDouble(), f[3].takeIf { it != "-" }?.toFloat(), f[4].toLong(), ts(f[5])))
            }
            "R" -> ChainEntry.Recorder(recorder(f.drop(2)), ts(f[1]))
            "E" -> ChainEntry.Escalation(
                when (f[2]) {
                    "LEVEL" -> EscalationEvent.LevelChanged(EscalationLevel.valueOf(f[3]), f[4].toLong())
                    "TRIGGER" -> EscalationEvent.Triggered(CapsuleTrigger.valueOf(f[3]), f[4].toLong(), f[5].toFloat())
                    "OFFER" -> EscalationEvent.IncidentOffered(IncidentCapsule(f[3], CapsuleTrigger.valueOf(f[4]), f[5].toLong(), f[6].toLong()))
                    "ANSWER" -> EscalationEvent.IncidentAnswered(f[3], f[4].toBooleanStrict(), f.getOrNull(5)?.toBooleanStrict() ?: true)
                    else -> error("unknown escalation ${f[2]}")
                },
                ts(f[1]),
            )
            "A" -> ChainEntry.Audio(
                when (f[2]) {
                    "BOOKMARK" -> AudioEvent.Bookmark(BookmarkSource.valueOf(f[3]))
                    "SPOKE" -> AudioEvent.Spoke(VoiceLine.valueOf(f[3]), f[4].toBooleanStrict())
                    "BEACON" -> AudioEvent.Beacon(f[3].toInt(), ts(f[4]))
                    else -> error("unknown audio event ${f[2]}")
                },
                ts(f[1]),
            )
            "H" -> ChainEntry.Handover(
                when (f[2]) {
                    "ARMED" -> HandoverEvent.Armed(HandoverSource.valueOf(f[3]), f[4].toLong())
                    "CANCELLED" -> HandoverEvent.Cancelled(HandoverSource.valueOf(f[3]), HandoverEndReason.valueOf(f[4]))
                    "STARTED" -> HandoverEvent.Started(f[3].toLong(), HandoverSource.valueOf(f[4]), f[5].toLong())
                    "EVENT" -> HandoverEvent.Observed(HandoverEventKind.valueOf(f[3]), f[4].toFloat(), f[5].takeIf { it != "-" }?.toFloat())
                    "ENDING" -> HandoverEvent.Ending(HandoverEndReason.valueOf(f[3]))
                    "RESUMED" -> HandoverEvent.Resumed
                    "ENDED" -> HandoverEvent.Ended(HandoverEndReason.valueOf(f[3]), f[4])
                    else -> error("unknown handover event ${f[2]}")
                },
                ts(f[1]),
            )
            else -> error("unknown entry type ${f[0]}")
        }
    }

    /** When a record was made, readable with or without the key. */
    fun timestamp(s: String): Timestamp {
        val f = s.split("|")
        return ts(
            when (f[0]) {
                "S" -> f[3]
                "L" -> if (RecordCipher.isSealed(f[1])) f[2] else f[5]
                else -> f[1]
            },
        )
    }

    /** True for a record whose content is encrypted. */
    fun isSealed(s: String): Boolean = s.split("|").let { f -> f.size > 2 && (RecordCipher.isSealed(f[1]) || RecordCipher.isSealed(f[2])) }

    private fun open(cipher: RecordCipher?, sealed: String, aad: String): String =
        (cipher ?: throw LockedRecordException()).open(sealed, aad)

    private fun ts(t: Timestamp) = "${t.elapsedNanos};${t.wallMillis}"

    private fun ts(s: String) = s.split(";").let { Timestamp(it[0].toLong(), it[1].toLong()) }

    private fun clean(s: String) = s.replace('|', '_').replace('\t', ' ').replace('\n', ' ')

    private fun value(s: VehicleSignal): String = when (s) {
        is VehicleSignal.BrakePedal -> s.pressed.toString()
        is VehicleSignal.TurnSignal -> s.state.name
        is VehicleSignal.SteeringAngle -> s.degrees.toString()
        is VehicleSignal.Gear -> s.position.name
        is VehicleSignal.Speed -> s.metersPerSecond.toString()
        is VehicleSignal.Ignition -> s.state.name
        is VehicleSignal.AirbagDeployed -> "${clean(s.seat).replace(';', '_')};${s.deployed}"
        is VehicleSignal.Telltale -> "${label(s.name)};${s.state.name}"
        is VehicleSignal.CruiseControl -> s.state.name
        is VehicleSignal.CruiseTargetSpeed -> s.metersPerSecond.toString()
        is VehicleSignal.Odometer -> s.distance.toString()
        is VehicleSignal.Door -> "${label(s.area)};${s.open}"
        is VehicleSignal.SeatBelt -> "${label(s.seat)};${s.buckled}"
        is VehicleSignal.SeatOccupancy -> "${label(s.seat)};${s.occupied}"
        is VehicleSignal.Light -> "${s.lamp.name};${s.on}"
        is VehicleSignal.Wiper -> "${label(s.area)};${label(s.mode)}"
        is VehicleSignal.Window -> "${label(s.area)};${s.position}"
        is VehicleSignal.ParkingBrake -> s.engaged.toString()
        is VehicleSignal.RoadContext -> "${s.type.name};${label(s.value)}"
        is VehicleSignal.RoadGrade -> s.percent.toString()
        is VehicleSignal.EvEfficiency -> s.value.toString()
        is VehicleSignal.AcceleratorPedal -> s.percent.toString()
        is VehicleSignal.BrakePressure -> s.percent.toString()
    }

    /** A free-form label inside a ';'-separated value. */
    private fun label(s: String) = clean(s).replace(';', '_')

    private fun signal(kind: SignalKind, v: String): VehicleSignal = when (kind) {
        SignalKind.BRAKE_PEDAL -> VehicleSignal.BrakePedal(v.toBooleanStrict())
        SignalKind.TURN_SIGNAL -> VehicleSignal.TurnSignal(TurnSignalState.valueOf(v))
        SignalKind.STEERING_ANGLE -> VehicleSignal.SteeringAngle(v.toFloat())
        SignalKind.GEAR -> VehicleSignal.Gear(GearPosition.valueOf(v))
        SignalKind.SPEED -> VehicleSignal.Speed(v.toFloat())
        SignalKind.IGNITION -> VehicleSignal.Ignition(IgnitionState.valueOf(v))
        SignalKind.AIRBAG -> v.split(";").let { VehicleSignal.AirbagDeployed(it[0], it[1].toBooleanStrict()) }
        SignalKind.TELLTALE -> v.split(";").let { VehicleSignal.Telltale(it[0], TelltaleState.valueOf(it[1])) }
        SignalKind.CRUISE_CONTROL -> VehicleSignal.CruiseControl(CruiseState.valueOf(v))
        SignalKind.CRUISE_TARGET_SPEED -> VehicleSignal.CruiseTargetSpeed(v.toFloat())
        SignalKind.ODOMETER -> VehicleSignal.Odometer(v.toFloat())
        SignalKind.DOOR -> v.split(";").let { VehicleSignal.Door(it[0], it[1].toBooleanStrict()) }
        SignalKind.SEAT_BELT -> v.split(";").let { VehicleSignal.SeatBelt(it[0], it[1].toBooleanStrict()) }
        SignalKind.SEAT_OCCUPANCY -> v.split(";").let { VehicleSignal.SeatOccupancy(it[0], it[1].toBooleanStrict()) }
        SignalKind.LIGHT -> v.split(";").let { VehicleSignal.Light(Lamp.valueOf(it[0]), it[1].toBooleanStrict()) }
        SignalKind.WIPER -> v.split(";").let { VehicleSignal.Wiper(it[0], it[1]) }
        SignalKind.WINDOW -> v.split(";").let { VehicleSignal.Window(it[0], it[1].toInt()) }
        SignalKind.PARKING_BRAKE -> VehicleSignal.ParkingBrake(v.toBooleanStrict())
        SignalKind.ROAD_CONTEXT -> v.split(";").let { VehicleSignal.RoadContext(RoadContextType.valueOf(it[0]), it[1]) }
        SignalKind.ROAD_GRADE -> VehicleSignal.RoadGrade(v.toFloat())
        SignalKind.EV_EFFICIENCY -> VehicleSignal.EvEfficiency(v.toFloat())
        SignalKind.ACCELERATOR_PEDAL -> VehicleSignal.AcceleratorPedal(v.toFloat())
        SignalKind.BRAKE_PRESSURE -> VehicleSignal.BrakePressure(v.toFloat())
    }

    private fun recorder(e: RecorderEvent): List<Any> = when (e) {
        is RecorderEvent.RecordingStarted -> listOf("STARTED", e.reason, e.restartCount) + (
            e.gap?.let { listOf(it.cause, it.fromWallMillis, it.untilWallMillis, it.durationMillis, it.acrossBoots) }
                ?: listOf("-")
            )
        RecorderEvent.RecordingStopped -> listOf("STOPPED")
        is RecorderEvent.PermissionDegraded -> listOf("DEGRADED", e.permission)
        is RecorderEvent.PermissionRestored -> listOf("RESTORED", e.permission)
    }

    private fun recorder(f: List<String>): RecorderEvent = when (f[0]) {
        "STARTED" -> RecorderEvent.RecordingStarted(
            StartReason.valueOf(f[1]),
            gap = if (f[3] == "-") null else Gap(GapCause.valueOf(f[3]), f[4].toLong(), f[5].toLong(), f[6].toLong(), f[7].toBooleanStrict()),
            restartCount = f[2].toInt(),
        )
        "STOPPED" -> RecorderEvent.RecordingStopped
        "DEGRADED" -> RecorderEvent.PermissionDegraded(RecorderPermission.valueOf(f[1]))
        "RESTORED" -> RecorderEvent.PermissionRestored(RecorderPermission.valueOf(f[1]))
        else -> error("unknown recorder event ${f[0]}")
    }
}

/** The record is encrypted and no key was given (the server, or a reader without access). */
class LockedRecordException : IllegalStateException("record is encrypted")
