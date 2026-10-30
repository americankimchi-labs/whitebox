package com.americankimchilabs.whitebox.ui

import android.content.res.Resources
import com.americankimchilabs.whitebox.R
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.GapCause
import com.americankimchilabs.whitebox.domain.GearPosition
import com.americankimchilabs.whitebox.domain.RecorderEvent
import com.americankimchilabs.whitebox.domain.RecorderPermission
import com.americankimchilabs.whitebox.domain.TurnSignalState
import com.americankimchilabs.whitebox.domain.VehicleSignal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** User-facing wording from strings.xml. Steering is shown as signed degrees with no left/right label: the SDK documents no sign convention. */
class Labels(private val res: Resources) {
    private val clock = SimpleDateFormat("HH:mm:ss.S", Locale.KOREA)
    private val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)

    fun time(wallMillis: Long): String = clock.format(Date(wallMillis))
    fun dateTime(wallMillis: Long): String = date.format(Date(wallMillis))
    val none: String get() = res.getString(R.string.none)

    fun gear(g: GearPosition?) = when (g) {
        GearPosition.PARK -> "P"
        GearPosition.REVERSE -> "R"
        GearPosition.NEUTRAL -> "N"
        GearPosition.DRIVE -> "D"
        GearPosition.MANUAL -> "M"
        GearPosition.UNKNOWN, null -> none
    }

    fun turn(t: TurnSignalState?) = when (t) {
        TurnSignalState.LEFT -> res.getString(R.string.turn_left)
        TurnSignalState.RIGHT -> res.getString(R.string.turn_right)
        TurnSignalState.OFF -> res.getString(R.string.turn_off)
        null -> none
    }

    fun brake(on: Boolean?) = when (on) {
        true -> res.getString(R.string.brake_on)
        false -> res.getString(R.string.brake_off)
        null -> none
    }

    /** Whole degrees; −0.4 and −0 both read "0°". */
    fun steering(deg: Float?): String {
        val d = deg?.roundToInt() ?: return none
        return if (d == 0) "0°" else "%+d°".format(d)
    }

    fun speed(kmh: Float?) = kmh?.let { res.getString(R.string.speed_kmh, it) } ?: none

    fun level(l: EscalationLevel) = res.getString(
        when (l) {
            EscalationLevel.NORMAL -> R.string.level_normal
            EscalationLevel.ALERT -> R.string.level_alert
            EscalationLevel.TRIGGERED -> R.string.level_triggered
        },
    )

    fun trigger(t: CapsuleTrigger) = res.getString(
        when (t) {
            CapsuleTrigger.AIRBAG -> R.string.trigger_airbag
            CapsuleTrigger.DELTA_V -> R.string.trigger_delta_v
            CapsuleTrigger.SHORT_DELTA_V -> R.string.trigger_short_delta_v
            CapsuleTrigger.BRAKED_SPIKE -> R.string.trigger_braked_spike
            CapsuleTrigger.REAR_SHOVE -> R.string.trigger_rear_shove
            CapsuleTrigger.ALERT_RESOLVED -> R.string.trigger_alert_resolved
            CapsuleTrigger.MANUAL -> R.string.trigger_manual
        },
    )

    /** The span over which the trigger's ΔV was measured. */
    fun window(t: CapsuleTrigger): String? = when (t) {
        CapsuleTrigger.DELTA_V -> res.getString(R.string.window_300)
        CapsuleTrigger.SHORT_DELTA_V -> res.getString(R.string.window_200)
        CapsuleTrigger.BRAKED_SPIKE -> res.getString(R.string.window_120)
        CapsuleTrigger.REAR_SHOVE -> res.getString(R.string.window_shove)
        else -> null
    }

    fun permission(p: RecorderPermission) = res.getString(
        when (p) {
            RecorderPermission.LOCATION -> R.string.perm_location
            RecorderPermission.BACKGROUND_LOCATION -> R.string.perm_background
            RecorderPermission.CAR_SPEED -> R.string.perm_speed
        },
    )

    private fun gapCause(c: GapCause) = res.getString(
        when (c) {
            GapCause.CLEAN_STOP -> R.string.gap_clean
            GapCause.POWER_CYCLE -> R.string.gap_power
            GapCause.PROCESS_DIED -> R.string.gap_died
            GapCause.STOPPED_WITHOUT_RESTART -> R.string.gap_forced
        },
    )

    /** One timeline row, or null for entries the timeline doesn't list (speed, steering, location). */
    fun row(e: ChainEntry): String? = when (e) {
        is ChainEntry.Signal -> when (val s = e.sample.signal) {
            is VehicleSignal.BrakePedal -> res.getString(R.string.row_brake, brake(s.pressed))
            is VehicleSignal.TurnSignal -> res.getString(R.string.row_turn, turn(s.state))
            is VehicleSignal.Gear -> res.getString(R.string.row_gear, gear(s.position))
            is VehicleSignal.AirbagDeployed -> if (s.deployed) res.getString(R.string.row_airbag, s.seat) else null
            is VehicleSignal.SteeringAngle, is VehicleSignal.Speed, is VehicleSignal.Ignition, is VehicleSignal.Telltale -> null
        }
        is ChainEntry.Location -> null
        is ChainEntry.Recorder -> when (val r = e.event) {
            is RecorderEvent.RecordingStarted -> r.gap?.let { res.getString(R.string.row_started_gap, gapCause(it.cause), it.durationMillis / 1000.0) }
                ?: res.getString(R.string.row_started)
            RecorderEvent.RecordingStopped -> res.getString(R.string.row_stopped)
            is RecorderEvent.PermissionDegraded -> res.getString(R.string.row_degraded, permission(r.permission))
            is RecorderEvent.PermissionRestored -> res.getString(R.string.row_restored, permission(r.permission))
        }
        is ChainEntry.Escalation -> when (val x = e.event) {
            is EscalationEvent.LevelChanged -> res.getString(R.string.row_level, level(x.level))
            is EscalationEvent.Triggered -> res.getString(R.string.row_trigger, trigger(x.trigger), x.measuredKmh)
            is EscalationEvent.IncidentOffered -> res.getString(R.string.row_offered)
            is EscalationEvent.IncidentAnswered -> res.getString(if (x.keep) R.string.row_answered_keep else R.string.row_answered_drop)
        }
    }
}

/**
 * Display-only dedupe: on subscribe the SDK re-sends current values, so a vehicle-state row
 * that repeats the last shown value of its kind is hidden. The chain keeps every raw record.
 */
class RowFilter {
    private val last = mutableMapOf<Class<*>, VehicleSignal>()

    fun shows(e: ChainEntry): Boolean {
        val s = (e as? ChainEntry.Signal)?.sample?.signal ?: return true
        if (s !is VehicleSignal.BrakePedal && s !is VehicleSignal.TurnSignal && s !is VehicleSignal.Gear) return true
        val repeat = last[s.javaClass] == s
        last[s.javaClass] = s
        return !repeat
    }
}
