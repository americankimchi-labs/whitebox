package com.americankimchilabs.whitebox.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The car's short summary: the most telling readings, in the order the full list has them. */
class EvidenceReadingsTest {
    private fun ts(ms: Long) = Timestamp(ms * 1_000_000, 1_700_000_000_000 + ms)
    private fun s(signal: VehicleSignal, ms: Long) = ChainEntry.Signal(SignalSample(signal, ts(ms)))

    @Test
    fun aLaneChangeContactLeadsWithSpeedBrakeAndSignal() {
        val entries = buildList {
            add(s(VehicleSignal.Gear(GearPosition.DRIVE), 0))
            add(s(VehicleSignal.Light(Lamp.LOW_BEAM, false), 10))
            add(s(VehicleSignal.SeatBelt("ROW_1_LEFT", true), 20))
            for (i in 0..120) add(s(VehicleSignal.Speed((if (i < 110) 60f else 60f - (i - 110) * 3f) / 3.6f), 1_000L + i * 100))
            add(s(VehicleSignal.TurnSignal(TurnSignalState.LEFT), 8_000))
            add(s(VehicleSignal.BrakePedal(true), 10_800))
        }
        val events = entries.mapIndexed { i, e -> DriveEvent(i.toLong(), e) }
        val cause = events.last { (it.entry as? ChainEntry.Signal)?.sample?.signal is VehicleSignal.Speed }.seq
        val all = EvidenceReadings.of(events, cause)
        val top = EvidenceReadings.top(all)
        assertEquals(3, top.size)
        assertTrue(top[0].text, top[0].text.startsWith("감지 10초 전에는 시속 60 km였고"))
        assertTrue(top[1].text, top[1].text.startsWith("브레이크는 감지"))
        assertTrue(top[2].text, top[2].text.startsWith("방향지시등(왼쪽)은 감지 5.0초 전부터"))
        assertTrue(all.any { it.text == "전조등은 꺼져 있었습니다." && it.note == "낮이었을 가능성이 높습니다." })
    }
}
