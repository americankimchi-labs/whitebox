package com.americankimchilabs.whitebox.platform

import com.americankimchilabs.whitebox.core.SpeakResult
import com.americankimchilabs.whitebox.core.SpeechOutput

/** The car's own voice first; the platform voice only when the car's is unavailable for a line. */
class FallbackSpeechOutput(private val primary: SpeechOutput, private val fallback: SpeechOutput) : SpeechOutput {
    override suspend fun speak(text: String): SpeakResult =
        primary.speak(text).takeIf { it != SpeakResult.UNAVAILABLE } ?: fallback.speak(text)

    override fun stop() {
        primary.stop()
        fallback.stop()
    }
}
