package com.americankimchilabs.whitebox.domain

enum class SignalKind {
    BRAKE_PEDAL,
    TURN_SIGNAL,
    STEERING_ANGLE,
    GEAR,
    SPEED,
    IGNITION,
    AIRBAG,
    TELLTALE,
    CRUISE_CONTROL,
    CRUISE_TARGET_SPEED,
    ODOMETER,
    DOOR,
    SEAT_BELT,
    SEAT_OCCUPANCY,
    LIGHT,
    WIPER,
    WINDOW,
    PARKING_BRAKE,
    ROAD_CONTEXT,

    /** Derived from the head unit's accelerometer once its mounting is calibrated (see [RoadGradeEstimator]). */
    ROAD_GRADE,

    /** Instantaneous EV efficiency as the vehicle reports it (a motor-power proxy where supported). */
    EV_EFFICIENCY,

    /** Reserved: no vehicle API exposes pedal position today; the kind exists so a future source plugs in unchanged. */
    ACCELERATOR_PEDAL,

    /** Reserved: the brake is reported as on/off only today. */
    BRAKE_PRESSURE,
}

enum class TurnSignalState { OFF, LEFT, RIGHT }

enum class CruiseState { OTHER, ENABLED, ACTIVATED, USER_OVERRIDE, SUSPENDED, FORCED_DEACTIVATION_WARNING, UNKNOWN }

/** HAZARD is not readable by third-party apps today; kept so a future source needs no new kind. */
enum class Lamp { LOW_BEAM, HIGH_BEAM, TAIL_LAMP, HAZARD }

enum class RoadContextType { SPEED_LIMIT, CAUTION_POINT, ENFORCEMENT_CAMERA }

enum class GearPosition { PARK, REVERSE, NEUTRAL, DRIVE, MANUAL, UNKNOWN }

enum class IgnitionState { LOCK, OFF, ACC, ON, START, UNKNOWN }

enum class TelltaleState { OFF, ON, BLINK }

/** A single decoded value from the car, independent of which SDK produced it. */
sealed interface VehicleSignal {
    val kind: SignalKind

    /** The vehicle reports pedal on/off only; there is no pedal position. */
    data class BrakePedal(val pressed: Boolean) : VehicleSignal {
        override val kind get() = SignalKind.BRAKE_PEDAL
    }

    data class TurnSignal(val state: TurnSignalState) : VehicleSignal {
        override val kind get() = SignalKind.TURN_SIGNAL
    }

    data class SteeringAngle(val degrees: Float) : VehicleSignal {
        override val kind get() = SignalKind.STEERING_ANGLE
    }

    data class Gear(val position: GearPosition) : VehicleSignal {
        override val kind get() = SignalKind.GEAR
    }

    data class Speed(val metersPerSecond: Float) : VehicleSignal {
        override val kind get() = SignalKind.SPEED
    }

    data class Ignition(val state: IgnitionState) : VehicleSignal {
        override val kind get() = SignalKind.IGNITION
    }

    /** [seat] is an SDK-neutral label such as "ROW_1_LEFT". */
    data class AirbagDeployed(val seat: String, val deployed: Boolean) : VehicleSignal {
        override val kind get() = SignalKind.AIRBAG
    }

    /** [name] is the telltale type as the vehicle names it, e.g. "ABS", "ESC". */
    data class Telltale(val name: String, val state: TelltaleState) : VehicleSignal {
        override val kind get() = SignalKind.TELLTALE
    }

    data class CruiseControl(val state: CruiseState) : VehicleSignal {
        override val kind get() = SignalKind.CRUISE_CONTROL
    }

    data class CruiseTargetSpeed(val metersPerSecond: Float) : VehicleSignal {
        override val kind get() = SignalKind.CRUISE_TARGET_SPEED
    }

    /** Total distance driven, in the unit the vehicle reports (not converted). */
    data class Odometer(val distance: Float) : VehicleSignal {
        override val kind get() = SignalKind.ODOMETER
    }

    /** [area] is an SDK-neutral label such as "ROW_1_LEFT". */
    data class Door(val area: String, val open: Boolean) : VehicleSignal {
        override val kind get() = SignalKind.DOOR
    }

    data class SeatBelt(val seat: String, val buckled: Boolean) : VehicleSignal {
        override val kind get() = SignalKind.SEAT_BELT
    }

    data class SeatOccupancy(val seat: String, val occupied: Boolean) : VehicleSignal {
        override val kind get() = SignalKind.SEAT_OCCUPANCY
    }

    data class Light(val lamp: Lamp, val on: Boolean) : VehicleSignal {
        override val kind get() = SignalKind.LIGHT
    }

    /** [mode] is the wiper switch position as the vehicle names it, e.g. "OFF", "AUTO", "CONTINUOUS_LEVEL_1". */
    data class Wiper(val area: String, val mode: String) : VehicleSignal {
        override val kind get() = SignalKind.WIPER
    }

    /** [position] in the vehicle's own range for that window (see its capability bounds). */
    data class Window(val area: String, val position: Int) : VehicleSignal {
        override val kind get() = SignalKind.WINDOW
    }

    data class ParkingBrake(val engaged: Boolean) : VehicleSignal {
        override val kind get() = SignalKind.PARKING_BRAKE
    }

    /**
     * What the navigation knows about the road here: the speed limit in km/h ("0" when unknown), or the type of the
     * caution point / enforcement camera ahead (e.g. "SCHOOL_ZONE"), "NONE" when it clears.
     */
    data class RoadContext(val type: RoadContextType, val value: String) : VehicleSignal {
        override val kind get() = SignalKind.ROAD_CONTEXT
    }

    /** Road grade in percent along the direction of travel; positive = uphill. One value per second. */
    data class RoadGrade(val percent: Float) : VehicleSignal {
        override val kind get() = SignalKind.ROAD_GRADE
    }

    /** Raw instantaneous efficiency in the vehicle's own unit; recorded, not interpreted, until the unit is known. */
    data class EvEfficiency(val value: Float) : VehicleSignal {
        override val kind get() = SignalKind.EV_EFFICIENCY
    }

    data class AcceleratorPedal(val percent: Float) : VehicleSignal {
        override val kind get() = SignalKind.ACCELERATOR_PEDAL
    }

    data class BrakePressure(val percent: Float) : VehicleSignal {
        override val kind get() = SignalKind.BRAKE_PRESSURE
    }
}

/**
 * A signal plus when we received it.
 *
 * [receivedAt] is stamped inside the SDK callback, before any dispatch, so callback latency and
 * rate can be measured. [sourceElapsedNanos] is the producer's own
 * timestamp when the source provides one (AAOS property events do; Pleos listeners don't).
 */
data class SignalSample(
    val signal: VehicleSignal,
    val receivedAt: Timestamp,
    val sourceElapsedNanos: Long? = null,
)
