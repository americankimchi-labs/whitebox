package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.data.MirrorProtocol
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/** Text messages (one-time codes, invitations, emergency notices). A deployment sends SMS or 알림톡. */
interface SmsSender {
    fun send(phone: String, text: String)
}

/**
 * Stand-in sender: nothing is sent; each message is printed and kept so the dashboard can show it, labelled as a
 * demonstration. The numbers shown are masked.
 */
class DemoSmsSender : SmsSender {
    data class Message(val atMs: Long, val to: String, val text: String)

    private val sent = ArrayDeque<Message>()

    @Synchronized
    override fun send(phone: String, text: String) {
        sent.addLast(Message(System.currentTimeMillis(), maskPhone(phone), text))
        while (sent.size > 30) sent.removeFirst()
        println("SMS to ${maskPhone(phone)}: $text")
    }

    @Synchronized
    fun recent(): List<Message> = sent.toList()
}

/** Korean mobile numbers to one form: digits only, leading 0 (010…). */
fun normalizePhone(raw: String): String? {
    val digits = raw.filter { it.isDigit() }.let { if (it.startsWith("82")) "0" + it.drop(2) else it }
    return digits.takeIf { it.length in 10..11 && it.startsWith("01") }
}

fun maskPhone(phone: String): String =
    if (phone.length >= 10) "${phone.take(3)}-****-${phone.takeLast(4)}" else "****"

/**
 * Who may see what. Identity is a phone number proved by a one-time code, never an account or password. The car's
 * root keys reach people only wrapped to keys their own phone browsers generated: the server keeps the wraps and
 * cannot open them.
 *
 * - Owner: claims the car from its QR. The same number on a new phone simply re-connects. A different number asks
 *   the current owner, who can decline; without an answer the car moves after [TRANSFER_WAIT_MS] (a sold car whose
 *   seller ignores texts). From the request on, the car records under a new key epoch that nobody holds yet: it
 *   goes to the new owner if the car moves, back to the owner if they decline, so the wait loses nothing.
 * - Emergency contacts: a name and number the owner enters, like the contact line of a hospital form. Nobody texts
 *   them, not when added and not after a crash. They can come anytime, prove the number with a code, and open the
 *   owner's severe crash capsules (whose minute keys the car hands the server); never everyday driving or location,
 *   which stay readable by the owner's phone alone. One person can be the contact of several owners.
 * - A new phone of the owner: the same number scans the car's QR again, and the car wraps every key for it.
 *
 * Kept on the local disk as small line records (phone numbers encrypted at rest and looked up by a keyed hash;
 * sessions only as hashes of their tokens, so a restart or a deployment signs nobody out); one-time codes and claim
 * codes live in memory.
 */
/** The identity log's name in the store. */
private const val LOG = "identity"

class Identity(private val store: Durable, private val key: ServerKey, private val sms: SmsSender, private val clock: () -> Long = System::currentTimeMillis) {
    /** On the local disk: `<dir>/identity.log`. */
    constructor(dir: File, key: ServerKey, sms: SmsSender, clock: () -> Long = System::currentTimeMillis) : this(FileDurable(dir), key, sms, clock)


    data class Owner(val id: String, val phoneHash: String, val phoneEnc: String, var ecdhKey: String)
    data class Contact(
        val id: String,
        val ownerId: String,
        val nameEnc: String,
        val phoneHash: String,
        val phoneEnc: String,
        val addedMs: Long,
        /** Set when removed: by the owner, or by the contact leaving ([leftByContact]). */
        var removedMs: Long? = null,
        var leftByContact: Boolean = false,
    )
    data class Transfer(
        val id: String,
        val deviceId: String,
        val fromOwnerId: String,
        val toOwnerId: String,
        val phoneKey: String,
        val mac: String,
        val masked: String,
        val createdMs: Long,
        var state: String = PENDING,
    ) {
        val autoAtMs get() = createdMs + TRANSFER_WAIT_MS
    }
    data class Wrap(val epoch: Int, val sealed: String)
    data class Session(val role: String, val id: String, val expiresMs: Long)
    private data class Otp(val code: String, val expiresMs: Long, var attempts: Int = 0)
    private data class ClaimCode(val stream: StreamId, val expiresMs: Long)

    private val random = SecureRandom()
    private val owners = HashMap<String, Owner>()
    private val deviceOwner = HashMap<String, String>()
    private val wraps = HashMap<Pair<String, String>, MutableMap<Int, String>>() // (device, recipient) → epoch → sealed key
    private val contacts = LinkedHashMap<String, Contact>()
    private val transfers = LinkedHashMap<String, Transfer>()
    private val pendingClaims = HashMap<String, String>() // device → CLAIMED frame not yet answered by the car
    private val transferFrames = HashMap<String, Pair<String, String>>() // device → (transfer, CLAIMPENDING/CLAIMDECLINED) until the car acks
    private val handoverCommands = HashMap<String, Pair<String, String>>() // device → (command, HANDOVERCMD) until the car acks
    private val handoverStatus = HashMap<String, String>() // device → the car's last HANDOVER status frame
    private val sessions = HashMap<String, Session>()
    private val otps = HashMap<String, Otp>()
    private val claimCodes = HashMap<String, ClaimCode>()
    private val lastOtpMs = HashMap<String, Long>()
    private val otpByClient = HashMap<String, ArrayDeque<Long>>()
    private val otpAll = ArrayDeque<Long>()

    init {
        store.lines(LOG).forEach(::apply)
    }

    /** Replays one stored line; every change is appended as one, so the file is the full state. */
    private fun apply(line: String) {
        val f = line.split("|")
        when (f[0]) {
            "O" -> owners[f[1]] = Owner(f[1], f[2], f[3], f[4])
            "K" -> owners[f[1]]?.ecdhKey = f[2]
            "D" -> deviceOwner[f[1]] = f[2]
            "W" -> wraps.getOrPut(f[1] to f[2]) { sortedMapOf() }[f[3].toInt()] = f[4]
            "CN" -> contacts[f[1]] = Contact(f[1], f[2], f[3], f[4], f[5], f[6].toLong())
            "CX" -> contacts[f[1]]?.let { it.removedMs = f[2].toLong(); it.leftByContact = f[3] == "1" }
            "P" -> pendingClaims[f[1]] = line.substringAfter("P|${f[1]}|")
            "PD" -> pendingClaims.remove(f[1])
            "PF" -> transferFrames[f[1]] = f[2] to line.substringAfter("PF|${f[1]}|${f[2]}|")
            "PFD" -> if (transferFrames[f[1]]?.first == f[2]) transferFrames.remove(f[1])
            "HC" -> handoverCommands[f[1]] = f[2] to line.substringAfter("HC|${f[1]}|${f[2]}|")
            "HCD" -> if (handoverCommands[f[1]]?.first == f[2]) handoverCommands.remove(f[1])
            "HS" -> handoverStatus[f[1]] = line.substringAfter("HS|${f[1]}|")
            "T" -> transfers[f[1]] = Transfer(f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8].toLong())
            "TS" -> transfers[f[1]]?.state = f[2]
            "S" -> sessions[f[1]] = Session(f[2], f[3], f[4].toLong())
        }
    }

    @Synchronized
    private fun record(vararg fields: String) {
        val line = fields.joinToString("|")
        store.append(LOG, line)
        apply(line)
    }

    private fun token(bytes: Int = 24) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    /** Keyed with the server's secret: without it, the stored hashes cannot be matched against all mobile numbers. */
    private fun phoneHash(phone: String) = key.keyedHash("phone", phone)

    // ---- one-time codes ----

    /**
     * Sends a 6-digit code to [phone]. Null when sent, else why not (in words for the person): one per number per
     * 30 s, at most [OTP_PER_CLIENT] per device in 10 min, and [OTP_GLOBAL_PER_MIN] in all per minute, so nobody can
     * use us to flood numbers with texts.
     */
    @Synchronized
    fun sendOtp(phone: String, purpose: String, client: String): String? {
        val now = clock()
        if ((lastOtpMs[phone] ?: 0) > now - 30_000) return "인증번호를 방금 보냈습니다. 30초 뒤에 다시 요청해 주세요."
        val mine = otpByClient.getOrPut(client) { ArrayDeque() }
        while (mine.isNotEmpty() && mine.first() < now - 10 * 60_000) mine.removeFirst()
        if (mine.size >= OTP_PER_CLIENT) return "요청이 너무 많습니다. 10분 뒤에 다시 시도해 주세요."
        while (otpAll.isNotEmpty() && otpAll.first() < now - 60_000) otpAll.removeFirst()
        if (otpAll.size >= OTP_GLOBAL_PER_MIN) return "지금 요청이 많습니다. 잠시 후 다시 시도해 주세요."
        mine.addLast(now)
        otpAll.addLast(now)
        val code = "%06d".format(random.nextInt(1_000_000))
        otps[phone] = Otp(code, now + 5 * 60_000)
        lastOtpMs[phone] = now
        sms.send(phone, "[White Box] ${purposeText(purpose)} 인증번호 [$code] · 5분 안에 입력해 주세요.")
        return null
    }

    private fun purposeText(p: String) = when (p) {
        "claim" -> "차 연결"
        else -> "본인 확인"
    }

    /** One use; five wrong tries void the code. */
    @Synchronized
    fun checkOtp(phone: String, code: String): Boolean {
        val o = otps[phone] ?: return false
        if (clock() > o.expiresMs || o.attempts >= 5) {
            otps.remove(phone)
            return false
        }
        if (o.code != code) {
            o.attempts++
            return false
        }
        otps.remove(phone)
        return true
    }

    // ---- sessions (an HttpOnly cookie per role on the phone) ----

    @Synchronized
    fun newSession(role: String, id: String): String {
        val t = token()
        record("S", tokenHash(t), role, id, (clock() + 30L * 24 * 3600_000).toString())
        return t
    }

    @Synchronized
    fun session(token: String?): Session? = token?.let { sessions[tokenHash(it)] }?.takeIf { it.expiresMs > clock() }

    private fun tokenHash(t: String) = key.keyedHash("session", t)

    /** Signing in again with the number and a code (the phone's key stays in its browser). Null if not known. */
    @Synchronized
    fun login(role: String, phone: String, otp: String): String? {
        val hash = phoneHash(phone)
        val known = when (role) {
            OWNER -> owners.values.firstOrNull { it.phoneHash == hash }?.id
            CONTACT -> hash.takeIf { contactsByPhoneHash(hash).isNotEmpty() }
            else -> null
        } ?: return null
        if (!checkOtp(phone, otp)) return null
        return newSession(role, known)
    }

    // ---- claiming a car ----

    /** The car asked to be claimed: a short code the phone page sends back (the QR's secret never reaches us). */
    @Synchronized
    fun claimCode(stream: StreamId): String {
        claimCodes.entries.removeIf { it.value.expiresMs < clock() || it.value.stream == stream }
        val code = (1..8).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random.nextInt(32)] }.joinToString("")
        claimCodes[code] = ClaimCode(stream, clock() + 10 * 60_000)
        return code
    }

    sealed interface ClaimResult {
        /** The car belongs to this number now (or already did): it will wrap its keys to the phone. */
        data class Claimed(val ownerId: String, val stream: StreamId, val session: String) : ClaimResult

        /** The car has another owner, who is asked; it moves at [Transfer.autoAtMs] unless they decline. */
        data class Pending(val ownerId: String, val transfer: Transfer, val session: String) : ClaimResult
        data class Refused(val reason: String) : ClaimResult
    }

    /** The phone proved the number by its code; the car will check [mac] (made with the QR secret) before wrapping. */
    @Synchronized
    fun claim(code: String, phone: String, otp: String, ecdhKey: String, mac: String): ClaimResult {
        val c = claimCodes[code]?.takeIf { it.expiresMs > clock() } ?: return ClaimResult.Refused("차 화면의 QR이 만료되었습니다. 차에서 휴대폰 연결을 다시 눌러 주세요.")
        if (!checkOtp(phone, otp)) return ClaimResult.Refused("인증번호가 맞지 않습니다.")
        claimCodes.remove(code)
        val hash = phoneHash(phone)
        val owner = owners.values.firstOrNull { it.phoneHash == hash }
        val id = owner?.id ?: token(12)
        if (owner == null) record("O", id, hash, key.encryptAtRest(phone, "phone|$id"), ecdhKey) else record("K", id, ecdhKey)
        val device = c.stream.deviceId
        val current = deviceOwner[device]
        if (current == null || current == id) {
            record("D", device, id)
            return ClaimResult.Claimed(id, c.stream, newSession(OWNER, id))
        }
        transfers.values.filter { it.deviceId == device && it.state == PENDING }.forEach { record("TS", it.id, SUPERSEDED) }
        val t = token(12)
        record("T", t, device, current, id, ecdhKey, mac, maskPhone(phone), clock().toString())
        // The car checks the MAC and starts recording under a key nobody holds until this is decided.
        record("PF", device, t, MirrorProtocol.encode(MirrorProtocol.Frame.ClaimPending(t, ecdhKey, mac, maskPhone(phone))))
        owners[current]?.let { o ->
            sms.send(ownerPhone(o), "[White Box] 다른 휴대폰(${maskPhone(phone)})에서 회원님 차를 연결하려고 합니다. 차를 팔거나 넘기셨다면 승인하고, 아니라면 거절해 주세요. 3일 안에 거절하지 않으면 새 휴대폰으로 넘어갑니다: /me")
        }
        return ClaimResult.Pending(id, transfers.getValue(t), newSession(OWNER, id))
    }

    /** Binds the car to the transfer's new owner; the caller tells the car (CLAIMED) so it starts a new key epoch. */
    @Synchronized
    private fun applyTransfer(t: Transfer, how: String) {
        record("D", t.deviceId, t.toOwnerId)
        record("TS", t.id, how)
        transferFrames[t.deviceId]?.let { record("PFD", t.deviceId, it.first) } // the CLAIMED frame supersedes it
        owners[t.fromOwnerId]?.let { sms.send(ownerPhone(it), "[White Box] 차 연결이 ${t.masked} 휴대폰으로 넘어갔습니다. 요청 전까지의 기록은 계속 회원님만 볼 수 있습니다.") }
        owners[t.toOwnerId]?.let { sms.send(ownerPhone(it), "[White Box] 차가 연결되었습니다. 요청하신 때부터의 기록을 볼 수 있습니다: /me") }
    }

    /** The current owner decided; returns the transfer if it now applies. */
    @Synchronized
    fun answerTransfer(ownerId: String, transferId: String, approve: Boolean): Transfer? {
        val t = transfers[transferId]?.takeIf { it.fromOwnerId == ownerId && it.state == PENDING && deviceOwner[it.deviceId] == ownerId } ?: return null
        if (approve) {
            applyTransfer(t, APPROVED)
            return t
        }
        record("TS", t.id, DECLINED)
        record("PF", t.deviceId, t.id, MirrorProtocol.encode(MirrorProtocol.Frame.ClaimDeclined(t.id)))
        owners[t.toOwnerId]?.let { sms.send(ownerPhone(it), "[White Box] 차주가 연결 요청을 거절했습니다.") }
        return null
    }

    /** Transfers nobody declined in time, applied now. */
    @Synchronized
    fun dueTransfers(): List<Transfer> = transfers.values
        .filter { it.state == PENDING && clock() >= it.autoAtMs && deviceOwner[it.deviceId] == it.fromOwnerId }
        .onEach { applyTransfer(it, AUTO_APPROVED) }

    @Synchronized
    fun transfersFrom(ownerId: String): List<Transfer> = transfers.values.filter { it.fromOwnerId == ownerId && it.state == PENDING }

    @Synchronized
    fun transfersTo(ownerId: String): List<Transfer> = transfers.values.filter { it.toOwnerId == ownerId && it.state in setOf(PENDING, DECLINED) }

    /** What to tell the car about its claim until it answers with the wraps (it may be offline). */
    @Synchronized
    fun setPendingClaim(deviceId: String, frame: String) = record("P", deviceId, frame)

    @Synchronized
    fun pendingClaim(deviceId: String): String? = pendingClaims[deviceId]

    // ---- handover mode (대리 운전) ----

    /**
     * The owner turned handover mode on or off for [deviceId] on the phone. The newest command replaces one the car has
     * not heard yet; it is sent now if the car is connected, else when it connects, until acknowledged.
     */
    @Synchronized
    fun handoverCommand(ownerId: String, deviceId: String, arm: Boolean): String? {
        if (deviceOwner[deviceId] != ownerId) return null
        val id = token(9)
        record("HC", deviceId, id, MirrorProtocol.encode(MirrorProtocol.Frame.HandoverCommand(id, arm, if (arm) clock() else 0)))
        return handoverCommands.getValue(deviceId).second
    }

    @Synchronized
    fun handoverFrame(deviceId: String): String? = handoverCommands[deviceId]?.second

    @Synchronized
    fun handoverAcked(deviceId: String, commandId: String) {
        if (handoverCommands[deviceId]?.first == commandId) record("HCD", deviceId, commandId)
    }

    /** Whether a command from the phone is still on its way to the car (and which). */
    @Synchronized
    fun handoverPending(deviceId: String): Boolean? = handoverCommands[deviceId]?.second?.let { (MirrorProtocol.decode(it) as? MirrorProtocol.Frame.HandoverCommand)?.arm }

    /** The car's status; one that shows the pending command carried out settles it even if its acknowledgement is late. */
    @Synchronized
    fun setHandoverStatus(deviceId: String, frame: String) {
        if (handoverStatus[deviceId] != frame) record("HS", deviceId, frame)
        val status = MirrorProtocol.decode(frame) as? MirrorProtocol.Frame.HandoverStatus ?: return
        val (id, pending) = handoverCommands[deviceId] ?: return
        val cmd = MirrorProtocol.decode(pending) as? MirrorProtocol.Frame.HandoverCommand ?: return
        val done = if (cmd.arm) status.phase != "OFF" && status.requestedAtWall == cmd.requestedAtWall else status.phase == "OFF"
        if (done) record("HCD", deviceId, id)
    }

    @Synchronized
    fun handoverStatus(deviceId: String): MirrorProtocol.Frame.HandoverStatus? = handoverStatus[deviceId]?.let { MirrorProtocol.decode(it) as? MirrorProtocol.Frame.HandoverStatus }

    /** A takeover request, or its decline, the car has not acknowledged yet. */
    @Synchronized
    fun transferFrame(deviceId: String): String? = transferFrames[deviceId]?.second

    @Synchronized
    fun transferAcked(deviceId: String, transferId: String) {
        if (transferFrames[deviceId]?.first == transferId) record("PFD", deviceId, transferId)
    }

    @Synchronized
    fun ownerOf(deviceId: String): Owner? = deviceOwner[deviceId]?.let { owners[it] }

    @Synchronized
    fun owner(id: String): Owner? = owners[id]

    @Synchronized
    fun devicesOf(ownerId: String): List<String> = deviceOwner.filterValues { it == ownerId }.keys.sorted()

    fun ownerPhone(o: Owner): String = key.decryptAtRest(o.phoneEnc, "phone|${o.id}")

    // ---- wrapped keys ----

    /** A wrap from the car (to its owner) or from a phone (to a contact, or a recovered phone). */
    @Synchronized
    fun putWrap(deviceId: String, recipientId: String, epoch: Int, sealed: String) {
        if (wraps[deviceId to recipientId]?.get(epoch) == sealed) return
        record("W", deviceId, recipientId, epoch.toString(), sealed)
        if (deviceOwner[deviceId] == recipientId) pendingClaims[deviceId]?.let { record("PD", deviceId) }
    }

    @Synchronized
    fun wraps(deviceId: String, recipientId: String): List<Wrap> = wraps[deviceId to recipientId]?.map { Wrap(it.key, it.value) } ?: emptyList()

    /** Cars [recipientId] holds keys for: their own, and cars they owned before a sale (for those records only). */
    @Synchronized
    fun devicesHeldBy(recipientId: String): List<String> = wraps.keys.filter { it.second == recipientId }.map { it.first }.distinct().sorted()

    // ---- emergency contacts ----

    /** The owner adds a contact by name and number. Nobody is asked or texted: the number is all it takes. */
    @Synchronized
    fun addContact(ownerId: String, name: String, phone: String): Contact {
        val hash = phoneHash(phone)
        contactsOf(ownerId).firstOrNull { it.phoneHash == hash }?.let { return it } // added twice: one entry
        val id = token(12)
        record("CN", id, ownerId, key.encryptAtRest(name, "name|$id"), hash, key.encryptAtRest(phone, "phone|$id"), clock().toString())
        return contacts.getValue(id)
    }

    fun contactName(c: Contact) = key.decryptAtRest(c.nameEnc, "name|${c.id}")
    fun contactPhone(c: Contact) = key.decryptAtRest(c.phoneEnc, "phone|${c.id}")

    /** Ends a contact: the owner removed them, or they took themselves off ([byContact]); they can no longer open anything. */
    @Synchronized
    fun removeContact(contactId: String, byContact: Boolean): Boolean {
        val c = contacts[contactId]?.takeIf { it.removedMs == null } ?: return false
        record("CX", c.id, clock().toString(), if (byContact) "1" else "0")
        return true
    }

    @Synchronized
    fun contactsOf(ownerId: String): List<Contact> = contacts.values.filter { it.ownerId == ownerId && it.removedMs == null }

    /** Contacts who took themselves off this owner's list (shown once on the owner's page). */
    @Synchronized
    fun contactsWhoLeft(ownerId: String): List<Contact> = contacts.values.filter { it.ownerId == ownerId && it.leftByContact }

    /** Every person this number is an emergency contact for (one entry per owner). */
    @Synchronized
    fun contactsByPhoneHash(hash: String): List<Contact> = contacts.values.filter { it.phoneHash == hash && it.removedMs == null }

    @Synchronized
    fun isContactNumber(phone: String): Boolean = contactsByPhoneHash(phoneHash(phone)).isNotEmpty()

    @Synchronized
    fun contact(id: String): Contact? = contacts[id]

    companion object {
        const val OWNER = "owner"
        const val CONTACT = "contact"
        const val PENDING = "pending"
        const val APPROVED = "approved"
        const val AUTO_APPROVED = "auto"
        const val DECLINED = "declined"
        const val SUPERSEDED = "superseded"

        /** A takeover nobody declined goes through after this long. */
        const val TRANSFER_WAIT_MS = 3L * 24 * 3600_000

        /**
         * Per client address in 10 minutes. Generous on purpose: mobile carriers put many customers behind one shared
         * address, and a family on one home network shares one too; the per-number and overall limits stop flooding.
         */
        const val OTP_PER_CLIENT = 20
        const val OTP_GLOBAL_PER_MIN = 60
    }
}
