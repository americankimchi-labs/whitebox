package com.americankimchilabs.whitebox.domain

import com.americankimchilabs.whitebox.domain.KeepQuestion.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VoiceTest {

    @Test
    fun bookmarkPhrasesMatchOnStemsIncludingGarbledRecognition() {
        for (t in listOf("방금 저장해", "기록 저장해", "방금 상황 저장해 줘", "화이트박스 보관해", "하이트박스 저장")) assertTrue(t, VoicePhrases.isBookmark(t))
        for (t in listOf("방금 저 생각해", "오늘 저녁 뭐 먹을까", "저장", "방금 뭐라고 했어")) assertFalse(t, VoicePhrases.isBookmark(t))
    }

    @Test
    fun keepAnswersReleaseOnlyOnAClearNo() {
        for (t in listOf("아니", "아니요", "아뇨", "보관 취소", "삭제해", "지워 줘", "저장하지 마", "됐어", "필요 없어")) {
            assertEquals(t, false, VoicePhrases.keepAnswer(t))
        }
        for (t in listOf("응", "네", "예.", "보관해", "저장해 줘", "그래", "좋아", "아니, 보관해")) assertEquals(t, true, VoicePhrases.keepAnswer(t))
        for (t in listOf("", "오늘 날씨 좋네요 그치", "어디 가", "음악 틀어")) assertNull(t, VoicePhrases.keepAnswer(t))
    }

    private val window = Tuning.KEEP_ANSWER_WINDOW_S * 1_000L

    @Test
    fun anAnswerInTheWindowIsRecordedAndConfirmed() {
        val q = KeepQuestion(0)
        assertEquals(listOf(Action.RequestFocus), q.start())
        assertEquals(listOf(Action.Speak), q.focusGranted(100))
        assertEquals(listOf(Action.Listen(2_000 + window)), q.spoke(complete = true, nowMs = 2_000))
        assertTrue(q.heard("오늘 저녁 뭐 먹지").isEmpty()) // not an answer: keep listening
        assertEquals(listOf(Action.StopListening, Action.Finish(false, true, VoiceLine.RELEASED)), q.heard("아니"))
        assertTrue(q.done)
    }

    @Test
    fun silenceForTheWholeWindowKeeps() {
        val q = KeepQuestion(0)
        q.start(); q.focusGranted(0); q.spoke(true, 2_000)
        assertTrue(q.tick(2_000 + window - 1).isEmpty())
        assertEquals(listOf(Action.StopListening, Action.Finish(true, false, VoiceLine.KEPT)), q.tick(2_000 + window))
    }

    @Test
    fun anInterruptedWindowStartsAgainInFullAfterTheInterruption() {
        val q = KeepQuestion(0)
        q.start(); q.focusGranted(0); q.spoke(true, 2_000)
        assertEquals(listOf(Action.StopListening), q.focusLost()) // a call comes in at 5 s
        assertTrue(q.heard("아니").isEmpty()) // not listening during the call
        assertTrue(q.tick(2_000 + window + 1).isEmpty()) // the window does not run out during the call
        assertEquals(listOf(Action.Listen(40_000 + window)), q.focusGranted(40_000)) // call ended at 40 s
        assertEquals(Action.Finish(true, true, VoiceLine.KEPT), q.heard("네").last())
    }

    @Test
    fun anInterruptedQuestionIsAskedAgain() {
        val q = KeepQuestion(0)
        q.start(); q.focusGranted(0)
        assertEquals(listOf(Action.StopSpeaking), q.focusLost()) // the assistant was called mid-question
        assertTrue(q.spoke(complete = false, nowMs = 600).isEmpty()) // the cut-off line is not a failure
        assertEquals(listOf(Action.Speak), q.focusGranted(9_000))
        assertEquals(listOf(Action.Listen(11_000 + window)), q.spoke(true, 11_000))
    }

    @Test
    fun aVoiceEngineThatKeepsFailingGivesUpAndKeeps() {
        val q = KeepQuestion(0)
        q.start()
        repeat(Tuning.KEEP_MAX_ASKS - 1) {
            assertEquals(listOf(Action.Speak), q.focusGranted(0))
            assertEquals(listOf(Action.RequestFocus), q.spoke(complete = false, nowMs = 0))
        }
        q.focusGranted(0)
        assertEquals(listOf(Action.Finish(true, false, null)), q.spoke(complete = false, nowMs = 0))
    }

    @Test
    fun audioThatNeverComesBackGivesUpSilently() {
        val q = KeepQuestion(1_000)
        q.start() // focus delayed: a long call is in progress
        assertTrue(q.tick(1_000 + Tuning.KEEP_QUESTION_MAX_S * 1_000L - 1).isEmpty())
        assertEquals(listOf(Action.Finish(true, false, null)), q.tick(1_000 + Tuning.KEEP_QUESTION_MAX_S * 1_000L))
        assertTrue(q.focusGranted(200_000).isEmpty())
    }

    @Test
    fun theChirpSweepsAboveHearingAndFadesInAndOut() {
        val rate = 48_000
        val s = Chirp.samples(rate)
        assertEquals(rate * Tuning.CHIRP_MS / 1_000, s.size)
        assertTrue(abs(s.first()) < 1e-3 && abs(s.last()) < 1e-3) // no click at either end
        assertTrue(s.maxOf { abs(it) } <= Tuning.CHIRP_AMPLITUDE + 1e-6)
        // Instantaneous frequency from zero crossings in the first and last fifth (inside the fades' tails).
        fun hz(from: Int, to: Int): Double {
            val crossings = (from + 1 until to).count { (s[it - 1] < 0) != (s[it] < 0) }
            return crossings / 2.0 / ((to - from).toDouble() / rate)
        }
        val n = s.size
        val start = hz(n / 10, n * 3 / 10)
        val end = hz(n * 7 / 10, n * 9 / 10)
        assertTrue("start $start", start >= Tuning.CHIRP_LOW_HZ - 300)
        assertTrue("end $end", end > start && end <= Tuning.CHIRP_HIGH_HZ + 300)
    }
}
