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
        // Lamps off says nothing about the hour (a driver may forget them at night): no daylight guess.
        assertTrue(all.any { it.text == "전조등은 꺼져 있었습니다." && it.note == null })
    }

    /** A quiet drive, a signalled lane change, then "save this" 18 s later: nothing was detected, so nothing says 감지. */
    private fun voiceSaved(brakeHeld: Boolean = false, signalStillOn: Boolean = false) = buildList {
        add(s(VehicleSignal.Gear(GearPosition.DRIVE), 0))
        add(s(VehicleSignal.BrakePedal(false), 0))
        add(s(VehicleSignal.TurnSignal(TurnSignalState.OFF), 0))
        for (sec in 0..150) add(s(VehicleSignal.Speed(50f / 3.6f), sec * 1_000L))
        add(s(VehicleSignal.TurnSignal(TurnSignalState.LEFT), 102_000))
        for (i in 0..9) add(s(VehicleSignal.SteeringAngle(-2f * (i + 1)), 104_000L + i * 100))
        for (i in 0..9) add(s(VehicleSignal.SteeringAngle(-20f + 2f * (i + 1)), 105_000L + i * 100))
        if (!signalStillOn) add(s(VehicleSignal.TurnSignal(TurnSignalState.OFF), 107_100))
        if (brakeHeld) add(s(VehicleSignal.BrakePedal(true), 118_000))
        add(ChainEntry.Audio(AudioEvent.Bookmark(BookmarkSource.ASSISTANT), ts(120_200)))
    }.sortedBy { it.at.elapsedNanos }.mapIndexed { i, e -> DriveEvent(i.toLong(), e) }

    @Test
    fun aRecordSavedByVoiceIsReadOverTheWholeStretchBeforeTheRequest() {
        val events = voiceSaved()
        val cause = events.first { it.entry is ChainEntry.Audio }.seq
        val all = EvidenceReadings.of(events, cause, request = true).map { it.text }
        assertEquals("보관을 요청하기 2분 전부터의 기록입니다.", all[0])
        assertEquals("이 구간 내내 시속 약 50 km로 달렸습니다.", all[1])
        assertTrue(all.toString(), "이 구간에서 브레이크를 밟은 기록이 없습니다." in all)
        assertTrue(all.toString(), "핸들은 요청 15.3초 전에 가장 크게 돌렸습니다 (조향각 -20°)." in all)
        assertTrue(all.toString(), "방향지시등(왼쪽)을 요청 18.2초 전에 켜서 5.1초 동안 켜 두었습니다." in all)
        assertTrue(all.toString(), all.none { "감지" in it })
        // The car's three: the signal and the wheel say most about what the request is about.
        val top = EvidenceReadings.top(EvidenceReadings.of(events, cause, request = true)).map { it.text }
        assertTrue(top.toString(), top.any { it.startsWith("방향지시등(왼쪽)을 요청") } && top.any { it.startsWith("핸들은 요청") })
        // The same records read as a detection keep the detection's words: the rule set is chosen by the record.
        assertTrue(EvidenceReadings.of(events, cause).any { it.text == "감지 순간 방향지시등은 꺼져 있었습니다." })
    }

    @Test
    fun aRequestWithThePedalDownAndTheSignalOnSaysSo() {
        val events = voiceSaved(brakeHeld = true, signalStillOn = true)
        val cause = events.first { it.entry is ChainEntry.Audio }.seq
        val all = EvidenceReadings.of(events, cause, request = true).map { it.text }
        assertTrue(all.toString(), "이 구간에서 브레이크를 1번 밟았고, 요청한 순간에도 밟고 있었습니다 (요청 2.2초 전부터)." in all)
        assertTrue(all.toString(), "방향지시등(왼쪽)은 요청 18.2초 전부터 켜져 있었습니다." in all)
    }

    @Test
    fun aWarningLampThatCameOnBeforeTheMomentIsReadWithWhatItMeans() {
        val entries = buildList {
            add(s(VehicleSignal.Telltale("ESC", TelltaleState.OFF), 0))
            add(s(VehicleSignal.Telltale("ABS", TelltaleState.OFF), 0))
            for (i in 0..120) add(s(VehicleSignal.Speed(60f / 3.6f), 1_000L + i * 100))
            add(s(VehicleSignal.Telltale("ABS", TelltaleState.ON), 5_000))
            add(s(VehicleSignal.Telltale("ABS", TelltaleState.OFF), 6_000))
            add(s(VehicleSignal.Telltale("ESC", TelltaleState.BLINK), 11_500))
        }.sortedBy { it.at.elapsedNanos }
        val events = entries.mapIndexed { i, e -> DriveEvent(i.toLong(), e) }
        val cause = events.last { (it.entry as? ChainEntry.Signal)?.sample?.signal is VehicleSignal.Speed }.seq
        val all = EvidenceReadings.of(events, cause)
        val lamp = all.single { "경고등" in it.text }
        assertEquals("ESC 경고등이 감지 1.5초 전부터 깜빡이고 있었습니다.", lamp.text)
        assertEquals("ABS·ESC 경고등은 제동이나 자세 제어 장치가 개입했거나 이상이 있을 때 켜집니다.", lamp.note)
        assertTrue(EvidenceReadings.top(all).any { it === lamp })
    }
}
