package com.americankimchilabs.whitebox.data

/**
 * Notices that the device slept. A real car suspends at ignition off and freezes the app until it wakes: the clock
 * that counts sleep (elapsed realtime) jumps ahead of the one that does not (uptime). Fed both clocks every few
 * seconds, [check] returns how long the device slept since the last check, or null if it did not (for longer than
 * [minMillis]: a short doze is not worth a record).
 */
class SleepDetector(private val minMillis: Long = 10_000) {
    private var lastElapsed: Long? = null
    private var lastUptime = 0L

    fun check(elapsedMillis: Long, uptimeMillis: Long): Long? {
        val prev = lastElapsed
        val slept = if (prev == null) 0 else (elapsedMillis - prev) - (uptimeMillis - lastUptime)
        lastElapsed = elapsedMillis
        lastUptime = uptimeMillis
        return slept.takeIf { it >= minMillis }
    }
}
