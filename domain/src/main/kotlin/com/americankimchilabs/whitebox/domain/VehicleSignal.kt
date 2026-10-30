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
}

enum class TurnSignalState { OFF, LEFT, RIGHT }

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
