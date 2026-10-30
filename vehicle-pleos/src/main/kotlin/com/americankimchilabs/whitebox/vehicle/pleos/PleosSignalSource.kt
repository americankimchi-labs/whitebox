package com.americankimchilabs.whitebox.vehicle.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.util.Log
import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.core.VehicleSignalSource
import com.americankimchilabs.whitebox.domain.SignalKind
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.TurnSignalState
import com.americankimchilabs.whitebox.domain.VehicleSignal
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import kotlin.coroutines.resume

private const val TAG = "WhiteBox/Pleos"

/**
 * Brake, turn signal, steering angle and airbag from the Pleos Vehicle SDK 2.3.2. Gear is not read here:
 * the SDK's Gear domain is @Deprecated ("use the Google GEAR_SELECTION property"), see [CarPropertySignalSource].
 *
 * One [Vehicle] per source, initialized on first use; [close] releases it.
 */
class PleosSignalSource(
    context: Context,
    private val clock: Clock,
) : VehicleSignalSource, Closeable {

    private val vehicle: Vehicle by lazy {
        Vehicle(context.applicationContext).also { it.initialize() }
    }

    override suspend fun supportedKinds(): Set<SignalKind> = buildSet {
        if (probe { ok, fail -> vehicle.getBrake().checkBrakePedalStatusCapabilities({ ok() }, fail) }) {
            add(SignalKind.BRAKE_PEDAL)
        }
        if (probe { ok, fail -> vehicle.getTurnSignal().checkTurnSignalStateCapabilities({ ok() }, fail) }) {
            add(SignalKind.TURN_SIGNAL)
        }
        if (probe { ok, fail -> vehicle.getSteeringWheel().checkSteeringWheelAngleCapabilities({ _, _ -> ok() }, fail) }) {
            add(SignalKind.STEERING_ANGLE)
        }
        if (airbagSeats().isNotEmpty()) add(SignalKind.AIRBAG)
    }

    override fun samples(): Flow<SignalSample> = callbackFlow {
        // Stamp receive time first thing in every callback, before any dispatch.
        fun send(signal: VehicleSignal) {
            trySendBlocking(SignalSample(signal, receivedAt = clock.now()))
        }

        val brakeListener = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(p0: Boolean?) {
                if (p0 != null) send(VehicleSignal.BrakePedal(p0))
            }
            override fun onFailed(p0: Exception) = logFailure("brake", p0)
        }
        val turnListener = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(p0: TurnSignalValue?) {
                if (p0 != null) send(VehicleSignal.TurnSignal(p0.toDomain()))
            }
            override fun onFailed(p0: Exception) = logFailure("turn signal", p0)
        }
        val steeringListener = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(p0: Float?) {
                if (p0 != null) send(VehicleSignal.SteeringAngle(p0))
            }
            override fun onFailed(p0: Exception) = logFailure("steering", p0)
        }
        // Unregister needs the same listener object and area as register.
        val airbagListeners = airbagSeats().associateWith { seat ->
            object : SeatAirbagDeployedListener {
                override fun onSeatAirbagDeployUpdated(p0: Boolean?) {
                    if (p0 != null) send(VehicleSignal.AirbagDeployed(seat.label(), p0))
                }
                override fun onFailed(p0: Exception) = logFailure("airbag ${seat.label()}", p0)
            }
        }

        val brake = vehicle.getBrake()
        val turnSignal = vehicle.getTurnSignal()
        val steering = vehicle.getSteeringWheel()
        val safety = vehicle.getSafety()

        brake.registerBrakePedalStatus(brakeListener)
        turnSignal.registerTurnSignalState(turnListener)
        steering.registerSteeringWheelAngle(steeringListener)
        airbagListeners.forEach { (seat, listener) -> safety.registerSeatAirbagDeployed(seat, listener) }

        awaitClose {
            brake.unregisterBrakePedalStatus(brakeListener)
            turnSignal.unregisterTurnSignalState(turnListener)
            steering.unregisterSteeringWheelAngle(steeringListener)
            airbagListeners.forEach { (seat, listener) -> safety.unregisterSeatAirbagDeployed(seat, listener) }
        }
    }

    override fun close() {
        vehicle.release()
    }

    private suspend fun airbagSeats(): List<SeatArea> = suspendCancellableCoroutine { cont ->
        vehicle.getSafety().checkAirbagDeployedCapabilities(
            { _, seats -> cont.resume(seats) },
            { e -> logFailure("airbag capability", e); cont.resume(emptyList()) },
        )
    }

    /** Runs one SDK capability call; true if it reports success. */
    private suspend fun probe(call: (ok: () -> Unit, fail: (Exception) -> Unit) -> Unit): Boolean =
        suspendCancellableCoroutine { cont ->
            call({ cont.resume(true) }, { e -> logFailure("capability", e); cont.resume(false) })
        }

    private fun logFailure(what: String, e: Exception) {
        Log.w(TAG, "$what failed", e)
    }
}

private fun TurnSignalValue.toDomain() = when (this) {
    TurnSignalValue.OFF -> TurnSignalState.OFF
    TurnSignalValue.LEFT -> TurnSignalState.LEFT
    TurnSignalValue.RIGHT -> TurnSignalState.RIGHT
}

private fun SeatArea.label(): String = values.joinToString("+") { it.name }
