package com.americankimchilabs.whitebox.server

import java.io.File
import java.time.Instant

/** A severe event went silent: the owner's registered emergency contacts get a link to the sealed record. */
data class EmergencyNotice(val stream: StreamId, val attestation: String, val reason: String, val atMs: Long)

/** Delivery channel for emergency notices (SMS, 알림톡 or push in a deployment). */
interface ContactNotifier {
    fun notify(notice: EmergencyNotice)
}

/**
 * Stand-in channel: writes each notice to a log file and the console instead of sending it, so the whole dead-man
 * path can be demonstrated without a messaging contract.
 */
class LoggingNotifier(dir: File) : ContactNotifier {
    private val file = File(dir.apply { mkdirs() }, "notices.log")

    @Synchronized
    override fun notify(notice: EmergencyNotice) {
        val line = "${Instant.ofEpochMilli(notice.atMs)} ${notice.stream} ${notice.reason} — would send the sealed record's " +
            "viewer link to the owner's emergency contacts | ${notice.attestation}"
        file.appendText(line + "\n")
        println("NOTICE $line")
    }
}
