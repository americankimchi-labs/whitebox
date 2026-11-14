package com.americankimchilabs.whitebox.vehicle.pleos

import android.car.Car
import android.car.VehicleGear
import android.car.VehicleIgnitionState
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.core.VehicleSignalSource
import com.americankimchilabs.whitebox.domain.GearPosition
import com.americankimchilabs.whitebox.domain.IgnitionState
import com.americankimchilabs.whitebox.domain.SignalKind
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.VehicleSignal
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "WhiteBox/AAOS"

/**
 * Speed, gear, ignition and parking brake from AAOS CarPropertyManager:
 * - speed: the Pleos SDK has no speed domain. Needs android.car.permission.CAR_SPEED, a runtime
 *   (dangerous) grant; without it speed is reported unsupported.
 * - gear: the Pleos Gear domain is @Deprecated in favor of GEAR_SELECTION. Needs CAR_POWERTRAIN (normal).
 * - ignition: CAR_POWERTRAIN (normal).
 * - parking brake (PARKING_BRAKE_ON): CAR_POWERTRAIN (normal); the Pleos SDK exposes it only as a telltale, which
 *   needs a private permission.
 *
 * The car service can restart under us (seen right after a cold boot). Connecting without a lifecycle listener makes
 * the car library kill this process when that happens, so we connect with one and re-subscribe each time the
 * service comes back.
 */
// registerCallback/unregisterCallback/status are deprecated in the API 35 car library in favor of
// subscribePropertyEvents/propertyStatus, which don't exist on the API 34 Pleos image.
@Suppress("DEPRECATION")
class CarPropertySignalSource(
    context: Context,
    private val clock: Clock,
) : VehicleSignalSource, Closeable {

    private val appContext = context.applicationContext

    /** The current property manager; null while the car service is down. */
    private val manager = MutableStateFlow<CarPropertyManager?>(null)
    private val car: Car by lazy {
        Car.createCar(appContext, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
            if (!ready) Log.w(TAG, "car service down; re-subscribing when it returns")
            manager.value = if (ready) c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager else null
        }
    }

    private suspend fun properties(): CarPropertyManager {
        car // connect
        return manager.filterNotNull().first()
    }

    override suspend fun supportedKinds(): Set<SignalKind> {
        val p = properties()
        fun has(id: Int) = p.getCarPropertyConfig(id) != null
        return buildSet {
            if (speedPermitted() && has(VehiclePropertyIds.PERF_VEHICLE_SPEED)) add(SignalKind.SPEED)
            if (has(VehiclePropertyIds.GEAR_SELECTION)) add(SignalKind.GEAR)
            if (has(VehiclePropertyIds.IGNITION_STATE)) add(SignalKind.IGNITION)
            if (has(VehiclePropertyIds.PARKING_BRAKE_ON)) add(SignalKind.PARKING_BRAKE)
        }
    }

    override fun samples(): Flow<SignalSample> = callbackFlow {
        val gearReported = AtomicBoolean(false)
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                // Stamp receive time first; value.timestamp is the VHAL event time (elapsedRealtimeNanos).
                val receivedAt = clock.now()
                if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
                if (value.propertyId == VehiclePropertyIds.GEAR_SELECTION) gearReported.set(true)
                val signal = when (value.propertyId) {
                    VehiclePropertyIds.PERF_VEHICLE_SPEED -> VehicleSignal.Speed(value.value as Float)
                    VehiclePropertyIds.GEAR_SELECTION -> VehicleSignal.Gear(gearOf(value.value as Int))
                    VehiclePropertyIds.IGNITION_STATE -> VehicleSignal.Ignition(ignitionOf(value.value as Int))
                    VehiclePropertyIds.PARKING_BRAKE_ON -> VehicleSignal.ParkingBrake(value.value as Boolean)
                    else -> return
                }
                trySendBlocking(SignalSample(signal, receivedAt, sourceElapsedNanos = value.timestamp))
            }

            override fun onErrorEvent(propertyId: Int, areaId: Int) {
                Log.w(TAG, "property $propertyId area $areaId error")
            }
        }

        car // connect
        // (Re)subscribe on every connection; a restarted car service has forgotten the old callbacks.
        launch {
            manager.collect { p ->
                if (p == null) return@collect
                if (speedPermitted()) {
                    p.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FASTEST)
                } else {
                    Log.w(TAG, "CAR_SPEED not granted; speed disabled")
                }
                p.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                // An on-change subscription is not bound to report the value it starts with (on a freshly booted
                // Pleos Connect emulator it says nothing until the gear first moves), and a recorder that never
                // heard the gear would not know whether the car is parked. Read it once, unless the subscription
                // has already said it.
                if (!gearReported.get()) {
                    runCatching { p.getProperty<Any>(VehiclePropertyIds.GEAR_SELECTION, 0) }
                        .onFailure { Log.w(TAG, "gear at start not readable: ${it.message}") }
                        .getOrNull()?.let { if (!gearReported.get()) callback.onChangeEvent(it) }
                }
                for (id in listOf(VehiclePropertyIds.IGNITION_STATE, VehiclePropertyIds.PARKING_BRAKE_ON)) {
                    if (p.getCarPropertyConfig(id) != null) p.registerCallback(callback, id, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                }
            }
        }

        awaitClose { manager.value?.unregisterCallback(callback) }
    }

    override fun close() {
        car.disconnect()
    }

    private fun speedPermitted() =
        appContext.checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED
}

private fun gearOf(raw: Int) = when (raw) {
    VehicleGear.GEAR_PARK -> GearPosition.PARK
    VehicleGear.GEAR_REVERSE -> GearPosition.REVERSE
    VehicleGear.GEAR_NEUTRAL -> GearPosition.NEUTRAL
    VehicleGear.GEAR_DRIVE -> GearPosition.DRIVE
    VehicleGear.GEAR_UNKNOWN -> GearPosition.UNKNOWN
    else -> GearPosition.MANUAL
}

private fun ignitionOf(raw: Int) = when (raw) {
    VehicleIgnitionState.LOCK -> IgnitionState.LOCK
    VehicleIgnitionState.OFF -> IgnitionState.OFF
    VehicleIgnitionState.ACC -> IgnitionState.ACC
    VehicleIgnitionState.ON -> IgnitionState.ON
    VehicleIgnitionState.START -> IgnitionState.START
    else -> IgnitionState.UNKNOWN
}
