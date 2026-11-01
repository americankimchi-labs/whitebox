package com.americankimchilabs.whitebox.domain

/**
 * What the car said and heard, and the dashcam sync chirp. Recorded so the evidence shows every voice command and
 * every spoken line with its time (spoken lines are also sync points a dashcam microphone may have captured).
 * The driver's words themselves are never recorded: only what they meant (a bookmark, an answer).
 */
sealed interface AudioEvent {
    /** The driver bookmarked the moment by voice. */
    data class Bookmark(val source: BookmarkSource) : AudioEvent

    /** A spoken line ended; [complete] is false when it was cut off (another app took the audio). */
    data class Spoke(val line: VoiceLine, val complete: Boolean) : AudioEvent

    /** The sync chirp; [playedAt] is the measured presentation time of its first frame, not the play call. */
    data class Beacon(val chirpId: Int, val playedAt: Timestamp) : AudioEvent
}

enum class BookmarkSource {
    /** The car assistant (Gleo) called our bookmark function; needs a network. */
    ASSISTANT,

    /** Our own on-device listener, which runs only while the car has no network. */
    OFFLINE_LISTENER,
}

/** Every line the car speaks. Kept short: a long synthetic voice is tiring. */
enum class VoiceLine {
    KEEP_QUESTION, KEPT, RELEASED, CRASH_GUIDANCE, BOOKMARKED,

    // Handover mode: to the hired driver, polite and short.
    HANDOVER_STARTED, HANDOVER_SET_DESTINATION, HANDOVER_SLOW_DOWN, HANDOVER_DRIVE_SAFELY, HANDOVER_CHECK_ROUTE,

    // The library was full and the oldest kept moment made room (said once parked).
    LIBRARY_FULL,
}

/**
 * Matching recognised Korean speech. On-device recognition garbles words (a synthetic "방금 저장해" came back as
 * "방금 저 생각해" in 2 of 5 runs), so matching is on stems, not exact text.
 */
object VoicePhrases {
    private val bookmarkAnchors = listOf("방금", "기록", "지금", "화이트박스", "하이트박스")
    private val bookmarkVerbs = listOf("저장", "보관")

    /** "방금 저장해", "기록 저장해", "화이트박스 보관해" … */
    fun isBookmark(text: String): Boolean {
        val t = normalize(text)
        return bookmarkAnchors.any { it in t } && bookmarkVerbs.any { it in t }
    }

    /** Release whatever else was said ("보관 취소", "저장하지 마"). */
    private val strongNegatives = listOf("취소", "삭제", "지워", "하지마", "하지말", "안해", "안할", "필요없")
    private val negatives = listOf("아니", "아뇨", "됐어", "싫어")
    private val positives = listOf("보관", "저장", "그래", "좋아", "부탁")
    private val shortYes = setOf("응", "어", "네", "예", "넵", "웅", "네네", "예예", "오케이")

    /**
     * The answer to "방금 상황 보관할까요?": false releases the capsule, true keeps it, null is not an answer (talk
     * about something else). "아니" with a keep word ("아니, 보관해") keeps: when in doubt, the evidence stays.
     */
    fun keepAnswer(text: String): Boolean? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        if (strongNegatives.any { it in t }) return false
        val no = negatives.any { it in t }
        val yes = positives.any { it in t } || t in shortYes
        return when {
            yes -> true
            no -> false
            else -> null
        }
    }

    private fun normalize(s: String) = s.filter { it.isLetterOrDigit() }
}

/**
 * The keep question as a pure state machine, so every interruption path is unit-tested. The driver gets
 * [Tuning.KEEP_ANSWER_WINDOW_S] after the question to answer. When a call, the car assistant or another app takes
 * the audio, the machine waits until it is finished: an interrupted question is asked again, an interrupted answer
 * window starts again in full. If the audio never comes back within [Tuning.KEEP_QUESTION_MAX_S], or the question
 * cannot be spoken after [Tuning.KEEP_MAX_ASKS] tries, it gives up and the capsule stays (silence keeps).
 */
class KeepQuestion(private val startMs: Long) {

    sealed interface Action {
        /** Ask for the audio (accepting a delayed grant, which arrives once whatever holds it is finished). */
        data object RequestFocus : Action
        data object Speak : Action
        data object StopSpeaking : Action
        data class Listen(val untilMs: Long) : Action
        data object StopListening : Action

        /**
         * Done. [answered] is false for silence or giving up; [confirm] is the line to speak before releasing the
         * audio (none when giving up: the moment has passed).
         */
        data class Finish(val keep: Boolean, val answered: Boolean, val confirm: VoiceLine?) : Action
    }

    private enum class Phase { WAIT_TO_ASK, ASKING, WAIT_TO_LISTEN, LISTENING, DONE }

    private var phase = Phase.WAIT_TO_ASK
    private var asks = 0
    private var untilMs = 0L

    val done get() = phase == Phase.DONE

    fun start(): List<Action> = listOf(Action.RequestFocus)

    fun focusGranted(nowMs: Long): List<Action> = when (phase) {
        Phase.WAIT_TO_ASK -> ask()
        Phase.WAIT_TO_LISTEN -> listen(nowMs)
        else -> emptyList()
    }

    fun focusLost(): List<Action> = when (phase) {
        Phase.ASKING -> {
            phase = Phase.WAIT_TO_ASK
            listOf(Action.StopSpeaking)
        }
        Phase.LISTENING -> {
            phase = Phase.WAIT_TO_LISTEN
            listOf(Action.StopListening)
        }
        else -> emptyList()
    }

    /** The question ended; incomplete without a focus loss means the voice engine failed: try again. */
    fun spoke(complete: Boolean, nowMs: Long): List<Action> {
        if (phase != Phase.ASKING) return emptyList()
        if (complete) return listen(nowMs)
        if (asks >= Tuning.KEEP_MAX_ASKS) return finish(keep = true, answered = false, confirm = null)
        phase = Phase.WAIT_TO_ASK
        return listOf(Action.RequestFocus)
    }

    fun heard(text: String): List<Action> {
        if (phase != Phase.LISTENING) return emptyList()
        val keep = VoicePhrases.keepAnswer(text) ?: return emptyList()
        return listOf(Action.StopListening) + finish(keep, answered = true, confirm = if (keep) VoiceLine.KEPT else VoiceLine.RELEASED)
    }

    fun tick(nowMs: Long): List<Action> = when {
        phase == Phase.LISTENING && nowMs >= untilMs ->
            listOf(Action.StopListening) + finish(keep = true, answered = false, confirm = VoiceLine.KEPT)
        (phase == Phase.WAIT_TO_ASK || phase == Phase.WAIT_TO_LISTEN) && nowMs - startMs >= Tuning.KEEP_QUESTION_MAX_S * 1_000L ->
            finish(keep = true, answered = false, confirm = null)
        else -> emptyList()
    }

    private fun ask(): List<Action> {
        phase = Phase.ASKING
        asks++
        return listOf(Action.Speak)
    }

    private fun listen(nowMs: Long): List<Action> {
        phase = Phase.LISTENING
        untilMs = nowMs + Tuning.KEEP_ANSWER_WINDOW_S * 1_000L
        return listOf(Action.Listen(untilMs))
    }

    private fun finish(keep: Boolean, answered: Boolean, confirm: VoiceLine?): List<Action> {
        phase = Phase.DONE
        return listOf(Action.Finish(keep, answered, confirm))
    }
}
