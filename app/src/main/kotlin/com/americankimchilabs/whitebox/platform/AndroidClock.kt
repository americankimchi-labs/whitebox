package com.americankimchilabs.whitebox.platform

import android.os.SystemClock
import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.domain.Timestamp

/** elapsedRealtimeNanos is the same clock as AAOS CarPropertyValue.timestamp, so latencies subtract directly. */
object AndroidClock : Clock {
    override fun now() = Timestamp(
        elapsedNanos = SystemClock.elapsedRealtimeNanos(),
        wallMillis = System.currentTimeMillis(),
    )
}
