package com.americankimchilabs.whitebox.domain

/**
 * Every recorded instant carries two clocks:
 * - [elapsedNanos]: monotonic since boot. Used for ordering, gaps and rates; never jumps.
 * - [wallMillis]: wall-clock epoch time. Used for evidence and dashcam alignment; can jump (NTP/GPS correction).
 */
data class Timestamp(
    val elapsedNanos: Long,
    val wallMillis: Long,
)
