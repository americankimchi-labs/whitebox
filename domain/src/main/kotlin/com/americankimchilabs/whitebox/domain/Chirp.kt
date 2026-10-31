package com.americankimchilabs.whitebox.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The dashcam sync chirp: a linear sweep from [Tuning.CHIRP_LOW_HZ] to [Tuning.CHIRP_HIGH_HZ] over
 * [Tuning.CHIRP_MS], with raised-cosine fades. A sweep has a sharp autocorrelation peak, so cross-correlating a
 * dashcam's audio with this template finds the playback instant to well under a millisecond where it was recorded.
 * The viewer uses the same template, so it must be generated identically from these constants.
 */
object Chirp {
    fun samples(sampleRate: Int): FloatArray {
        val n = sampleRate * Tuning.CHIRP_MS / 1_000
        val fade = sampleRate * Tuning.CHIRP_FADE_MS / 1_000
        val f0 = Tuning.CHIRP_LOW_HZ.toDouble()
        val k = (Tuning.CHIRP_HIGH_HZ - f0) / (Tuning.CHIRP_MS / 1_000.0) // Hz per second
        return FloatArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val envelope = when {
                i < fade -> 0.5 * (1 - cos(PI * i / fade))
                i >= n - fade -> 0.5 * (1 - cos(PI * (n - 1 - i) / fade))
                else -> 1.0
            }
            (Tuning.CHIRP_AMPLITUDE * envelope * sin(2 * PI * (f0 * t + k * t * t / 2))).toFloat()
        }
    }
}
