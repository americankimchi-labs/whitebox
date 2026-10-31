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

/**
 * Emergency notices to the owner's active emergency contacts (and the owner), by text message with the link to
 * their page, where the record opens with their own key. Also kept in the server log via [log].
 */
class ContactsNotifier(private val identity: Identity, private val sms: SmsSender, private val log: ContactNotifier) : ContactNotifier {
    override fun notify(notice: EmergencyNotice) {
        log.notify(notice)
        val owner = identity.ownerOf(notice.stream.deviceId) ?: return
        val ownerPhone = identity.ownerPhone(owner)
        for (c in identity.contactsOf(owner.id).filter { it.state == Identity.ACTIVE }) {
            sms.send(identity.contactPhone(c), "[White Box] ${maskPhone(ownerPhone)} 님 차량에서 충돌 수준 사고가 감지된 뒤 연락이 끊겼습니다. 기록은 봉인되었습니다. 확인: /contact")
        }
    }
}
