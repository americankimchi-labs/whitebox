package com.americankimchilabs.whitebox.domain

/**
 * Tunable constants, all in one place.
 */
object Tuning {
    // Server dead-man switch
    const val DEADMAN_SEAL_SILENCE_S = 10
    const val DEADMAN_NOTIFY_SILENCE_S = 60

    // Mirror and escalation
    const val NORMAL_BATCH_INTERVAL_S = 10

    /** In ALERT each received event is forwarded at once; this only caps the rate per signal kind. */
    const val ALERT_FORWARD_MAX_HZ = 20

    // ALERT entry: brake ON while moving and slowing hard. Provisional; ordinary braking is ≈ 0.1–0.3 g (3.5–10 km/h/s).
    const val ALERT_MIN_SPEED_KMH = 15
    const val ALERT_DECEL_WINDOW_MS = 500
    const val ALERT_DECEL_KMHPS = 15

    /** ALERT returns to NORMAL after this long with the brake off and the car moving; the incident stays pending. */
    const val ALERT_CLEAR_S = 5

    // TRIGGERED derived ΔV: the Part 563 rate (8 km/h per 150 ms) scaled to a sliding window, because a 10 Hz
    // speed source makes a 150 ms window alias-prone.
    const val TRIGGER_DV_WINDOW_MS = 300
    const val TRIGGER_DV_KMH = 16

    // S1: ΔV over a 150–250 ms span (≥ 50 km/h/s ≈ 1.4 g; braking tops out ≈ 1.2 g).
    const val TRIGGER_DV2_WINDOW_MS = 200
    const val TRIGGER_DV2_SPAN_MIN_MS = 150
    const val TRIGGER_DV2_SPAN_MAX_MS = 250
    const val TRIGGER_DV2_KMH = 10

    // S2: brake ON within the lookback, short drop, confirmed when the next sample does not rebound.
    const val TRIGGER_BRAKE_LOOKBACK_MS = 2000
    const val TRIGGER_BRAKED_DV_KMH = 6
    const val TRIGGER_BRAKED_SPAN_MAX_MS = 120
    const val TRIGGER_REBOUND_KMH = 3

    // S3: speed increase while the brake is ON (stationary or slow car shoved from behind).
    const val TRIGGER_SHOVE_DV_KMH = 6
    const val TRIGGER_SHOVE_SPAN_MAX_MS = 200

    /** S3 baseline must be steady: no sample in this long before it was faster by more than TRIGGER_REBOUND_KMH
     *  (otherwise a braking glitch that rebounds would look like a shove). */
    const val TRIGGER_SHOVE_BASELINE_MS = 300

    /** After a trigger, further triggers inside the same open capsule count only after this long (one impact = one). */
    const val TRIGGER_REFRACTORY_MS = 1000

    /** Guard for every speed condition: no span may contain a larger sample gap, and none crosses a recorder start. */
    const val TRIGGER_MAX_SAMPLE_GAP_MS = 250

    // Capsules
    const val CAPSULE_PRE_WINDOW_S = 120
    const val CAPSULE_STILLNESS_CLOSE_S = 10

    /** A capsule that never reaches stillness is closed after this long. */
    const val CAPSULE_MAX_OPEN_S = 600

    /** A near-miss capsule (non-severe trigger) closes this long after its last trigger if the car drives on. */
    const val CAPSULE_POST_WINDOW_S = 30

    /** Below this the car counts as still. */
    const val STILL_SPEED_KMH = 1

    // Near-miss and 급발진 patterns. Every one opens a capsule without asking (non-severe). Provisional values from
    // the emulator spikes and published reference points; tune on real driving data.

    /**
     * Evasive steering: steering-wheel rate between consecutive samples. Measured on the emulator: an ordinary lane
     * change 51°/s, evasive manoeuvres 350–670°/s; reference points: ~99.5 % of normal-driving samples < 90°/s,
     * ~140°/s "sharp", 300°/s used to recognise evasion.
     */
    const val STEER_RATE_DPS = 300
    const val STEER_MIN_SPEED_KMH = 30
    const val STEER_MAX_SAMPLE_GAP_MS = 250

    /** Brake on (or released) within this of an evasive steer makes it brake + swerve. */
    const val SWERVE_BRAKE_WINDOW_MS = 1000

    /** Gear to N while moving at least this fast (the advised response to unintended acceleration). */
    const val NEUTRAL_MIN_SPEED_KMH = 20

    /**
     * Speed rising while the brake is held: the strongest 급발진 signature we can read. Brake held continuously for
     * the whole window, a total gain of at least BRAKE_RISE_KMH (12 km/h in 3 s ≈ 0.11 g, more than gravity gives on
     * a grade under ~11 % even with no braking at all, so downhill drag braking does not qualify), and rising in
     * every second of it (no single bump).
     */
    const val BRAKE_RISE_HELD_MS = 3000
    const val BRAKE_RISE_KMH = 12
    const val BRAKE_RISE_STEP_KMH = 1

    /** Repeated brake presses while moving. */
    const val BRAKE_PUMP_COUNT = 3
    const val BRAKE_PUMP_WINDOW_MS = 3000
    const val BRAKE_PUMP_MIN_KMH = 20

    /** Parking brake engaged while moving at least this fast. */
    const val PARKING_BRAKE_MIN_KMH = 10

    /** Lurch: from standstill to this speed within the window (scenario 2's '울컥'). */
    const val LURCH_KMH = 15
    const val LURCH_WINDOW_MS = 1000
    const val LURCH_STILL_MS = 1000

    /** A door opening while stopped this soon after a dangerous moment marks a post-event stop. */
    const val EXIT_DANGER_LOOKBACK_S = 180

    /** The voice "방금 상황을 보관할까요?" is asked only if the car is still driving this fast this long after a near-miss. */
    const val KEEP_PROMPT_DELAY_S = 5
    const val KEEP_PROMPT_MIN_KMH = 10

    /** How long an answer to the keep question is accepted; no answer by then counts as silence (kept). */
    const val KEEP_ANSWER_WINDOW_S = 8

    /** A speed sample older than this does not count as the current speed. */
    const val SPEED_STALE_MS = 500
}
