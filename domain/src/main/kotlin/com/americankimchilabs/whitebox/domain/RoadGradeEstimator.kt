package com.americankimchilabs.whitebox.domain

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt
import kotlin.math.tan

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = Vec3(x * k, y * k, z * k)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    val length get() = sqrt(this dot this)
    fun unit() = this * (1f / length)
}

/**
 * The head unit's accelerometer averaged over one window (m/s², sensor frame, specific force: reads +g "up" at rest).
 * Times are on the elapsed-realtime clock, like the VHAL speed timestamps.
 */
data class MotionWindow(val startNs: Long, val endNs: Long, val mean: Vec3, val count: Int)

/**
 * Road grade from the head unit's accelerometer, independent of how the unit is mounted.
 *
 * Along the car's forward axis f the accelerometer reads `a·f = dv/dt + g·sin θ` (θ = pitch, positive uphill). The
 * speed signal gives dv/dt, so `sin θ = (a·f − dv/dt) / g` once f is known. f is learned from the car's own speed
 * changes: whenever it speeds up or slows down, the reading moves away from its resting value along f by dv/dt, so
 * the covariance of (reading − resting) with dv/dt points along f. Lateral forces and grade changes are not
 * correlated with dv/dt over many windows and average out. Until the axis is confident, no grade is reported.
 *
 * Pure: the recorder feeds speed samples and accelerometer windows; the learned axis is exposed for persistence.
 */
class RoadGradeEstimator(restoredForward: Vec3? = null) {

    private class SpeedAt(val tNs: Long, val mps: Float)

    private val speeds = ArrayDeque<SpeedAt>()
    private var resting: Vec3? = null
    private var cov = Vec3(0f, 0f, 0f)
    private var excitation = 0f

    /** The learned forward axis (unit vector, sensor frame), or null while not yet confident. */
    var forward: Vec3? = restoredForward?.unit()
        private set

    fun onSpeed(tNs: Long, metersPerSecond: Float) {
        if (speeds.isNotEmpty() && tNs <= speeds.last().tNs) speeds.clear()
        speeds.addLast(SpeedAt(tNs, abs(metersPerSecond)))
        while (tNs - speeds.first().tNs > HISTORY_NS) speeds.removeFirst()
    }

    /** Learns from [w] and returns the grade in percent over it, or null (no axis yet, or no speed around it). */
    fun onWindow(w: MotionWindow): Float? {
        if (w.count < MIN_SAMPLES) return null
        val accel = longitudinalAccel(w) ?: return null
        learn(w.mean, accel)
        val f = forward ?: return null
        val sin = ((w.mean dot f) - accel) / G
        if (abs(sin) > MAX_SIN) return null // a jolt, not a road
        return tan(asin(sin)) * 100f
    }

    private fun learn(mean: Vec3, accel: Float) {
        val rest = resting
        if (abs(accel) < STEADY_ACCEL) {
            resting = rest?.let { it + (mean - it) * RESTING_ALPHA } ?: mean
            return
        }
        if (rest == null || abs(accel) < LEARN_ACCEL) return
        cov += (mean - rest) * accel
        excitation += accel * accel
        if (excitation < CONFIDENT_EXCITATION) return
        // Consistent only if the reading moved by about dv/dt along one axis: |cov| / excitation ≈ 1.
        val gain = cov.length / excitation
        if (gain in MIN_GAIN..MAX_GAIN) forward = cov.unit()
    }

    /** dv/dt over the window from the speed samples nearest its ends (m/s²), or null if they are too far away. */
    private fun longitudinalAccel(w: MotionWindow): Float? {
        val a = nearest(w.startNs) ?: return null
        val b = nearest(w.endNs) ?: return null
        if (b.tNs - a.tNs < MIN_SPAN_NS) return null
        return (b.mps - a.mps) * 1e9f / (b.tNs - a.tNs)
    }

    private fun nearest(tNs: Long) = speeds.minByOrNull { abs(it.tNs - tNs) }?.takeIf { abs(it.tNs - tNs) <= MAX_OFFSET_NS }

    private companion object {
        const val G = 9.80665f
        const val HISTORY_NS = 3_000_000_000L
        const val MAX_OFFSET_NS = 150_000_000L
        const val MIN_SPAN_NS = 500_000_000L
        const val MIN_SAMPLES = 10

        /** Below this |dv/dt| (m/s²) the window updates the resting reading; above LEARN_ACCEL it teaches the axis. */
        const val STEADY_ACCEL = 0.2f
        const val LEARN_ACCEL = 0.8f
        const val RESTING_ALPHA = 0.1f

        /** Σ (dv/dt)² before the axis counts: ≈ 20 one-second windows of brisk (1 m/s²) speed change. */
        const val CONFIDENT_EXCITATION = 20f
        const val MIN_GAIN = 0.7f
        const val MAX_GAIN = 1.3f

        /** |sin θ| above this (≈ 35 % grade) is a bump or an impact, not a road. */
        const val MAX_SIN = 0.33f
    }
}
