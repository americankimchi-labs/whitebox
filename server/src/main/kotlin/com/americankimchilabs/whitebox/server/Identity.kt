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
 * - Emergency contacts: invited by the owner, consent with their own number, approved after comparing check codes.
 *   Either side can end it; the car then starts a new key epoch so the former contact cannot read what follows.
 *   One person can be the contact of several owners.
 * - Recovery: a new phone of the owner gets the keys re-wrapped by any one active contact.
 *
 * Kept on the local disk as small line records (phone numbers encrypted at rest and looked up by a keyed hash;
 * sessions only as hashes of their tokens, so a restart or a deployment signs nobody out); one-time codes and claim
 * codes live in memory.
 */
class Identity(dir: File, private val key: ServerKey, private val sms: SmsSender, private val clock: () -> Long = System::currentTimeMillis) {

    data class Owner(val id: String, val phoneHash: String, val phoneEnc: String, var ecdhKey: String)
    data class Contact(
        val id: String,
        val ownerId: String,
        val nameEnc: String,
        val phoneHash: String,
        val phoneEnc: String,
        var ecdhKey: String? = null,
        var state: String = INVITED,
        val inviteToken: String,
    )
    data class Recovery(val id: String, val ownerId: String, val ecdhKey: String, var done: Boolean = false)
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

    private val file = File(dir.apply { mkdirs() }, "identity.log")
    private val random = SecureRandom()
    private val owners = HashMap<String, Owner>()
    private val deviceOwner = HashMap<String, String>()
    private val wraps = HashMap<Pair<String, String>, MutableMap<Int, String>>() // (device, recipient) → epoch → sealed key
    private val contacts = HashMap<String, Contact>()
    private val recoveries = HashMap<String, Recovery>()
    private val transfers = LinkedHashMap<String, Transfer>()
    private val pendingClaims = HashMap<String, String>() // device → CLAIMED frame not yet answered by the car
    private val transferFrames = HashMap<String, Pair<String, String>>() // device → (transfer, CLAIMPENDING/CLAIMDECLINED) until the car acks
    private val pendingRotations = HashMap<String, Int>() // device → newest epoch when the rotation was asked
    private val sessions = HashMap<String, Session>()
    private val otps = HashMap<String, Otp>()
    private val claimCodes = HashMap<String, ClaimCode>()
    private val lastOtpMs = HashMap<String, Long>()
    private val otpByClient = HashMap<String, ArrayDeque<Long>>()
    private val otpAll = ArrayDeque<Long>()

    init {
        if (file.exists()) file.readLines().filter { it.isNotBlank() }.forEach(::apply)
    }

    /** Replays one stored line; every change is appended as one, so the file is the full state. */
    private fun apply(line: String) {
        val f = line.split("|")
        when (f[0]) {
            "O" -> owners[f[1]] = Owner(f[1], f[2], f[3], f[4])
            "K" -> owners[f[1]]?.ecdhKey = f[2]
            "D" -> deviceOwner[f[1]] = f[2]
            "W" -> wraps.getOrPut(f[1] to f[2]) { sortedMapOf() }[f[3].toInt()] = f[4]
            "WX" -> wraps.keys.filter { it.second == f[1] }.forEach { wraps.remove(it) }
            "C" -> contacts[f[1]] = Contact(f[1], f[2], f[3], f[4], f[5], inviteToken = f[6])
            "CK" -> contacts[f[1]]?.let { it.ecdhKey = f[2].ifEmpty { null }; it.state = f[3] }
            "R" -> recoveries[f[1]] = Recovery(f[1], f[2], f[3])
            "RD" -> recoveries[f[1]]?.done = true
            "P" -> pendingClaims[f[1]] = line.substringAfter("P|${f[1]}|")
            "PD" -> pendingClaims.remove(f[1])
            "PF" -> transferFrames[f[1]] = f[2] to line.substringAfter("PF|${f[1]}|${f[2]}|")
            "PFD" -> if (transferFrames[f[1]]?.first == f[2]) transferFrames.remove(f[1])
            "T" -> transfers[f[1]] = Transfer(f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8].toLong())
            "TS" -> transfers[f[1]]?.state = f[2]
            "S" -> sessions[f[1]] = Session(f[2], f[3], f[4].toLong())
            "Q" -> pendingRotations[f[1]] = f[2].toInt()
            "QD" -> pendingRotations.remove(f[1])
        }
    }

    @Synchronized
    private fun record(vararg fields: String) {
        val line = fields.joinToString("|")
        file.appendText(line + "\n")
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
        sms.send(phone, "[White Box] ${purposeText(purpose)} 인증번호 $code (5분 안에 입력)")
        return null
    }

    private fun purposeText(p: String) = when (p) {
        "claim" -> "차량 연결"
        "contact" -> "비상 연락처 등록"
        "recover" -> "기록 복구"
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
            CONTACT -> hash.takeIf { contacts.values.any { it.phoneHash == hash && it.state in setOf(ACCEPTED, ACTIVE) } }
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
        val c = claimCodes[code]?.takeIf { it.expiresMs > clock() } ?: return ClaimResult.Refused("차량 화면의 코드가 만료되었습니다. 차량에서 다시 시작하세요.")
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
            sms.send(ownerPhone(o), "[White Box] 다른 휴대폰(${maskPhone(phone)})이 회원님 차량의 연결을 요청했습니다. 차를 판매·양도했다면 승인하고, 아니라면 거절해 주세요. 3일 안에 거절하지 않으면 자동으로 넘어갑니다: /me")
        }
        return ClaimResult.Pending(id, transfers.getValue(t), newSession(OWNER, id))
    }

    /** Binds the car to the transfer's new owner; the caller tells the car (CLAIMED) so it starts a new key epoch. */
    @Synchronized
    private fun applyTransfer(t: Transfer, how: String) {
        record("D", t.deviceId, t.toOwnerId)
        record("TS", t.id, how)
        transferFrames[t.deviceId]?.let { record("PFD", t.deviceId, it.first) } // the CLAIMED frame supersedes it
        owners[t.fromOwnerId]?.let { sms.send(ownerPhone(it), "[White Box] 차량 연결이 ${t.masked} 휴대폰으로 넘어갔습니다. 지금까지의 기록은 계속 회원님만 볼 수 있습니다.") }
        owners[t.toOwnerId]?.let { sms.send(ownerPhone(it), "[White Box] 차량이 연결되었습니다. 지금부터의 기록을 볼 수 있습니다: /me") }
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
        owners[t.toOwnerId]?.let { sms.send(ownerPhone(it), "[White Box] 차량 소유자가 연결 요청을 거절했습니다.") }
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
        if (deviceOwner[deviceId] == recipientId) {
            pendingClaims[deviceId]?.let { record("PD", deviceId) }
            pendingRotations[deviceId]?.let { asked -> if (epoch > asked) record("QD", deviceId) }
        }
    }

    @Synchronized
    fun wraps(deviceId: String, recipientId: String): List<Wrap> = wraps[deviceId to recipientId]?.map { Wrap(it.key, it.value) } ?: emptyList()

    /** Cars [recipientId] holds keys for: their own, and cars they owned before a sale (for those records only). */
    @Synchronized
    fun devicesHeldBy(recipientId: String): List<String> = wraps.keys.filter { it.second == recipientId }.map { it.first }.distinct().sorted()

    // ---- emergency contacts ----

    /** The owner invites a contact by name and number; the contact gets a link with a one-time token. */
    @Synchronized
    fun invite(ownerId: String, name: String, phone: String, linkBase: String): Contact {
        val id = token(12)
        val inviteToken = token()
        record("C", id, ownerId, key.encryptAtRest(name, "name|$id"), phoneHash(phone), key.encryptAtRest(phone, "phone|$id"), inviteToken)
        val ownerPhone = owners[ownerId]?.let(::ownerPhone)?.let(::maskPhone) ?: ""
        sms.send(phone, "[White Box] $ownerPhone 님이 회원님을 비상 연락처로 등록하려 합니다. 동의하시면 열어 주세요: $linkBase/contact#$inviteToken")
        return contacts.getValue(id)
    }

    @Synchronized
    fun contactByInvite(token: String): Contact? = contacts.values.firstOrNull { it.inviteToken == token && it.state == INVITED }

    fun contactName(c: Contact) = key.decryptAtRest(c.nameEnc, "name|${c.id}")
    fun contactPhone(c: Contact) = key.decryptAtRest(c.phoneEnc, "phone|${c.id}")

    /** The invited person consented and proved the invited number; returns the session id (their number's hash). */
    @Synchronized
    fun accept(token: String, phone: String, otp: String, ecdhKey: String): String? {
        val c = contactByInvite(token) ?: return null
        if (c.phoneHash != phoneHash(phone) || !checkOtp(phone, otp)) return null
        record("CK", c.id, ecdhKey, ACCEPTED)
        return c.phoneHash
    }

    /** The owner's phone wrapped the keys for the contact: the contact is active. */
    @Synchronized
    fun activate(ownerId: String, contactId: String): Boolean {
        val c = contacts[contactId]?.takeIf { it.ownerId == ownerId && it.ecdhKey != null && it.state in setOf(ACCEPTED, ACTIVE) } ?: return false
        if (c.state != ACTIVE) record("CK", c.id, c.ecdhKey!!, ACTIVE)
        return true
    }

    /**
     * Ends a contact (the owner removed them, or they withdrew): their wraps are deleted and each of the owner's cars
     * is asked to start a new key epoch, so nothing recorded afterwards is readable to them.
     */
    @Synchronized
    fun removeContact(contactId: String, byContact: Boolean): Boolean {
        val c = contacts[contactId]?.takeIf { it.state != REMOVED } ?: return false
        val wasHolding = c.state == ACTIVE
        record("CK", c.id, c.ecdhKey.orEmpty(), REMOVED)
        record("WX", c.id)
        if (wasHolding) for (d in devicesOf(c.ownerId)) record("Q", d, (wraps[d to c.ownerId]?.keys?.maxOrNull() ?: 0).toString())
        if (byContact) owners[c.ownerId]?.let { sms.send(ownerPhone(it), "[White Box] ${contactName(c)} 님이 비상 연락처 등록을 해제했습니다. 다른 분을 등록해 두시길 권합니다: /me") }
        return true
    }

    @Synchronized
    fun pendingRotation(deviceId: String): Boolean = pendingRotations.containsKey(deviceId)

    @Synchronized
    fun contactsOf(ownerId: String): List<Contact> = contacts.values.filter { it.ownerId == ownerId && it.state != REMOVED }.sortedBy { it.id }

    /** Every person this number is an emergency contact for (one entry per owner). */
    @Synchronized
    fun contactsByPhoneHash(hash: String): List<Contact> = contacts.values.filter { it.phoneHash == hash && it.state in setOf(ACCEPTED, ACTIVE) }

    @Synchronized
    fun contact(id: String): Contact? = contacts[id]

    // ---- recovery: a new phone gets access through any one contact ----

    @Synchronized
    fun startRecovery(phone: String, otp: String, ecdhKey: String, linkBase: String): Pair<Recovery, String>? {
        val owner = owners.values.firstOrNull { it.phoneHash == phoneHash(phone) } ?: return null
        if (!checkOtp(phone, otp)) return null
        // The same phone asking again (it lost its session) resumes its request: no duplicate, no second text.
        recoveries.values.firstOrNull { it.ownerId == owner.id && it.ecdhKey == ecdhKey && !it.done }?.let { return it to newSession(RECOVERING, it.id) }
        val id = token(12)
        record("R", id, owner.id, ecdhKey)
        for (c in contactsOf(owner.id).filter { it.state == ACTIVE }) {
            sms.send(contactPhone(c), "[White Box] ${maskPhone(phone)} 님이 새 휴대폰으로 기록 복구를 요청했습니다. 본인과 통화해 확인 코드를 맞춰 본 뒤 승인해 주세요: $linkBase/contact")
        }
        return recoveries.getValue(id) to newSession(RECOVERING, id)
    }

    @Synchronized
    fun recovery(id: String): Recovery? = recoveries[id]

    @Synchronized
    fun pendingRecoveries(ownerId: String): List<Recovery> = recoveries.values.filter { it.ownerId == ownerId && !it.done }

    /**
     * An active contact re-wrapped every key it holds for the recovering phone: that phone's key becomes the owner's.
     * [sealed] maps (device, epoch) to the wrap and must cover every key the contact holds for this owner.
     */
    @Synchronized
    fun completeRecovery(recoveryId: String, contactId: String, sealed: Map<Pair<String, Int>, String>): Boolean {
        val r = recoveries[recoveryId]?.takeIf { !it.done } ?: return false
        val c = contacts[contactId]?.takeIf { it.ownerId == r.ownerId && it.state == ACTIVE } ?: return false
        // Every key the contact holds, including a car the owner has since sold (its records stay the owner's).
        val held = devicesHeldBy(c.id).flatMap { d -> wraps(d, c.id).map { d to it.epoch } }.toSet()
        if (held.isEmpty() || sealed.keys != held) return false
        sealed.forEach { (de, s) -> record("W", de.first, r.ownerId, de.second.toString(), s) }
        record("K", r.ownerId, r.ecdhKey)
        record("RD", r.id)
        return true
    }

    companion object {
        const val OWNER = "owner"
        const val CONTACT = "contact"
        const val RECOVERING = "recovering"
        const val INVITED = "invited"
        const val ACCEPTED = "accepted"
        const val ACTIVE = "active"
        const val REMOVED = "removed"
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
