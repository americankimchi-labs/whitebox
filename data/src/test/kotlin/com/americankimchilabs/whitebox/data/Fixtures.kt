package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.domain.AudioEvent
import com.americankimchilabs.whitebox.domain.BookmarkSource
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.VoiceLine
import com.americankimchilabs.whitebox.domain.CruiseState
import com.americankimchilabs.whitebox.domain.Lamp
import com.americankimchilabs.whitebox.domain.RoadContextType
import com.americankimchilabs.whitebox.domain.DriveEvent
import com.americankimchilabs.whitebox.domain.Gap
import com.americankimchilabs.whitebox.domain.GapCause
import com.americankimchilabs.whitebox.domain.GearPosition
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import com.americankimchilabs.whitebox.domain.LocationFix
import com.americankimchilabs.whitebox.domain.RecorderEvent
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.StartReason
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.TurnSignalState
import com.americankimchilabs.whitebox.domain.VehicleSignal
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.random.Random

/** JDK provider stand-in for the Android Keystore signer: same algorithm (ECDSA P-256, SHA256withECDSA). */
class JdkSigner : Signer {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override val publicKey: ByteArray = pair.public.encoded
    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private)
        update(data)
        sign()
    }
}

fun ts(ms: Long) = Timestamp(ms * 1_000_000, 1_700_000_000_000 + ms)

/** One of every entry shape the chain can hold. */
fun everyEntryShape(): List<ChainEntry> = listOf(
    ChainEntry.Signal(SignalSample(VehicleSignal.BrakePedal(true), ts(1))),
    ChainEntry.Signal(SignalSample(VehicleSignal.TurnSignal(TurnSignalState.LEFT), ts(2))),
    ChainEntry.Signal(SignalSample(VehicleSignal.SteeringAngle(-12.5f), ts(3))),
    ChainEntry.Signal(SignalSample(VehicleSignal.Gear(GearPosition.DRIVE), ts(4), sourceElapsedNanos = 3_999_000)),
    ChainEntry.Signal(SignalSample(VehicleSignal.Speed(16.666668f), ts(5), sourceElapsedNanos = 4_900_000)),
    ChainEntry.Signal(SignalSample(VehicleSignal.AirbagDeployed("ROW_1_LEFT", true), ts(6))),
    ChainEntry.Location(LocationFix(37.4395, 127.127, 5f, 1_700_000_000_007, ts(7))),
    ChainEntry.Location(LocationFix(37.4395, 127.127, null, 1_700_000_000_008, ts(8))),
    ChainEntry.Recorder(RecorderEvent.RecordingStarted(StartReason.BOOT, null, 0), ts(9)),
    ChainEntry.Recorder(
        RecorderEvent.RecordingStarted(StartReason.STICKY_RESTART, Gap(GapCause.PROCESS_DIED, 1, 2, 3, false), 2), ts(10),
    ),
    ChainEntry.Recorder(RecorderEvent.RecordingStopped, ts(11)),
    ChainEntry.Recorder(RecorderEvent.PermissionDegraded(com.americankimchilabs.whitebox.domain.RecorderPermission.BACKGROUND_LOCATION), ts(12)),
    ChainEntry.Escalation(
        com.americankimchilabs.whitebox.domain.EscalationEvent.Triggered(
            com.americankimchilabs.whitebox.domain.CapsuleTrigger.REAR_SHOVE, 41, 7.2f,
        ),
        ts(13),
    ),
    ChainEntry.Escalation(
        com.americankimchilabs.whitebox.domain.EscalationEvent.IncidentOffered(
            IncidentCapsule("I7", com.americankimchilabs.whitebox.domain.CapsuleTrigger.ALERT_RESOLVED, 7, 99),
        ),
        ts(14),
    ),
    ChainEntry.Escalation(com.americankimchilabs.whitebox.domain.EscalationEvent.IncidentAnswered("I7", false), ts(15)),
    ChainEntry.Escalation(com.americankimchilabs.whitebox.domain.EscalationEvent.IncidentAnswered("I8", true, answered = false), ts(15)),
    ChainEntry.Audio(AudioEvent.Bookmark(BookmarkSource.ASSISTANT), ts(15)),
    ChainEntry.Audio(AudioEvent.Bookmark(BookmarkSource.OFFLINE_LISTENER), ts(15)),
    ChainEntry.Audio(AudioEvent.Spoke(VoiceLine.KEEP_QUESTION, complete = false), ts(15)),
    ChainEntry.Audio(AudioEvent.Beacon(3, ts(15)), ts(15)),
) + contextSignals().mapIndexed { i, s -> ChainEntry.Signal(SignalSample(s, ts(16L + i))) }

/** Every context and reserved signal kind, including labels with separator characters the codec must neutralise. */
fun contextSignals(): List<VehicleSignal> = listOf(
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
)

fun randomEntry(r: Random, ms: Long): ChainEntry = when (r.nextInt(5)) {
    0 -> ChainEntry.Signal(SignalSample(VehicleSignal.Speed(r.nextFloat() * 30), ts(ms), sourceElapsedNanos = ms * 1_000_000 - 50))
    1 -> ChainEntry.Signal(SignalSample(VehicleSignal.BrakePedal(r.nextBoolean()), ts(ms)))
    2 -> ChainEntry.Signal(SignalSample(VehicleSignal.SteeringAngle(r.nextInt(-90, 90).toFloat()), ts(ms)))
    3 -> ChainEntry.Signal(SignalSample(contextSignals().random(r), ts(ms)))
    else -> ChainEntry.Location(LocationFix(37 + r.nextDouble(), 127 + r.nextDouble(), 5f, 1_700_000_000_000 + ms, ts(ms)))
}
