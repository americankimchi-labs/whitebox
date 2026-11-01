package com.americankimchilabs.whitebox.ui

import android.content.res.Resources
import com.americankimchilabs.whitebox.R
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.LibraryEvent
import com.americankimchilabs.whitebox.domain.Tier
import com.americankimchilabs.whitebox.domain.MeasureUnit
import com.americankimchilabs.whitebox.domain.AudioEvent
import com.americankimchilabs.whitebox.domain.BookmarkSource
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.CruiseState
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.GapCause
import com.americankimchilabs.whitebox.domain.GearPosition
import com.americankimchilabs.whitebox.domain.HandoverEndReason
import com.americankimchilabs.whitebox.domain.HandoverEvent
import com.americankimchilabs.whitebox.domain.HandoverEventKind
import com.americankimchilabs.whitebox.domain.HandoverSource
import com.americankimchilabs.whitebox.domain.Lamp
import com.americankimchilabs.whitebox.domain.RecorderEvent
import com.americankimchilabs.whitebox.domain.RecorderPermission
import com.americankimchilabs.whitebox.domain.RoadContextType
import com.americankimchilabs.whitebox.domain.TurnSignalState
import com.americankimchilabs.whitebox.domain.VehicleSignal
import com.americankimchilabs.whitebox.domain.VoiceLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** User-facing wording from strings.xml. Steering is shown as signed degrees with no left/right label: the SDK documents no sign convention. */
class Labels(private val res: Resources) {
    private val clock = SimpleDateFormat("HH:mm:ss.S", Locale.KOREA)
    private val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
    private val day = SimpleDateFormat("yyyy년 M월 d일", Locale.KOREA)

    fun time(wallMillis: Long): String = clock.format(Date(wallMillis))
    fun dateTime(wallMillis: Long): String = date.format(Date(wallMillis))
    fun date(wallMillis: Long): String = day.format(Date(wallMillis))
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
            CapsuleTrigger.TEST_AIRBAG -> R.string.trigger_test_airbag
            CapsuleTrigger.DELTA_V -> R.string.trigger_delta_v
            CapsuleTrigger.SHORT_DELTA_V -> R.string.trigger_short_delta_v
            CapsuleTrigger.BRAKED_SPIKE -> R.string.trigger_braked_spike
            CapsuleTrigger.REAR_SHOVE -> R.string.trigger_rear_shove
            CapsuleTrigger.ALERT_RESOLVED -> R.string.trigger_alert_resolved
            CapsuleTrigger.MANUAL -> R.string.trigger_manual
            CapsuleTrigger.HARD_BRAKE -> R.string.trigger_hard_brake
            CapsuleTrigger.EVASIVE_STEER -> R.string.trigger_evasive_steer
            CapsuleTrigger.BRAKE_SWERVE -> R.string.trigger_brake_swerve
            CapsuleTrigger.SHIFT_TO_NEUTRAL -> R.string.trigger_shift_to_neutral
            CapsuleTrigger.BRAKE_SPEED_RISE -> R.string.trigger_brake_speed_rise
            CapsuleTrigger.BRAKE_NO_DECEL -> R.string.trigger_brake_no_decel
            CapsuleTrigger.BRAKE_PUMPING -> R.string.trigger_brake_pumping
            CapsuleTrigger.PARKING_BRAKE_MOVING -> R.string.trigger_parking_brake_moving
            CapsuleTrigger.LURCH -> R.string.trigger_lurch
            CapsuleTrigger.POST_EVENT_EXIT -> R.string.trigger_post_event_exit
            CapsuleTrigger.HANDOVER -> R.string.trigger_handover
        },
    )

    /** "■ type · value" in the trigger's own unit. */
    fun triggerRow(x: EscalationEvent.Triggered): String = when (x.trigger.unit) {
        MeasureUnit.DELTA_KMH -> res.getString(R.string.row_trigger, trigger(x.trigger), x.measured)
        MeasureUnit.KMH -> res.getString(R.string.row_trigger_kmh, trigger(x.trigger), x.measured)
        MeasureUnit.DEG_PER_S -> res.getString(R.string.row_trigger_dps, trigger(x.trigger), x.measured)
        MeasureUnit.COUNT -> res.getString(R.string.row_trigger_count, trigger(x.trigger), x.measured.toInt())
        MeasureUnit.NONE -> res.getString(R.string.row_trigger_plain, trigger(x.trigger))
    }

    /** The span over which the trigger's ΔV was measured. */
    fun window(t: CapsuleTrigger): String? = when (t) {
        CapsuleTrigger.DELTA_V -> res.getString(R.string.window_300)
        CapsuleTrigger.SHORT_DELTA_V -> res.getString(R.string.window_200)
        CapsuleTrigger.BRAKED_SPIKE -> res.getString(R.string.window_120)
        CapsuleTrigger.REAR_SHOVE -> res.getString(R.string.window_shove)
        CapsuleTrigger.HARD_BRAKE -> res.getString(R.string.window_500)
        CapsuleTrigger.BRAKE_SPEED_RISE -> res.getString(R.string.window_rise)
        CapsuleTrigger.BRAKE_NO_DECEL -> res.getString(R.string.window_no_decel)
        CapsuleTrigger.LURCH -> res.getString(R.string.window_lurch)
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

    /** Seat or door area in Korean; unknown areas pass through. */
    fun area(a: String) = when (a) {
        "ROW_1_LEFT" -> res.getString(R.string.area_driver)
        "ROW_1_RIGHT" -> res.getString(R.string.area_passenger)
        "ROW_2_LEFT" -> res.getString(R.string.area_rear_left)
        "ROW_2_RIGHT" -> res.getString(R.string.area_rear_right)
        "ROW_2_CENTER" -> res.getString(R.string.area_rear_center)
        "HOOD" -> res.getString(R.string.area_hood)
        "REAR" -> res.getString(R.string.area_trunk)
        "FRONT_WINDSHIELD" -> res.getString(R.string.area_front_wiper)
        "REAR_WINDSHIELD" -> res.getString(R.string.area_rear_wiper)
        else -> a
    }

    fun lamp(l: Lamp) = res.getString(
        when (l) {
            Lamp.LOW_BEAM -> R.string.lamp_low
            Lamp.HIGH_BEAM -> R.string.lamp_high
            Lamp.TAIL_LAMP -> R.string.lamp_tail
            Lamp.HAZARD -> R.string.lamp_hazard
        },
    )

    fun wiper(mode: String) = when {
        mode == "OFF" -> res.getString(R.string.state_off)
        mode == "AUTO" -> res.getString(R.string.wiper_auto)
        mode == "ON" || mode.startsWith("CONTINUOUS") || mode.startsWith("INTERMITTENT") || mode == "LOW" || mode == "HIGH" -> res.getString(R.string.wiper_running)
        else -> mode
    }

    fun cruise(c: CruiseState) = res.getString(
        when (c) {
            CruiseState.ENABLED -> R.string.cruise_ready
            CruiseState.ACTIVATED -> R.string.cruise_active
            CruiseState.USER_OVERRIDE -> R.string.cruise_override
            CruiseState.SUSPENDED -> R.string.cruise_suspended
            CruiseState.FORCED_DEACTIVATION_WARNING -> R.string.cruise_forced_off
            CruiseState.OTHER, CruiseState.UNKNOWN -> R.string.state_off
        },
    )

    /** Caution point / camera type from the navigation, in Korean where we know it. */
    fun roadContext(v: String) = when {
        "SCHOOL_ZONE" in v -> res.getString(R.string.road_school_zone)
        "WRONG_WAY" in v -> res.getString(R.string.road_wrong_way)
        "TRAFFIC_LIGHT" in v -> res.getString(R.string.road_traffic_light)
        "CUT_IN" in v || "LANE_CHANGE" in v -> res.getString(R.string.road_lane_change)
        "SPEED_CAMERA" in v || "SECTION_SPEED" in v -> res.getString(R.string.road_speed_camera)
        else -> v
    }

    /** One timeline row, or null for entries the timeline doesn't list (speed, steering, location). */
    fun row(e: ChainEntry): String? = when (e) {
        is ChainEntry.Signal -> when (val s = e.sample.signal) {
            is VehicleSignal.BrakePedal -> res.getString(R.string.row_brake, brake(s.pressed))
            is VehicleSignal.TurnSignal -> res.getString(R.string.row_turn, turn(s.state))
            is VehicleSignal.Gear -> res.getString(R.string.row_gear, gear(s.position))
            is VehicleSignal.AirbagDeployed -> if (s.deployed) res.getString(if (s.test) R.string.row_airbag_test else R.string.row_airbag, s.seat) else null
            is VehicleSignal.Door -> res.getString(
                if (s.area == "HOOD" || s.area == "REAR") R.string.row_lid else R.string.row_door, // hood and trunk are not doors
                area(s.area), res.getString(if (s.open) R.string.state_open else R.string.state_closed),
            )
            is VehicleSignal.SeatBelt -> res.getString(R.string.row_seat_belt, area(s.seat), res.getString(if (s.buckled) R.string.belt_on else R.string.belt_off))
            is VehicleSignal.ParkingBrake -> res.getString(R.string.row_parking_brake, res.getString(if (s.engaged) R.string.pbrake_on else R.string.pbrake_off))
            is VehicleSignal.Light -> res.getString(R.string.row_light, lamp(s.lamp), res.getString(if (s.on) R.string.state_on else R.string.state_off))
            is VehicleSignal.Wiper -> res.getString(R.string.row_wiper, wiper(s.mode))
            is VehicleSignal.CruiseControl -> res.getString(R.string.row_cruise, cruise(s.state))
            is VehicleSignal.RoadContext -> when {
                s.type == RoadContextType.SPEED_LIMIT || s.type == RoadContextType.SPEED_ENFORCEMENT || s.value == "NONE" -> null
                s.type == RoadContextType.ROUTE -> when (s.value) {
                    "GUIDING" -> res.getString(R.string.row_route_guiding)
                    "REROUTED" -> res.getString(R.string.row_route_rerouted)
                    "ARRIVED" -> res.getString(R.string.row_route_arrived)
                    else -> null
                }
                else -> res.getString(R.string.row_road, roadContext(s.value))
            }
            // Not listed: continuous or slow-changing values the packet shows as context instead.
            is VehicleSignal.SteeringAngle, is VehicleSignal.Speed, is VehicleSignal.Ignition, is VehicleSignal.Telltale,
            is VehicleSignal.CruiseTargetSpeed, is VehicleSignal.Odometer, is VehicleSignal.SeatOccupancy, is VehicleSignal.Window,
            is VehicleSignal.RoadGrade, is VehicleSignal.EvEfficiency,
            is VehicleSignal.AcceleratorPedal, is VehicleSignal.BrakePressure -> null
        }
        is ChainEntry.Location -> null
        is ChainEntry.Recorder -> when (val r = e.event) {
            is RecorderEvent.RecordingStarted -> r.gap?.let { res.getString(R.string.row_started_gap, gapCause(it.cause), it.durationMillis / 1000.0) }
                ?: res.getString(R.string.row_started)
            RecorderEvent.RecordingStopped -> res.getString(R.string.row_stopped)
            is RecorderEvent.PermissionDegraded -> res.getString(R.string.row_degraded, permission(r.permission))
            is RecorderEvent.PermissionRestored -> res.getString(R.string.row_restored, permission(r.permission))
            is RecorderEvent.Pruned -> res.getString(if (r.unsent) R.string.row_pruned_unsent else R.string.row_pruned, dateTime(r.throughWall))
            is RecorderEvent.Slept -> res.getString(R.string.row_slept, (r.sleptMillis / 1000).let { s -> if (s >= 60) "${s / 60}분 ${s % 60}초" else "${s}초" })
        }
        is ChainEntry.Escalation -> when (val x = e.event) {
            is EscalationEvent.LevelChanged -> res.getString(R.string.row_level, level(x.level))
            is EscalationEvent.Triggered -> triggerRow(x)
            is EscalationEvent.IncidentOffered -> res.getString(R.string.row_offered)
            is EscalationEvent.IncidentAnswered -> res.getString(
                when {
                    !x.answered -> R.string.row_answered_silent
                    x.keep -> R.string.row_answered_keep
                    else -> R.string.row_answered_drop
                },
            )
        }
        is ChainEntry.Audio -> when (val a = e.event) {
            is AudioEvent.Bookmark -> res.getString(
                if (a.source == BookmarkSource.ASSISTANT) R.string.row_bookmark_assistant else R.string.row_bookmark_offline,
            )
            is AudioEvent.Spoke -> res.getString(if (a.complete) R.string.row_spoke else R.string.row_spoke_cut, res.getString(voiceLineText(a.line)))
            is AudioEvent.Beacon -> null // every few seconds while driving; the packet's sync data, not a timeline row
        }
        is ChainEntry.Handover -> handoverRow(e.event)
        is ChainEntry.Library -> when (val k = e.event) {
            is LibraryEvent.PlanChanged -> res.getString(R.string.row_plan, plan(k.tier))
            is LibraryEvent.Left -> res.getString(R.string.row_library_left)
            is LibraryEvent.Returned -> res.getString(R.string.row_library_returned)
            is LibraryEvent.Extended -> res.getString(R.string.row_extended, date(k.untilWall))
        }
    }

    fun plan(t: Tier): String = res.getString(
        when (t) {
            Tier.FREE -> R.string.plan_free
            Tier.PLUS -> R.string.plan_plus
            Tier.PRO -> R.string.plan_pro
        },
    )

    fun handoverRow(h: HandoverEvent): String = when (h) {
        is HandoverEvent.Armed -> res.getString(R.string.row_handover_armed, source(h.by))
        is HandoverEvent.Cancelled -> res.getString(R.string.row_handover_cancelled, endReason(h.reason))
        is HandoverEvent.Started -> res.getString(R.string.row_handover_started)
        is HandoverEvent.Observed -> handoverEvent(h)
        is HandoverEvent.Ending -> res.getString(R.string.row_handover_ending, endReason(h.reason))
        HandoverEvent.Resumed -> res.getString(R.string.row_handover_resumed)
        is HandoverEvent.Ended -> res.getString(R.string.row_handover_ended, endReason(h.reason))
    }

    /** One thing handover mode noticed, in words with its value. */
    fun handoverEvent(h: HandoverEvent.Observed): String = when (h.kind) {
        HandoverEventKind.SPEEDING -> res.getString(R.string.handover_speeding, h.value, h.limit ?: 0f)
        HandoverEventKind.SECTION_SPEEDING -> res.getString(R.string.handover_section_speeding, h.value, h.limit ?: 0f)
        HandoverEventKind.HARD_BRAKE -> res.getString(R.string.handover_hard_brake, h.value)
        HandoverEventKind.CRASH -> res.getString(R.string.handover_crash, h.value)
        HandoverEventKind.HARD_ACCEL -> res.getString(R.string.handover_hard_accel, h.value)
        HandoverEventKind.SHARP_STEER -> res.getString(R.string.handover_sharp_steer, h.value)
        HandoverEventKind.ROUTE_LEFT -> res.getString(R.string.handover_route_left)
        HandoverEventKind.ARRIVED -> res.getString(R.string.handover_arrived)
        HandoverEventKind.NO_ROUTE -> res.getString(R.string.handover_no_route)
    }

    fun source(s: HandoverSource) = res.getString(if (s == HandoverSource.CAR) R.string.handover_by_car else R.string.handover_by_phone)

    fun endReason(r: HandoverEndReason) = res.getString(
        when (r) {
            HandoverEndReason.IGNITION_OFF -> R.string.handover_end_ignition
            HandoverEndReason.PARKED -> R.string.handover_end_parked
            HandoverEndReason.ARRIVED -> R.string.handover_end_arrived
            HandoverEndReason.OWNER -> R.string.handover_end_owner
            HandoverEndReason.EXPIRED -> R.string.handover_end_expired
            HandoverEndReason.RESTART -> R.string.handover_end_restart
        },
    )
}

/** The words of each line the car speaks (also what the timeline quotes). */
fun voiceLineText(line: VoiceLine): Int = when (line) {
    VoiceLine.KEEP_QUESTION -> R.string.voice_keep_question
    VoiceLine.KEPT -> R.string.voice_kept
    VoiceLine.RELEASED -> R.string.voice_released
    VoiceLine.CRASH_GUIDANCE -> R.string.voice_crash_guidance
    VoiceLine.BOOKMARKED -> R.string.voice_bookmarked
    VoiceLine.HANDOVER_STARTED -> R.string.voice_handover_started
    VoiceLine.HANDOVER_SET_DESTINATION -> R.string.voice_handover_set_destination
    VoiceLine.HANDOVER_SLOW_DOWN -> R.string.voice_handover_slow_down
    VoiceLine.HANDOVER_DRIVE_SAFELY -> R.string.voice_handover_drive_safely
    VoiceLine.HANDOVER_CHECK_ROUTE -> R.string.voice_handover_check_route
    VoiceLine.LIBRARY_FULL -> R.string.voice_library_full
}

/**
 * Display-only dedupe: on subscribe the SDK re-sends current values, so a vehicle-state row
 * that repeats the last shown value of its kind is hidden. The chain keeps every raw record.
 */
class RowFilter {
    private val last = mutableMapOf<String, VehicleSignal>()

    fun shows(e: ChainEntry): Boolean {
        val s = (e as? ChainEntry.Signal)?.sample?.signal ?: return true
        val key = when (s) {
            is VehicleSignal.BrakePedal, is VehicleSignal.TurnSignal, is VehicleSignal.Gear, is VehicleSignal.ParkingBrake,
            is VehicleSignal.CruiseControl -> s.kind.name
            is VehicleSignal.Door -> "${s.kind}:${s.area}"
            is VehicleSignal.SeatBelt -> "${s.kind}:${s.seat}"
            is VehicleSignal.Light -> "${s.kind}:${s.lamp}"
            is VehicleSignal.Wiper -> "${s.kind}:${s.area}"
            is VehicleSignal.RoadContext -> "${s.kind}:${s.type}"
            else -> return true
        }
        val repeat = last[key] == s
        last[key] = s
        return !repeat
    }
}
