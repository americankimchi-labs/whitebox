package com.americankimchilabs.whitebox.vehicle.fake

import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.domain.GearPosition
import com.americankimchilabs.whitebox.domain.SignalKind
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.TurnSignalState
import com.americankimchilabs.whitebox.domain.VehicleSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScriptedSignalSourceTest {

    private val script = listOf(
        ScriptStep(0, VehicleSignal.Gear(GearPosition.DRIVE)),
        ScriptStep(1_000, VehicleSignal.TurnSignal(TurnSignalState.LEFT)),
        ScriptStep(2_500, VehicleSignal.BrakePedal(pressed = true)),
    )

    private fun TestScope.virtualClock() = Clock {
        Timestamp(elapsedNanos = currentTime * 1_000_000, wallMillis = 1_700_000_000_000 + currentTime)
    }

    @Test
    fun replaysSignalsInOrderAtScriptedTimes() = runTest {
        val samples = ScriptedSignalSource(script, virtualClock()).samples().toList()

        assertEquals(script.map { it.signal }, samples.map { it.signal })
        assertEquals(listOf(0L, 1_000L, 3_500L), samples.map { it.receivedAt.elapsedNanos / 1_000_000 })
    }

    @Test
    fun eachCollectionReplaysFromTheStart() = runTest {
        val source = ScriptedSignalSource(script, virtualClock())

        val first = source.samples().take(2).toList().map { it.signal }
        val second = source.samples().take(2).toList().map { it.signal }

        assertEquals(first, second)
    }

    @Test
    fun supportedKindsAreTheKindsInTheScript() = runTest {
        val kinds = ScriptedSignalSource(script, virtualClock()).supportedKinds()

        assertEquals(setOf(SignalKind.GEAR, SignalKind.TURN_SIGNAL, SignalKind.BRAKE_PEDAL), kinds)
    }
}
