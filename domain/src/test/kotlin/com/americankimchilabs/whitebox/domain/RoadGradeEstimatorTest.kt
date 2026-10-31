package com.americankimchilabs.whitebox.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

/** A head unit mounted at an arbitrary angle, driven through speed changes and then over graded road. */
class RoadGradeEstimatorTest {

    /** Car frame: x forward, z up. The head unit is turned 90° about up and tilted 25° back: an odd but rigid mount. */
    private fun toSensor(car: Vec3): Vec3 {
        val yaw = Math.toRadians(90.0)
        val tilt = Math.toRadians(25.0)
        val a = Vec3(
            (car.x * cos(yaw) - car.y * sin(yaw)).toFloat(),
            (car.x * sin(yaw) + car.y * cos(yaw)).toFloat(),
            car.z,
        )
        return Vec3(a.x, (a.y * cos(tilt) - a.z * sin(tilt)).toFloat(), (a.y * sin(tilt) + a.z * cos(tilt)).toFloat())
    }

    private class Drive(val estimator: RoadGradeEstimator, val sensor: (Vec3) -> Vec3) {
        var tMs = 0L
        var mps = 15f
        val grades = mutableListOf<Float?>()

        /** One second at [accel] m/s² on a [gradePct] road: speed samples at 10 Hz, then the averaged window. */
        fun second(accel: Float, gradePct: Float) {
            val start = tMs
            for (i in 0..10) {
                if (i > 0) mps += accel / 10
                estimator.onSpeed((start + i * 100) * 1_000_000, mps)
            }
            tMs += 1_000
            val theta = atan(gradePct / 100f)
            val car = Vec3(accel + G * sin(theta), 0f, G * cos(theta)) // specific force in the car frame
            grades += estimator.onWindow(MotionWindow(start * 1_000_000, tMs * 1_000_000, sensor(car), 50))
        }
    }

    @Test
    fun learnsTheMountFromSpeedChangesThenReadsTheGrade() {
        val d = Drive(RoadGradeEstimator(), ::toSensor)
        repeat(5) { d.second(0f, 0f) } // resting reading
        repeat(8) {
            d.second(1.5f, 0f)
            d.second(1.5f, 0f)
            d.second(-1.5f, 0f)
            d.second(-1.5f, 0f)
        }
        assertNotNull(d.estimator.forward)
        d.second(0f, 5f)
        assertEquals(5f, d.grades.last()!!, 0.3f)
        d.second(0f, -6f) // drag braking downhill at steady speed
        assertEquals(-6f, d.grades.last()!!, 0.3f)
        d.second(-1f, 0f) // braking on the flat is not a slope
        assertEquals(0f, d.grades.last()!!, 0.3f)
    }

    @Test
    fun noGradeBeforeTheMountIsKnown() {
        val d = Drive(RoadGradeEstimator(), ::toSensor)
        repeat(5) { d.second(0f, 0f) }
        d.second(0f, 5f)
        assertNull(d.grades.last())
        assertNull(d.estimator.forward)
    }

    @Test
    fun aRestoredMountReadsAtOnce() {
        val learned = Drive(RoadGradeEstimator(), ::toSensor).apply {
            repeat(5) { second(0f, 0f) }
            repeat(8) { second(1.5f, 0f); second(1.5f, 0f); second(-1.5f, 0f); second(-1.5f, 0f) }
        }.estimator.forward!!
        val d = Drive(RoadGradeEstimator(restoredForward = learned), ::toSensor)
        d.second(0f, 4f)
        assertEquals(4f, d.grades.last()!!, 0.3f)
    }

    @Test
    fun aSensorThatDoesNotMoveWithTheCarNeverCalibrates() {
        // The emulator's accelerometer: a fixed pose that ignores the car's speed changes.
        val d = Drive(RoadGradeEstimator()) { Vec3(0f, 9.776f, 0.812f) }
        repeat(5) { d.second(0f, 0f) }
        repeat(20) { d.second(1.5f, 0f); d.second(-1.5f, 0f) }
        assertNull(d.estimator.forward)
        assertNull(d.grades.last())
    }

    private companion object {
        const val G = 9.80665f
    }
}
