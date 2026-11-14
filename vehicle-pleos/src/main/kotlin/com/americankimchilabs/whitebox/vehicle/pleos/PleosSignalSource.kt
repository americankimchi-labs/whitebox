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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import kotlin.coroutines.resume

private const val TAG = "WhiteBox/Pleos"

/** How long a capability check may take before the signal is subscribed without its answer. */
private const val CAPABILITY_ANSWER_MS = 3_000L

/**
 * Brake, turn signal, steering angle and airbag from the Pleos Vehicle SDK 2.3.2, plus the driving context
 * ([PleosContextSignals]). Every signal is subscribed only after its capability check. Gear is not read here: the
 * SDK's Gear domain is @Deprecated ("use the Google GEAR_SELECTION property"), see [CarPropertySignalSource].
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
    private val contextSignals by lazy { PleosContextSignals(vehicle) }

    override suspend fun supportedKinds(): Set<SignalKind> = buildSet {
        if (brakeSupported() != false) add(SignalKind.BRAKE_PEDAL)
        if (turnSignalSupported() != false) add(SignalKind.TURN_SIGNAL)
        if (steeringSupported() != false) add(SignalKind.STEERING_ANGLE)
        if (airbagSeats().isNotEmpty()) add(SignalKind.AIRBAG)
        addAll(contextSignals.supportedKinds())
    }

    private suspend fun brakeSupported() = probe("brake") { ok, fail -> vehicle.getBrake().checkBrakePedalStatusCapabilities({ ok() }, fail) }

    private suspend fun turnSignalSupported() =
        probe("turn signal") { ok, fail -> vehicle.getTurnSignal().checkTurnSignalStateCapabilities({ ok() }, fail) }

    private suspend fun steeringSupported() =
        probe("steering") { ok, fail -> vehicle.getSteeringWheel().checkSteeringWheelAngleCapabilities({ _, _ -> ok() }, fail) }

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

        // Each signal is subscribed only after its capability check: a car that says it lacks one is left alone.
        // A check that gives no answer does not cost the recorder a core signal: it is subscribed anyway, and a
        // failure then arrives at the listener.
        val brakeOn = brakeSupported() != false
        val turnOn = turnSignalSupported() != false
        val steeringOn = steeringSupported() != false
        Log.i(TAG, "capabilities: brake=$brakeOn turnSignal=$turnOn steering=$steeringOn airbagSeats=${airbagListeners.size}")

        if (brakeOn) {
            brake.registerBrakePedalStatus(brakeListener)
            // The subscription reports transitions only, so a recorder (re)started with the pedal already down or
            // released would keep the state from before it stopped. Read the pedal once at start.
            brake.getCurrentBrakePedalStatus({ p0 -> if (p0 != null) send(VehicleSignal.BrakePedal(p0)) }, { e -> logFailure("brake now", e) })
        }
        if (turnOn) {
            turnSignal.registerTurnSignalState(turnListener)
            // Same for the turn signal: without its state at start, a lane change could not be told apart from one
            // made without signalling.
            turnSignal.getCurrentTurnSignalState({ p0 -> if (p0 != null) send(VehicleSignal.TurnSignal(p0.toDomain())) }, { e -> logFailure("turn signal now", e) })
        }
        if (steeringOn) steering.registerSteeringWheelAngle(steeringListener)
        airbagListeners.forEach { (seat, listener) -> safety.registerSeatAirbagDeployed(seat, listener) }
        val contextOff = contextSignals.register(::send)

        awaitClose {
            contextOff.forEach { it() }
            if (brakeOn) brake.unregisterBrakePedalStatus(brakeListener)
            if (turnOn) turnSignal.unregisterTurnSignalState(turnListener)
            if (steeringOn) steering.unregisterSteeringWheelAngle(steeringListener)
            airbagListeners.forEach { (seat, listener) -> safety.unregisterSeatAirbagDeployed(seat, listener) }
        }
    }

    override fun close() {
        vehicle.release()
    }

    /** The seats whose airbag the car reports; none where it has no such signal (the seats are needed to subscribe). */
    private suspend fun airbagSeats(): List<SeatArea> = withTimeoutOrNull(CAPABILITY_ANSWER_MS) {
        suspendCancellableCoroutine { cont ->
            vehicle.getSafety().checkAirbagDeployedCapabilities(
                { _, seats -> if (cont.isActive) cont.resume(seats) },
                { e -> logFailure("airbag capability", e); if (cont.isActive) cont.resume(emptyList()) },
            )
        }
    } ?: emptyList<SeatArea>().also { Log.w(TAG, "airbag capability: no answer in $CAPABILITY_ANSWER_MS ms") }

    /**
     * Runs one SDK capability call: true if the car reports the signal, false if it reports it cannot, null if it
     * has not answered within [CAPABILITY_ANSWER_MS].
     */
    private suspend fun probe(what: String, call: (ok: () -> Unit, fail: (Exception) -> Unit) -> Unit): Boolean? {
        val answer = withTimeoutOrNull(CAPABILITY_ANSWER_MS) {
            suspendCancellableCoroutine { cont ->
                // The SDK answers once; a second answer after a timeout must not resume a finished coroutine.
                call({ if (cont.isActive) cont.resume(true) }, { e -> logFailure("$what capability", e); if (cont.isActive) cont.resume(false) })
            }
        }
        if (answer == null) Log.w(TAG, "$what capability: no answer in $CAPABILITY_ANSWER_MS ms")
        return answer
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
