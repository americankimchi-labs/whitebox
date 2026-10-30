package com.americankimchilabs.whitebox.vehicle.fake

import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.core.VehicleSignalSource
import com.americankimchilabs.whitebox.domain.SignalKind
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.VehicleSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One scripted signal, emitted [afterMillis] after the previous step. */
data class ScriptStep(
    val afterMillis: Long,
    val signal: VehicleSignal,
)

/**
 * Replays a fixed script of vehicle signals. Each collection replays from the start, so the same
 * script drives tests, the demo replay mode and screen recordings identically.
 */
class ScriptedSignalSource(
    private val script: List<ScriptStep>,
    private val clock: Clock,
) : VehicleSignalSource {

    override suspend fun supportedKinds(): Set<SignalKind> = script.mapTo(mutableSetOf()) { it.signal.kind }

    override fun samples(): Flow<SignalSample> = flow {
        for (step in script) {
            delay(step.afterMillis)
            emit(SignalSample(step.signal, receivedAt = clock.now()))
        }
    }
}
