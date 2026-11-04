package com.americankimchilabs.whitebox.ui

import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.GearPosition
import com.americankimchilabs.whitebox.domain.Lamp
import com.americankimchilabs.whitebox.domain.RecorderEvent
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.StartReason
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.VehicleSignal
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineRowsTest {
    private fun ts(ms: Long) = Timestamp(ms * 1_000_000, 1_700_000_000_000 + ms)
    private fun signal(s: VehicleSignal, ms: Long) = ChainEntry.Signal(SignalSample(s, ts(ms)))

    @Test
    fun theStartingStateIsFoldedIntoTheStartRowButLaterChangesShow() {
        val f = RowFilter()
        val shown = listOf(
            ChainEntry.Recorder(RecorderEvent.RecordingStarted(StartReason.BOOT, null, 0), ts(0)),
            signal(VehicleSignal.Door("ROW_1_LEFT", false), 100),
            signal(VehicleSignal.SeatBelt("ROW_1_LEFT", false), 150),
            signal(VehicleSignal.Light(Lamp.LOW_BEAM, false), 200),
            signal(VehicleSignal.Gear(GearPosition.PARK), 250), // the driving controls still show
            signal(VehicleSignal.SeatBelt("ROW_1_LEFT", true), 60_000), // buckling up later is news
            signal(VehicleSignal.SeatBelt("ROW_1_LEFT", true), 60_100), // a repeat is not
        ).filter(f::shows)
        assertEquals(3, shown.size)
        assertEquals(VehicleSignal.Gear(GearPosition.PARK), (shown[1] as ChainEntry.Signal).sample.signal)
        assertEquals(VehicleSignal.SeatBelt("ROW_1_LEFT", true), (shown[2] as ChainEntry.Signal).sample.signal)
    }

    @Test
    fun lengthsOfTimeReadAsPeopleSayThem() {
        assertEquals("0.8초", humanDuration(800))
        assertEquals("12분 3초", humanDuration(723_000))
        assertEquals("5분", humanDuration(300_000))
        assertEquals("1시간 42분", humanDuration(6_126_300))
        assertEquals("2시간", humanDuration(7_200_000))
        assertEquals("2일 3시간", humanDuration((51 * 3_600) * 1_000L))
    }
}
