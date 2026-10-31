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

    /** Records this far apart (by the car's clock) belong to different drives in the owner's drive list. */
    const val DRIVE_GAP_MS = 5 * 60_000L
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

    /**
     * Brake held without slowing. On speed alone this is identical to drag braking downhill, so it counts only when
     * recorded road grades cover the whole hold and never drop below −BRAKE_NO_DECEL_MIN_GRADE_PCT (flat or uphill:
     * the brake should be slowing the car, so something is pushing it) and no downhill caution area is active.
     * On the Pleos Connect emulator no grade is ever recorded (its accelerometer does not move with the simulated
     * drive), so this pattern cannot fire there; BRAKE_SPEED_RISE covers unintended acceleration on speed alone.
     */
    const val BRAKE_NO_DECEL_HELD_MS = 3000
    const val BRAKE_NO_DECEL_MIN_KMH = 40
    const val BRAKE_NO_DECEL_MAX_DROP_KMH = 1
    const val BRAKE_NO_DECEL_MIN_GRADE_PCT = 2

    /** A road grade older than this does not count as the current grade. */
    const val GRADE_STALE_MS = 1500

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

    /**
     * How long the driver has to answer after the keep question ends: ample time to answer, short enough not to pick
     * up the cabin's conversation. No answer by then is silence (kept). An interruption restarts it in full.
     */
    const val KEEP_ANSWER_WINDOW_S = 10

    /** Waiting for the audio (a long call) longer than this gives the keep question up: silence, kept. */
    const val KEEP_QUESTION_MAX_S = 120

    /** The keep question is tried this many times when the voice engine fails before giving up (kept). */
    const val KEEP_MAX_ASKS = 3

    /** Post-crash guidance is spoken when the car is put in P within this long after a crash-level trigger. */
    const val GUIDANCE_WINDOW_S = 600

    // Dashcam sync chirp: above human hearing (≥ 20 kHz), inside the 48 kHz output path. Dashcams that low-pass their
    // audio below this will not record it; sync then falls back to GPS time and the recorded spoken lines.
    const val CHIRP_LOW_HZ = 20_000
    const val CHIRP_HIGH_HZ = 22_000
    const val CHIRP_MS = 100

    /** Raised-cosine fade at both ends: an abrupt start would click audibly across the whole spectrum. */
    const val CHIRP_FADE_MS = 15
    const val CHIRP_AMPLITUDE = 0.3f

    /** One chirp this often while driving; longer than any plausible GPS-time pre-alignment error, so unambiguous. */
    const val CHIRP_PERIOD_S = 15

    // Handover mode (대리 운전)

    /** Armed but no trip started this long after: cancelled (the owner changed plans). */
    const val HANDOVER_ARM_EXPIRY_MS = 12 * 3_600_000L

    /**
     * After the trip looks finished (engine off, or parked a while), it is final only if the car stays put this long:
     * a 대리기사 often stops at the entrance and then moves the car to its parking spot, or waits a moment for the
     * owner. Driving off within it resumes the same trip.
     */
    const val HANDOVER_GRACE_MS = 10 * 60_000L

    /** Parked with the engine still on this long ends the trip (engine-off ends it at once, still with the grace). */
    const val HANDOVER_PARKED_END_MS = 10 * 60_000L

    /** Speeding counts only above an enforcement limit by more than this (km/h), as Korean enforcement does. */
    const val HANDOVER_SPEEDING_MARGIN_KMH = 10

    /**
     * 급가속: speed up by this much (km/h) within one second, from at least [HARD_ACCEL_MIN_KMH]. The common Korean
     * definition used for commercial-vehicle digital tachograph analysis (11 km/h per second); tune on real data.
     */
    const val HARD_ACCEL_KMH_PER_S = 11f
    const val HARD_ACCEL_MIN_KMH = 5f

    /** The same spoken reminder at most this often (each kind on its own). */
    const val HANDOVER_VOICE_COOLDOWN_MS = 60_000L

    /** A speed sample older than this does not count as the current speed. */
    const val SPEED_STALE_MS = 500
}
