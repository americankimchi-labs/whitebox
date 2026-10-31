package com.americankimchilabs.whitebox.vehicle.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.CruiseControlTargetSpeedListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryEfficiencyListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.util.Log
import com.americankimchilabs.whitebox.domain.CruiseState
import com.americankimchilabs.whitebox.domain.Lamp
import com.americankimchilabs.whitebox.domain.SignalKind
import com.americankimchilabs.whitebox.domain.VehicleSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "WhiteBox/PleosContext"

/**
 * The driving context around an event, from the Pleos Vehicle SDK: doors, seat belts and occupancy, head/tail lamps,
 * wipers, windows, cruise control, odometer and EV instantaneous efficiency. Each is subscribed only where its
 * capability check succeeds (per area), and repeated identical reports are dropped so the chain holds changes only.
 *
 * Permissions (all normal, read-only): CAR_DOORS, CAR_SEATS, CAR_LIGHTS, CAR_WINDOWS (wipers and windows),
 * CAR_DRIVE (cruise, odometer), CAR_ENERGY (EV efficiency).
 *
 * The Pleos Connect emulator supports everything here except EV instantaneous efficiency (its capability check fails,
 * so nothing is subscribed). Where a car reports it, the value is recorded raw: its unit is not documented.
 */
internal class PleosContextSignals(private val vehicle: Vehicle) {

    /** What this vehicle supports, read once from the capability calls. */
    private class Capabilities(
        val doors: Map<DoorArea, Int>, // area → closed position
        val belts: List<SeatArea>,
        val occupancy: List<SeatArea>,
        val lamps: Set<Lamp>,
        val wipers: List<WindowArea>,
        val windows: List<WindowArea>,
        val cruiseState: Boolean,
        val cruiseTarget: Boolean,
        val odometer: Boolean,
        val evEfficiency: Boolean,
    )

    private var capabilities: Capabilities? = null

    suspend fun supportedKinds(): Set<SignalKind> = with(capabilities()) {
        buildSet {
            if (doors.isNotEmpty()) add(SignalKind.DOOR)
            if (belts.isNotEmpty()) add(SignalKind.SEAT_BELT)
            if (occupancy.isNotEmpty()) add(SignalKind.SEAT_OCCUPANCY)
            if (lamps.isNotEmpty()) add(SignalKind.LIGHT)
            if (wipers.isNotEmpty()) add(SignalKind.WIPER)
            if (windows.isNotEmpty()) add(SignalKind.WINDOW)
            if (cruiseState) add(SignalKind.CRUISE_CONTROL)
            if (cruiseTarget) add(SignalKind.CRUISE_TARGET_SPEED)
            if (odometer) add(SignalKind.ODOMETER)
            if (evEfficiency) add(SignalKind.EV_EFFICIENCY)
        }
    }

    /** Subscribes everything supported; returns the matching unsubscribe actions. */
    suspend fun register(emit: (VehicleSignal) -> Unit): List<() -> Unit> {
        val c = capabilities()
        val last = HashMap<String, VehicleSignal>()
        fun send(key: String, s: VehicleSignal) {
            val changed = synchronized(last) { (last.put(key, s) != s) }
            if (changed) emit(s)
        }
        val off = mutableListOf<() -> Unit>()

        val door = vehicle.getDoor()
        for ((area, closed) in c.doors) {
            val l = object : DoorPositionListener {
                override fun onPositionUpdated(p0: Int?) {
                    if (p0 != null) send("door ${area.label()}", VehicleSignal.Door(area.label(), p0 > closed))
                }
                override fun onFailed(p0: Exception) = fail("door ${area.label()}", p0)
            }
            door.registerDoorPosition(area, l)
            off += { door.unregisterDoorPosition(area, l) }
        }

        val seat = vehicle.getSeat()
        for (area in c.belts) {
            val l = object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(p0: Boolean?) {
                    if (p0 != null) send("belt ${area.label()}", VehicleSignal.SeatBelt(area.label(), p0))
                }
                override fun onFailed(p0: Exception) = fail("belt ${area.label()}", p0)
            }
            seat.registerSeatBeltBuckledState(area, l)
            off += { seat.unregisterSeatBeltBuckledState(area, l) }
        }
        for (area in c.occupancy) {
            val l = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(p0: Boolean?) {
                    if (p0 != null) send("seat ${area.label()}", VehicleSignal.SeatOccupancy(area.label(), p0))
                }
                override fun onFailed(p0: Exception) = fail("occupancy ${area.label()}", p0)
            }
            seat.registerSeatOccupancy(area, l)
            off += { seat.unregisterSeatOccupancy(area, l) }
        }

        val light = vehicle.getLight()
        for (lamp in c.lamps) {
            val l = object : LightStateListener {
                override fun onLightStateUpdated(p0: LightState?) {
                    // Daytime running lights are not the headlamps being switched on.
                    if (p0 != null) send("lamp $lamp", VehicleSignal.Light(lamp, p0 == LightState.ON))
                }
                override fun onFailed(p0: Exception) = fail("lamp $lamp", p0)
            }
            when (lamp) {
                Lamp.LOW_BEAM -> light.registerLowBeamLightState(l).also { off += { light.unregisterLowBeamLightState(l) } }
                Lamp.HIGH_BEAM -> light.registerHighBeamLightState(l).also { off += { light.unregisterHighBeamLightState(l) } }
                Lamp.TAIL_LAMP -> light.registerTailLampLightState(l).also { off += { light.unregisterTailLampLightState(l) } }
                Lamp.HAZARD -> Unit // not readable by third-party apps
            }
        }

        val wiper = vehicle.getWiper()
        for (area in c.wipers) {
            val l = object : WiperStateListener {
                override fun onWiperStateUpdated(p0: WiperState?) {
                    if (p0 != null) send("wiper ${area.label()}", VehicleSignal.Wiper(area.label(), p0.name))
                }
                override fun onFailed(p0: Exception) = fail("wiper ${area.label()}", p0)
            }
            wiper.registerWiperState(area, l)
            off += { wiper.unregisterWiperState(area, l) }
        }

        val window = vehicle.getWindow()
        for (area in c.windows) {
            val l = object : WindowPositionListener {
                override fun onWindowPositionUpdated(p0: Int?) {
                    if (p0 != null) send("window ${area.label()}", VehicleSignal.Window(area.label(), p0))
                }
                override fun onFailed(p0: Exception) = fail("window ${area.label()}", p0)
            }
            window.registerWindowPosition(area, l)
            off += { window.unregisterWindowPosition(area, l) }
        }

        val driving = vehicle.getDrivingMode()
        if (c.cruiseState) {
            val l = object : CruiseControlStateListener {
                override fun onCruiseControlStateUpdated(p0: CruiseControl?) {
                    if (p0 != null) send("cruise", VehicleSignal.CruiseControl(p0.toDomain()))
                }
                override fun onFailed(p0: Exception) = fail("cruise state", p0)
            }
            driving.registerCruiseControlState(l)
            off += { driving.unregisterCruiseControlState(l) }
        }
        if (c.cruiseTarget) {
            val l = object : CruiseControlTargetSpeedListener {
                override fun onCruiseControlTargetSpeedUpdated(p0: Float?) {
                    if (p0 != null) send("cruise target", VehicleSignal.CruiseTargetSpeed(p0))
                }
                override fun onFailed(p0: Exception) = fail("cruise target", p0)
            }
            driving.registerCruiseControlTargetSpeed(l)
            off += { driving.unregisterCruiseControlTargetSpeed(l) }
        }

        if (c.odometer) {
            val odometer = vehicle.getOdometer()
            val l = object : DistanceDrivenListener {
                override fun onDistanceDrivenUpdated(p0: Float?) {
                    if (p0 != null) send("odometer", VehicleSignal.Odometer(p0))
                }
                override fun onFailed(p0: Exception) = fail("odometer", p0)
            }
            odometer.registerDistanceDriven(l)
            off += { odometer.unregisterDistanceDriven(l) }
        }

        if (c.evEfficiency) {
            val battery = vehicle.getEvBattery()
            val l = object : EvBatteryEfficiencyListener {
                override fun onEvBatteryEfficiencyUpdated(p0: Float?) {
                    if (p0 != null) send("ev efficiency", VehicleSignal.EvEfficiency(p0))
                }
                override fun onFailed(p0: Exception) = fail("ev efficiency", p0)
            }
            battery.registerInstantaneousEvEfficiency(l)
            off += { battery.unregisterInstantaneousEvEfficiency(l) }
        }
        return off
    }

    private suspend fun capabilities(): Capabilities = capabilities ?: run {
        val door = vehicle.getDoor()
        val seat = vehicle.getSeat()
        val light = vehicle.getLight()
        val driving = vehicle.getDrivingMode()
        Capabilities(
            doors = capability { ok, fail -> door.checkDoorPositionCapabilities({ m -> ok(m.mapValues { it.value.minValue }) }, fail) }
                ?: emptyMap(),
            belts = capability { ok, fail -> seat.checkSeatBeltBuckledCapabilities({ ok(it) }, fail) } ?: emptyList(),
            occupancy = capability { ok, fail -> seat.checkSeatOccupancyCapabilities({ ok(it) }, fail) } ?: emptyList(),
            lamps = buildSet {
                if (capability<Unit> { ok, fail -> light.checkLowBeamLightStateCapabilities({ _, _ -> ok(Unit) }, fail) } != null) add(Lamp.LOW_BEAM)
                if (capability<Unit> { ok, fail -> light.checkHighBeamLightStateCapabilities({ _, _ -> ok(Unit) }, fail) } != null) add(Lamp.HIGH_BEAM)
                if (capability<Unit> { ok, fail -> light.checkTailLampLightStateCapabilities({ _, _ -> ok(Unit) }, fail) } != null) add(Lamp.TAIL_LAMP)
            },
            wipers = capability { ok, fail -> vehicle.getWiper().checkWiperStateCapabilities({ ok(it.keys.toList()) }, fail) } ?: emptyList(),
            windows = capability { ok, fail -> vehicle.getWindow().checkWindowPositionCapabilities({ ok(it.keys.toList()) }, fail) }
                ?: emptyList(),
            cruiseState = capability<Unit> { ok, fail -> driving.checkCruiseControlStateCapabilities({ ok(Unit) }, fail) } != null,
            cruiseTarget = capability<Unit> { ok, fail -> driving.checkCruiseControlTargetSpeedCapabilities({ ok(Unit) }, fail) } != null,
            odometer = capability<Unit> { ok, fail -> vehicle.getOdometer().checkCurrentDistanceDrivenCapabilities({ ok(Unit) }, fail) } != null,
            evEfficiency = capability<Unit> { ok, fail ->
                vehicle.getEvBattery().checkInstantaneousEvEfficiencyCapabilities({ ok(Unit) }, fail)
            } != null,
        ).also { capabilities = it }
    }

    /** One SDK capability call; null if it fails (unsupported on this vehicle, or not permitted). */
    private suspend fun <T> capability(call: (ok: (T) -> Unit, fail: (Exception) -> Unit) -> Unit): T? =
        suspendCancellableCoroutine { cont ->
            call(
                { if (cont.isActive) cont.resume(it) },
                { e -> Log.i(TAG, "capability unavailable: ${e.message}"); if (cont.isActive) cont.resume(null) },
            )
        }

    private fun fail(what: String, e: Exception) {
        Log.w(TAG, "$what failed", e)
    }
}

private fun CruiseControl.toDomain() = runCatching { CruiseState.valueOf(name) }.getOrDefault(CruiseState.UNKNOWN)

internal fun SeatArea.label(): String = values.joinToString("+") { it.name }

internal fun DoorArea.label(): String = values.joinToString("+") { it.name }

internal fun WindowArea.label(): String = values.joinToString("+") { it.name }
