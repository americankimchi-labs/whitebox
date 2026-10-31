package com.americankimchilabs.whitebox.platform

import com.americankimchilabs.whitebox.domain.MotionWindow
import com.americankimchilabs.whitebox.domain.RoadGradeEstimator
import com.americankimchilabs.whitebox.domain.Vec3
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [RoadGradeEstimator] for the live recorder: fed speed by the record loop and accelerometer windows by the motion
 * source (two threads, hence the lock), with the learned mounting kept in one small file so a restart reads the
 * grade at once. Grades are reported only while the car moves, rounded to 0.1 %.
 */
class RoadGradeTracker(private val file: File) {

    private val estimator = RoadGradeEstimator(read())
    private var saved = estimator.forward
    private var lastMps = 0f

    @Synchronized
    fun onSpeed(tNs: Long, metersPerSecond: Float) {
        lastMps = abs(metersPerSecond)
        estimator.onSpeed(tNs, metersPerSecond)
    }

    @Synchronized
    fun onWindow(w: MotionWindow): Float? {
        val grade = estimator.onWindow(w)
        estimator.forward?.let { f -> if (saved.let { it == null || (it dot f) < SAME_AXIS_COS }) write(f) }
        if (lastMps < MOVING_MPS) return null
        return grade?.let { (it * 10).roundToInt() / 10f }
    }

    private fun read(): Vec3? = runCatching {
        val v = file.readText().trim().split(",").map { it.toFloat() }
        Vec3(v[0], v[1], v[2])
    }.getOrNull()

    /** Temp file, fsync, rename: a power cut leaves the old or the new axis, never a torn one. */
    private fun write(f: Vec3) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            FileOutputStream(tmp).use {
                it.write("${f.x},${f.y},${f.z}".toByteArray())
                it.fd.sync()
            }
            check(tmp.renameTo(file))
            saved = f
        }
    }

    private companion object {
        /** Rewrite the file only when the axis moved by more than ≈ 2°. */
        const val SAME_AXIS_COS = 0.9994f
        const val MOVING_MPS = 0.6f
    }
}
