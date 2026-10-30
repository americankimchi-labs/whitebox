package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.VehicleSignal
import com.americankimchilabs.whitebox.vehicle.fake.DemoDrive
import com.americankimchilabs.whitebox.vehicle.fake.ScriptStep
import com.americankimchilabs.whitebox.vehicle.fake.ScriptedLocationSource
import com.americankimchilabs.whitebox.vehicle.fake.ScriptedSignalSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** End to end on the fake source: scripted drive → chain → engine → signed capsule → verification. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderEndToEndTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.clock() = Clock { Timestamp(currentTime * 1_000_000, 1_793_000_000_000 + currentTime) }

    private suspend fun TestScope.replay(recorder: Recorder, signals: List<ScriptStep>) {
        val clock = clock()
        merge(
            ScriptedSignalSource(signals, clock).samples().map { ChainEntry.Signal(it) },
            ScriptedLocationSource(DemoDrive.locations(), clock).fixes().map { ChainEntry.Location(it) },
        ).collect { recorder.record(it) }
    }

    @Test
    fun demoDriveSealsOneVerifiedDeltaVCapsule() = runTest {
        val dir = tmp.newFolder()
        val mirror = RecordingMirror()
        val store = FileEventStore(dir)
        val recorder = Recorder(store, SealStore(dir), JdkSigner(), clock(), mirror)

        replay(recorder, DemoDrive.signals())

        val seal = recorder.capsules.value.single()
        assertEquals(CapsuleTrigger.DELTA_V, seal.capsule.trigger)
        assertTrue(seal.final)
        assertEquals(0L, seal.capsule.firstSeq) // the 120 s pre-window reaches back to the start of the drive
        assertEquals(CapsuleCheck.Verified, verifyCapsule(seal, store))
        assertEquals(EscalationLevel.NORMAL, recorder.level.value)
        // Local seal first, then the mirror is told: provisional at impact, final at stillness.
        assertEquals(listOf(seal.capsule.id, seal.capsule.id), mirror.manifests.map { it.id })

        val inCapsule = store.read(seal.capsule.firstSeq, seal.capsule.lastSeq).map { it.entry }
        val escalations = inCapsule.filterIsInstance<ChainEntry.Escalation>().map { it.event }
        assertEquals(
            listOf(EscalationLevel.ALERT, EscalationLevel.TRIGGERED, EscalationLevel.NORMAL),
            escalations.filterIsInstance<EscalationEvent.LevelChanged>().map { it.level },
        )
        val trigger = escalations.filterIsInstance<EscalationEvent.Triggered>().single()
        assertEquals(-25f, trigger.measuredKmh, 0.1f)
        val triggerAtMs = store.read(trigger.causeSeq, trigger.causeSeq).single().entry.at.elapsedNanos / 1_000_000
        assertEquals(DemoDrive.IMPACT_AT_MS, triggerAtMs)
        assertTrue(inCapsule.any { it is ChainEntry.Location })
        assertTrue(inCapsule.any { it is ChainEntry.Signal && it.sample.signal is VehicleSignal.TurnSignal })
    }

    @Test
    fun sealsSurviveRestartAndDetectLaterTampering() = runTest {
        val dir = tmp.newFolder()
        val store = FileEventStore(dir)
        replay(Recorder(store, SealStore(dir), JdkSigner(), clock(), RecordingMirror()), DemoDrive.signals())
        store.close()

        // Edit one speed value inside the capsule, as someone with file access would.
        val chain = File(dir, "chain.log")
        chain.writeText(chain.readText().replaceFirst("S|SPEED|6.9444447|", "S|SPEED|6.9444448|"))

        val seal = SealStore(dir).all().single()
        val check = verifyCapsule(seal, FileEventStore(dir))
        assertTrue(check is CapsuleCheck.Failed)
    }

    @Test
    fun hardStopWithoutImpactOffersAnIncidentWhichSealsOnConsent() = runTest {
        val dir = tmp.newFolder()
        val store = FileEventStore(dir)
        val recorder = Recorder(store, SealStore(dir), JdkSigner(), clock(), RecordingMirror())
        // The demo drive up to the hard brake, then a plain 1 g stop instead of the impact.
        val cut = DemoDrive.signals().runningFold(0L to (null as ScriptStep?)) { (t, _), s -> (t + s.afterMillis) to s }
            .drop(1).takeWhile { it.first < DemoDrive.IMPACT_AT_MS - 900 }.map { it.second!! }
        val stop = buildList {
            add(ScriptStep(0, VehicleSignal.BrakePedal(true)))
            var v = 60f
            while (v > 0f) { add(ScriptStep(100, VehicleSignal.Speed(v / 3.6f))); v -= 3.5f }
            repeat(110) { add(ScriptStep(100, VehicleSignal.Speed(0f))) }
        }
        replay(recorder, cut + stop)

        assertTrue(recorder.capsules.value.isEmpty())
        val incident = assertNotNull(recorder.pendingIncident.value).let { recorder.pendingIncident.value!! }
        assertEquals(CapsuleTrigger.ALERT_RESOLVED, incident.trigger)

        // The process dies before the driver answers: a new recorder on the same files restores the offer.
        store.close()
        val store2 = FileEventStore(dir)
        val reborn = Recorder(store2, SealStore(dir), JdkSigner(), clock(), RecordingMirror())
        assertEquals(incident, reborn.pendingIncident.value)

        reborn.answerIncident(keep = true)
        val seal = reborn.capsules.value.single()
        assertEquals(CapsuleCheck.Verified, verifyCapsule(seal, store2))
        // Answered: a third start offers nothing.
        assertNull(Recorder(store2, SealStore(dir), JdkSigner(), clock(), RecordingMirror()).pendingIncident.value)
    }
}
