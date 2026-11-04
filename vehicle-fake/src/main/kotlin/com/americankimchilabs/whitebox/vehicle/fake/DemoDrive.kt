package com.americankimchilabs.whitebox.vehicle.fake

import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.core.LocationSource
import com.americankimchilabs.whitebox.domain.CruiseState
import com.americankimchilabs.whitebox.domain.GearPosition
import com.americankimchilabs.whitebox.domain.Lamp
import com.americankimchilabs.whitebox.domain.LocationFix
import com.americankimchilabs.whitebox.domain.RoadContextType
import com.americankimchilabs.whitebox.domain.TurnSignalState
import com.americankimchilabs.whitebox.domain.VehicleSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.cos

/**
 * The demo drive (≈75 s), shared by the replay mode and tools/virtual-console/demo.py so both tell the same story:
 * the driving context at the start (belt, door, lamps, wipers, cruise, speed limit), parking brake off and out of
 * Park, lane changes with turn signals, an ordinary brake, then a hard brake (ALERT, a near-miss capsule) that ends
 * in a crash-profile speed drop (TRIGGERED, primary ΔV, the capsule escalates) with the airbags firing (the capsule
 * becomes a 큰 사고), stillness → the capsule seals, back to Park. Everything here is made up: it runs in test builds,
 * the tests and the demo car on the server, never in the release app.
 *
 * Speed is emitted continuously at 10 Hz like PERF_VEHICLE_SPEED; everything else on change.
 */
object DemoDrive {

    /** Absolute time (ms from start) of the crash-profile sample, for tests and the narration. */
    const val IMPACT_AT_MS = 52_500L

    /** Where the car's odometer stands when the drive begins (km). */
    private const val ODOMETER_START_KM = 12_480.0

    fun signals(): List<ScriptStep> = build().signals

    fun locations(): List<LocationStep> = build().locations

    private class Builder {
        val timed = mutableListOf<Pair<Long, VehicleSignal>>()
        val speedTrack = mutableListOf<Pair<Long, Float>>() // (abs ms, km/h)
        var t = 0L
        var kmh = 0f
        // The odometer, as a real car reports it: in 0.1 km steps as the distance adds up.
        var km = ODOMETER_START_KM
        var shownKm = ODOMETER_START_KM

        fun now(s: VehicleSignal) {
            timed += t to s
        }

        /** Linear speed change to [to] over [ms], one sample per 100 ms. */
        fun speed(ms: Long, to: Float = kmh) {
            val from = kmh
            val steps = (ms / 100).coerceAtLeast(1)
            for (i in 1..steps) {
                t += 100
                kmh = from + (to - from) * i / steps
                speedTrack += t to kmh
                timed += t to VehicleSignal.Speed(kmh / 3.6f)
                km += kmh * 0.1 / 3_600
                if (km - shownKm >= 0.1) {
                    shownKm = Math.round(km * 10) / 10.0
                    timed += t to VehicleSignal.Odometer(shownKm.toFloat())
                }
            }
        }

        /** Steering sweep emitted on change at ≈10 Hz while [ms] of speed samples run. */
        fun steer(ms: Long, peakDegrees: Float) {
            val start = t
            speed(ms)
            val steps = ms / 100
            for (i in 1..steps) {
                val x = i.toFloat() / steps
                val deg = peakDegrees * kotlin.math.sin(Math.PI * x).toFloat()
                timed += (start + i * 100) to VehicleSignal.SteeringAngle(Math.round(deg).toFloat())
            }
        }

        val signals: List<ScriptStep>
            get() {
                var prev = 0L
                return timed.sortedBy { it.first }.map { (at, s) -> ScriptStep(at - prev, s).also { prev = at } }
            }

        /** 1 Hz fixes along a straight road heading north-east from Siheung-dong, Seongnam, integrating speed. */
        val locations: List<LocationStep>
            get() {
                var lat = 37.4395
                var lon = 127.1270
                var meters = 0.0
                var lastMs = 0L
                var prev = 0L
                return (0..t step 1_000).map { at ->
                    meters = speedTrack.filter { it.first in (lastMs + 1)..at }.sumOf { it.second / 3.6 * 0.1 }
                    lastMs = at
                    lat += meters * 0.7071 / 111_320.0
                    lon += meters * 0.7071 / (111_320.0 * cos(Math.toRadians(lat)))
                    LocationStep(at - prev, lat, lon).also { prev = at }
                }
            }
    }

    private fun build() = Builder().apply {
        now(VehicleSignal.Odometer(ODOMETER_START_KM.toFloat()))
        now(VehicleSignal.Gear(GearPosition.PARK))
        now(VehicleSignal.BrakePedal(true))
        now(VehicleSignal.TurnSignal(TurnSignalState.OFF))
        now(VehicleSignal.SteeringAngle(0f))
        now(VehicleSignal.ParkingBrake(true))
        now(VehicleSignal.SeatBelt("ROW_1_LEFT", buckled = true))
        now(VehicleSignal.Door("ROW_1_LEFT", open = false))
        now(VehicleSignal.Light(Lamp.LOW_BEAM, on = true))
        now(VehicleSignal.Wiper("FRONT_WINDSHIELD", "OFF"))
        now(VehicleSignal.CruiseControl(CruiseState.ENABLED))
        now(VehicleSignal.RoadContext(RoadContextType.SPEED_LIMIT, "60"))
        now(VehicleSignal.RoadContext(RoadContextType.ROUTE, "GUIDING")) // a destination set before leaving
        speed(3_000) // parked
        now(VehicleSignal.ParkingBrake(false))
        now(VehicleSignal.Gear(GearPosition.DRIVE))
        speed(1_000)
        now(VehicleSignal.BrakePedal(false))
        speed(8_000, to = 50f) // pull away

        now(VehicleSignal.TurnSignal(TurnSignalState.LEFT)) // lane change left
        speed(2_000)
        steer(2_000, -12f)
        now(VehicleSignal.TurnSignal(TurnSignalState.OFF))
        speed(6_000)

        now(VehicleSignal.BrakePedal(true)) // ordinary brake: 5 km/h/s, no ALERT
        speed(2_000, to = 40f)
        now(VehicleSignal.BrakePedal(false))
        speed(2_000, to = 60f)
        // A speed camera on a 40 km/h stretch: announced 600 m ahead, passed at 60 km/h (handover mode's speeding).
        now(VehicleSignal.RoadContext(RoadContextType.SPEED_ENFORCEMENT, "POINT;40;0;600"))
        speed(2_000)
        now(VehicleSignal.RoadContext(RoadContextType.SPEED_ENFORCEMENT, "POINT;40;0;80"))
        speed(1_000)
        now(VehicleSignal.RoadContext(RoadContextType.SPEED_ENFORCEMENT, "NONE"))

        now(VehicleSignal.TurnSignal(TurnSignalState.RIGHT)) // lane change right
        speed(2_000)
        steer(2_000, 12f)
        now(VehicleSignal.TurnSignal(TurnSignalState.OFF))
        speed(IMPACT_AT_MS - 900 - t) // cruise until the hard brake

        now(VehicleSignal.BrakePedal(true)) // hard brake: 60 → 50 in 500 ms (20 km/h/s) → ALERT
        speed(500, to = 50f)
        speed(300)
        speed(100, to = 25f) // impact: −25 km/h in one 100 ms sample → TRIGGERED, primary ΔV
        // A frontal hit this hard fires the airbags; their ECU reports a few tens of ms later, turning the crash
        // capsule into a 큰 사고. Made up like the rest of this drive: it exists only in test builds and the demo car.
        timed += (t + 40) to VehicleSignal.AirbagDeployed("ROW_1_LEFT", true)
        speed(2_000, to = 0f)
        speed(12_000) // stillness → final seal
        now(VehicleSignal.Gear(GearPosition.PARK))
        speed(500)
        now(VehicleSignal.BrakePedal(false)) // in P the driver lets go of the brake
        speed(2_500)
    }
}

/** One scripted fix, [afterMillis] after the previous one. */
data class LocationStep(val afterMillis: Long, val latitude: Double, val longitude: Double)

/** Replays [steps] as GPS fixes; GPS time is the clock's wall time (a replay has no satellites). */
class ScriptedLocationSource(private val steps: List<LocationStep>, private val clock: Clock) : LocationSource {
    override fun fixes(): Flow<LocationFix> = flow {
        for (s in steps) {
            delay(s.afterMillis)
            val now = clock.now()
            emit(LocationFix(s.latitude, s.longitude, accuracyMeters = 5f, gpsTimeMillis = now.wallMillis, receivedAt = now))
        }
    }
}
