package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.Entitlements
import com.americankimchilabs.whitebox.domain.Extension
import com.americankimchilabs.whitebox.domain.LibraryStatus
import com.americankimchilabs.whitebox.domain.Retention
import com.americankimchilabs.whitebox.domain.Tier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** One session cookie per role, so a phone (or one test browser) can be owner and contact at once. */
private fun cookie(role: String) = "wb_$role"

/**
 * The phone side: pages for the owner (claim the car, its drives and capsules, emergency contacts, a takeover) and
 * for an emergency contact (the owner's severe crash capsules). Everyday records are decrypted in the owner's
 * browser only; a severe capsule also opens with the minute keys the car handed the server for it.
 */
fun Route.phoneRoutes(service: MirrorService, identity: Identity) {
    for (page in listOf("claim", "me", "contact", "capsule", "drives")) {
        get("/$page") { call.respondText(resource("web/$page.html"), ContentType.Text.Html) }
    }
    for (script in listOf("wb.js", "codec.js", "packet.js", "verify.js", "sync.js", "report.js", "clip.js")) {
        get("/$script") { call.respondText(resource("web/$script"), ContentType.Text.JavaScript) }
    }
    get("/wb.css") { call.respondText(resource("web/wb.css"), ContentType.Text.CSS) }

    post("/v1/otp") {
        val p = call.receiveParameters()
        val phone = normalizePhone(p["phone"].orEmpty()) ?: return@post call.fail("휴대폰 번호를 확인해 주세요.")
        // A family member signing in: only registered numbers get a text, and the answer never says which are.
        if (p["purpose"] == "contact" && !identity.isContactNumber(phone)) return@post call.ok()
        identity.sendOtp(phone, p["purpose"].orEmpty(), call.request.origin.remoteHost)?.let { return@post call.fail(it) }
        call.ok()
    }

    /** Signing in again with the number and a code; the phone's key is still in its browser. */
    post("/v1/login") {
        val p = call.receiveParameters()
        val role = p["role"].takeIf { it == Identity.OWNER || it == Identity.CONTACT } ?: return@post call.fail("잘못된 요청입니다.")
        val phone = normalizePhone(p["phone"].orEmpty()) ?: return@post call.fail("휴대폰 번호를 확인해 주세요.")
        val session = identity.login(role, phone, p["otp"].orEmpty()) ?: return@post call.fail(
            if (role == Identity.OWNER) "연결된 차량이 없는 번호이거나 인증번호가 맞지 않습니다." else "비상 연락처로 등록된 번호가 아니거나 인증번호가 맞지 않습니다.",
        )
        call.setSession(role, session)
        call.ok()
    }

    post("/v1/claim") {
        val p = call.receiveParameters()
        val phone = normalizePhone(p["phone"].orEmpty()) ?: return@post call.fail("휴대폰 번호를 확인해 주세요.")
        val key = p["key"].orEmpty()
        val mac = p["mac"].orEmpty()
        when (val r = identity.claim(p["code"].orEmpty().uppercase(), phone, p["otp"].orEmpty(), key, mac)) {
            is Identity.ClaimResult.Refused -> call.fail(r.reason)
            is Identity.ClaimResult.Claimed -> {
                service.claimed(r.stream.deviceId, Frame.Claimed(r.ownerId, key, mac, maskPhone(phone)))
                call.setSession(Identity.OWNER, r.session)
                call.json(obj("ok" to true))
            }
            is Identity.ClaimResult.Pending -> {
                service.transferChanged(r.transfer.deviceId)
                call.setSession(Identity.OWNER, r.session)
                call.json(obj("ok" to true, "pending" to true, "autoAtMs" to r.transfer.autoAtMs))
            }
        }
    }

    get("/v1/me") {
        val owner = call.owner(identity) ?: return@get call.unauthorized()
        val current = identity.devicesOf(owner.id)
        // Cars owned before a sale stay readable for the records of that time.
        val devices = (current + identity.devicesHeldBy(owner.id)).distinct().map { d ->
            obj(
                "id" to d,
                "current" to (d in current),
                "wraps" to wrapList(identity.wraps(d, owner.id)),
                "handover" to if (d in current) identity.handoverStatus(d).let { h ->
                    obj(
                        "phase" to (h?.phase ?: "OFF"), "requestedAtWall" to h?.requestedAtWall, "sinceWall" to h?.sinceWall,
                        "tripId" to h?.tripId?.takeIf { it != "-" }, "pending" to identity.handoverPending(d),
                    )
                } else null,
                // A car sold on shows nothing about its life since (no state, no activity, no record counts).
                "streams" to service.statuses().filter { it.stream.deviceId == d }.map { s ->
                    if (d in current) obj(
                        "stream" to s.stream.stream, "records" to s.records, "level" to s.level?.name, "silentForMs" to s.silentForMs,
                        "capsules" to s.seals.map { it.substringBefore('|') }.distinct().size + s.severeSilent.size,
                    )
                    else obj("stream" to s.stream.stream)
                },
            )
        }
        val contacts = identity.contactsOf(owner.id).map { c ->
            obj("id" to c.id, "name" to identity.contactName(c), "phone" to maskPhone(identity.contactPhone(c)), "addedMs" to c.addedMs)
        }
        val recent = service.now() - 30L * 24 * 3600_000
        val left = identity.contactsWhoLeft(owner.id).filter { (it.removedMs ?: 0) > recent }.map { obj("name" to identity.contactName(it), "atMs" to it.removedMs) }
        // Who opened which crash record: the owner always sees it (nobody else is told).
        val names = identity.contactsOf(owner.id).associate { it.id to identity.contactName(it) } +
            identity.contactsWhoLeft(owner.id).associate { it.id to identity.contactName(it) }
        val opened = (current + identity.devicesHeldBy(owner.id)).distinct().flatMap { d ->
            service.streamsOf(d).flatMap { st -> service.log(st, OPENED) }.mapNotNull { l ->
                val f = l.split("|")
                (if (f.getOrNull(1) == SHARE_LINK) "QR 공유 링크" else names[f.getOrNull(1)])?.let { obj("name" to it, "atMs" to f[0].toLongOrNull(), "capsule" to f.getOrNull(2), "shared" to (f.getOrNull(1) == SHARE_LINK)) }
            }
        }
        val incoming = identity.transfersFrom(owner.id).map { obj("id" to it.id, "phone" to it.masked, "createdMs" to it.createdMs, "autoAtMs" to it.autoAtMs) }
        val outgoing = identity.transfersTo(owner.id).map { obj("state" to it.state, "createdMs" to it.createdMs, "autoAtMs" to it.autoAtMs) }
        call.json(
            obj(
                "phone" to maskPhone(identity.ownerPhone(owner)), "key" to owner.ecdhKey, "devices" to devices, "contacts" to contacts,
                "contactsLeft" to left, "opened" to opened, "transfersIn" to incoming, "transfersOut" to outgoing,
                "plan" to planJson(service, identity, owner.id, current),
            ),
        )
    }

    /** The owner turns handover mode (대리 운전) on or off for their car from the phone. */
    post("/v1/handover") {
        val owner = call.owner(identity) ?: return@post call.unauthorized()
        val p = call.receiveParameters()
        val device = p["device"].orEmpty()
        val tier = service.plans?.tier(owner.id) ?: Entitlements.DEFAULT
        if (p["action"] == "arm" && !Entitlements(tier).handover) return@post call.fail("대리 운전 모드는 플러스 요금제부터 쓸 수 있습니다.")
        identity.handoverCommand(owner.id, device, p["action"] == "arm") ?: return@post call.fail("이 차의 차주만 바꿀 수 있습니다.")
        service.handoverChanged(device)
        call.ok()
    }

    /**
     * The owner made a share link for one of their capsules on the phone: the phone derived the capsule's minute keys
     * and put them in the link; the server keeps only the hash of the link's code, for 90 days.
     */
    post("/v1/share") {
        val p = call.receiveParameters()
        val device = p["device"].orEmpty()
        val owner = call.owner(identity)?.takeIf { identity.wraps(device, it.id).isNotEmpty() } ?: return@post call.unauthorized()
        val stream = StreamId(device, p["stream"] ?: "live")
        val epochs = identity.wraps(device, owner.id).map { it.epoch }.toSet()
        val c = service.capsules(stream).firstOrNull { it.id == p["id"] && epochs.containsAll(it.epochs) } ?: return@post call.fail("기록을 찾을 수 없습니다.")
        val hash = p["hash"].orEmpty().takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return@post call.fail("잘못된 요청입니다.")
        val expires = service.now() + SHARE_VALID_MS
        service.addShare(stream, c.id, hash, expires)
        call.json(obj("ok" to true, "expiresMs" to expires))
    }

    /** The owner changes plan (through the payment port; the demonstration takes no money and says so). */
    post("/v1/plan") {
        val owner = call.owner(identity) ?: return@post call.unauthorized()
        val plans = service.plans ?: return@post call.fail("요금제를 바꿀 수 없습니다.")
        val tier = call.receiveParameters()["tier"]?.let { t -> Tier.entries.firstOrNull { it.name == t } } ?: return@post call.fail("잘못된 요청입니다.")
        val devices = identity.devicesOf(owner.id)
        plans.setTier(owner.id, tier, devices, "purchase")?.let { return@post call.fail(it) }
        devices.forEach(service::libraryChanged)
        call.json(obj("ok" to true, "notice" to plans.payments.notice))
    }

    /** The owner keeps one capsule (and its clip) longer: 1 or 3 years from the end of its free 30 days. */
    post("/v1/extend") {
        val p = call.receiveParameters()
        val device = p["device"].orEmpty()
        val owner = call.owner(identity)?.takeIf { identity.wraps(device, it.id).isNotEmpty() } ?: return@post call.unauthorized()
        val plans = service.plans ?: return@post call.fail("연장할 수 없습니다.")
        val stream = StreamId(device, p["stream"] ?: "live")
        val epochs = identity.wraps(device, owner.id).map { it.epoch }.toSet()
        val c = service.capsules(stream).firstOrNull { it.id == p["id"] && epochs.containsAll(it.epochs) } ?: return@post call.fail("기록을 찾을 수 없습니다.")
        val e = Extension.entries.firstOrNull { it.years.toString() == p["years"] } ?: return@post call.fail("잘못된 요청입니다.")
        val until = plans.extend(owner.id, device, c.id, c.eventMs, e).getOrElse { return@post call.fail(it.message ?: "결제하지 못했습니다.") }
        service.libraryChanged(device)
        call.json(obj("ok" to true, "untilMs" to until, "notice" to plans.payments.notice))
    }

    /**
     * The owner saves a synced dashcam clip, encrypted on the phone (the body is the ciphertext). Counted against the
     * plan's clip slots, paid (extended) clips included. When they are full the answer says so with the clips that
     * may be replaced (unpaid ones, oldest first) and the plans above; a paid clip is never replaced, only deleted
     * by the owner.
     */
    post("/v1/clips") {
        val q = call.request.queryParameters
        val device = q["device"].orEmpty()
        val owner = call.owner(identity)?.takeIf { identity.wraps(device, it.id).isNotEmpty() } ?: return@post call.unauthorized()
        val clips = service.clips ?: return@post call.fail("영상을 저장할 수 없습니다.")
        val stream = StreamId(device, q["stream"] ?: "live")
        val epochs = identity.wraps(device, owner.id).map { it.epoch }.toSet()
        val c = service.capsules(stream).firstOrNull { it.id == q["id"] && epochs.containsAll(it.epochs) } ?: return@post call.fail("기록을 찾을 수 없습니다.")
        val used = clipsInSlots(service, identity.devicesOf(owner.id))
        val slots = Entitlements(service.plans?.tier(owner.id) ?: Entitlements.DEFAULT).clipSlots
        val replace = q["replace"]?.let { r -> used.firstOrNull { it.clip.id == r && !it.paid } }
        if (used.size >= slots && replace == null) {
            return@post call.json(obj(
                "ok" to false, "full" to true, "slots" to slots,
                "replaceable" to used.filter { !it.paid }.sortedBy { it.clip.savedAtMs }.map { clipJson(it.clip) },
                "saved" to used.sortedBy { it.clip.savedAtMs }.map(::slotJson),
                "upgrades" to Entitlements.above(service.plans?.tier(owner.id) ?: Entitlements.DEFAULT).map { it.name },
            ))
        }
        val clip = withContext(Dispatchers.IO) {
            call.receiveStream().use { body ->
                runCatching { clips.save(q["clip"].orEmpty(), device, stream.stream, c.id, q["offsetMs"]?.toDoubleOrNull() ?: 0.0, q["name"].orEmpty(), body, MAX_CLIP_BYTES, service.now()) }.getOrNull()
            }
        } ?: return@post call.fail("영상이 너무 크거나 잘못된 요청입니다.")
        replace?.let { clips.remove(it.clip.id, "replaced", service.now()) }
        call.json(obj("ok" to true, "clip" to clipJson(clip), "untilMs" to service.clipUntil(stream, c)))
    }

    /**
     * A clip the car attached by itself from a built-in camera (Plus and Pro): accepted only when signed by the key
     * that sealed the capsule. Counted against the owner's clip slots; when full the oldest unpaid clip gives way
     * (nobody is there to ask), and with only paid clips saved the new one is not kept.
     */
    post("/v1/car/clips") {
        val q = call.request.queryParameters
        val clips = service.clips ?: return@post call.fail("영상을 저장할 수 없습니다.")
        val stream = StreamId(q["device"].orEmpty(), q["stream"] ?: "live")
        val c = service.capsules(stream).firstOrNull { it.id == q["id"] } ?: return@post call.fail("기록을 찾을 수 없습니다.")
        val seal = c.seals.firstNotNullOfOrNull { runCatching { com.americankimchilabs.whitebox.data.SealStore.parse(it) }.getOrNull() } ?: return@post call.fail("봉인이 없습니다.")
        if (com.americankimchilabs.whitebox.data.MirrorProtocol.deviceId(seal.publicKey) != stream.deviceId) return@post call.unauthorized()
        val owner = identity.ownerOf(stream.deviceId)
        if (!Entitlements(service.plans?.tier(owner?.id) ?: Entitlements.DEFAULT).builtInCameraClips) return@post call.fail("요금제에 포함되지 않습니다.")
        val clipId = q["clip"].orEmpty()
        val offset = q["offsetMs"]?.toDoubleOrNull() ?: 0.0
        val saved = withContext(Dispatchers.IO) {
            call.receiveStream().use { runCatching { clips.save(clipId, stream.deviceId, stream.stream, c.id, offset, q["name"].orEmpty(), it, MAX_CLIP_BYTES, service.now()) }.getOrNull() }
        } ?: return@post call.fail("잘못된 요청입니다.")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(clips.file(saved.id).readBytes()).joinToString("") { "%02x".format(it) }
        val signed = runCatching {
            java.security.Signature.getInstance("SHA256withECDSA").run {
                initVerify(java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(seal.publicKey)))
                update(com.americankimchilabs.whitebox.data.ClipFormat.signedText(c.id, clipId, offset, digest).toByteArray())
                verify(java.util.Base64.getUrlDecoder().decode(q["sig"].orEmpty()))
            }
        }.getOrDefault(false)
        if (!signed) {
            clips.remove(saved.id, "unsigned", service.now())
            return@post call.unauthorized()
        }
        owner?.let { o ->
            val used = clipsInSlots(service, identity.devicesOf(o.id))
            val slots = Entitlements(service.plans?.tier(o.id) ?: Entitlements.DEFAULT).clipSlots
            if (used.size > slots) {
                val oldest = used.filter { it.clip.id != saved.id && !it.paid }.minByOrNull { it.clip.savedAtMs }
                if (oldest == null) {
                    clips.remove(saved.id, "full", service.now())
                    return@post call.json(obj("ok" to false, "full" to true, "slots" to slots))
                }
                clips.remove(oldest.clip.id, "replaced", service.now())
            }
        }
        call.json(obj("ok" to true, "clip" to clipJson(saved)))
    }

    /** A saved clip (still encrypted), for whoever may open its capsule: the owner, or a contact for a crash. */
    get("/v1/clips/get") {
        val q = call.request.queryParameters
        val clip = service.clips?.get(q["clip"].orEmpty()) ?: return@get call.fail("영상을 찾을 수 없습니다.")
        val access = call.capsuleAccess(identity, clip.device) ?: return@get call.unauthorized()
        val stream = StreamId(clip.device, clip.stream)
        val c = service.capsules(stream).firstOrNull { it.id == clip.capsuleId }
            ?.takeIf { (access.contact == null || (it.severe && access.contactFor(it.epochs) != null)) && access.mayRead(it.epochs) }
            ?: return@get call.fail("영상을 찾을 수 없습니다.")
        call.respondFile(service.clips!!.file(clip.id))
    }

    /** A saved clip of a shared capsule (the share code in the body, as for the capsule itself). */
    post("/v1/shared/clip") {
        val p = call.receiveParameters()
        val clip = service.clips?.get(p["clip"].orEmpty()) ?: return@post call.fail("영상을 찾을 수 없습니다.")
        val stream = StreamId(clip.device, clip.stream)
        if (!service.shareValid(stream, clip.capsuleId, p["t"].orEmpty())) return@post call.fail("공유 기간이 끝났거나 잘못된 QR입니다.")
        call.respondFile(service.clips!!.file(clip.id))
    }

    /** The owner deletes a saved clip. */
    post("/v1/clips/delete") {
        val owner = call.owner(identity) ?: return@post call.unauthorized()
        val clip = service.clips?.get(call.receiveParameters()["clip"].orEmpty())?.takeIf { it.device in identity.devicesOf(owner.id) }
            ?: return@post call.fail("영상을 찾을 수 없습니다.")
        service.clips!!.remove(clip.id, "owner", service.now())
        call.ok()
    }

    /** The owner answers a takeover request for their car. */
    post("/v1/transfer/answer") {
        val owner = call.owner(identity) ?: return@post call.unauthorized()
        val p = call.receiveParameters()
        val id = p["id"].orEmpty()
        val device = identity.transfersFrom(owner.id).firstOrNull { it.id == id }?.deviceId
        identity.answerTransfer(owner.id, id, p["approve"] == "yes")?.let { service.transferred(it) }
        device?.let(service::transferChanged) // a decline: the car gives the waiting records back to the owner
        call.ok()
    }

    /**
     * Records of a car the owner holds keys for, as stored (values encrypted; the browser decrypts), limited to the
     * key epochs they hold: a previous owner sees their own time, a new owner theirs. Either the newest [last], or
     * the range [from]..[to] thinned to one record per kind every [every] ms (a drive's graph without every sample).
     */
    get("/v1/records") {
        val q = call.request.queryParameters
        val device = q["device"].orEmpty()
        val owner = call.owner(identity)?.takeIf { identity.wraps(device, it.id).isNotEmpty() } ?: return@get call.unauthorized()
        val epochs = identity.wraps(device, owner.id).map { it.epoch }.toSet()
        val stream = StreamId(device, q["stream"] ?: "live")
        val kind = q["kind"]
        val from = q["from"]?.toLongOrNull()
        val records = if (from != null) {
            val to = q["to"]?.toLongOrNull() ?: from
            thinned(service.recordsBetween(stream, from, minOf(to, from + 200_000)), (q["every"]?.toLongOrNull() ?: 0).coerceAtLeast(0))
        } else service.lastRecords(stream, 20_000).filter { (_, e) -> kind == null || e.startsWith("$kind|") }
        // A plain marker (trip start, detection) belongs to the time of the encrypted record before it; in a range it
        // comes along only in the requester's own time, so a seller asking for seq numbers from after the sale does
        // not learn when the buyer drove.
        var lastEpoch: Int? = null
        val mine = records.filter { (_, e) ->
            val epoch = recordEpoch(e)
            if (epoch != null) lastEpoch = epoch
            if (epoch != null) epoch in epochs else from != null && lastEpoch?.let { it in epochs } == true
        }
        val last = (q["last"]?.toIntOrNull() ?: 20).coerceIn(1, 5_000)
        call.json((if (from != null) mine else mine.takeLast(last)).map { (seq, entry) -> obj("seq" to seq, "entry" to entry) })
    }

    /** The owner's drives: continuous stretches of recording, from the records' readable times and markers. */
    get("/v1/drives") {
        val q = call.request.queryParameters
        val device = q["device"].orEmpty()
        val owner = call.owner(identity)?.takeIf { identity.wraps(device, it.id).isNotEmpty() } ?: return@get call.unauthorized()
        val epochs = identity.wraps(device, owner.id).map { it.epoch }.toSet()
        val stream = StreamId(device, q["stream"] ?: "live")
        call.json(service.drives(stream) { e -> e in epochs }.map { d ->
            obj(
                "firstSeq" to d.firstSeq, "lastSeq" to d.lastSeq, "startMs" to d.startMs, "endMs" to d.endMs, "records" to d.records,
                "odometer" to d.odometer.map { (seq, entry) -> obj("seq" to seq, "entry" to entry) },
                "capsules" to d.capsules, "bookmarks" to d.bookmarks, "hardBrakes" to d.hardBrakes,
            )
        })
    }

    /**
     * Capsules of a car: the owner sees every capsule of their time; an emergency contact sees severe ones only
     * (crash-level, with the minute keys the car handed over), plus a severe event the server sealed when the car
     * went silent.
     */
    get("/v1/capsules") {
        val q = call.request.queryParameters
        val device = q["device"].orEmpty()
        val access = call.capsuleAccess(identity, device) ?: return@get call.unauthorized()
        val stream = StreamId(device, q["stream"] ?: "live")
        call.json(service.capsules(stream).filter { c -> (access.contact == null || (c.severe && access.contactFor(c.epochs) != null)) && access.mayRead(c.epochs) }.map { capsuleJson(service, stream, it) })
    }

    /**
     * One capsule: its chain records (with the one before, so the first link checks too), its seals, the server's
     * signed anchors, and — for a contact, or an owner whose phone has no key — the minute keys of a severe capsule.
     * A contact's opening is logged for the owner.
     */
    get("/v1/capsule") {
        val q = call.request.queryParameters
        val device = q["device"].orEmpty()
        val access = call.capsuleAccess(identity, device) ?: return@get call.unauthorized()
        val stream = StreamId(device, q["stream"] ?: "live")
        val c = service.capsules(stream).firstOrNull { it.id == q["id"] }?.takeIf { (access.contact == null || (it.severe && access.contactFor(it.epochs) != null)) && access.mayRead(it.epochs) }
            ?: return@get call.fail("기록을 찾을 수 없습니다.")
        val wantsKeys = access.contact != null || q["keys"] == "1"
        val keys = if (wantsKeys && c.severe) service.minuteKeys(stream, c.id) else emptyList()
        if (keys.isNotEmpty()) access.contactFor(c.epochs)?.let { service.logOpened(stream, it.id, c.id) }
        call.json(
            obj(
                "capsule" to capsuleJson(service, stream, c),
                "records" to service.recordLines(stream, maxOf(0, c.firstSeq - 1), c.lastSeq),
                "anchors" to service.anchorsCovering(stream, c.lastSeq),
                "serverKey" to java.util.Base64.getEncoder().encodeToString(service.serverPublicKey()),
                "keys" to keys.map { (e, m, k) -> obj("epoch" to e, "minute" to m, "key" to k) },
                "copies" to if (c.attestation != null && (access.contact != null || q["keys"] == "1")) service.signalCopies(stream, c.firstSeq, c.lastSeq).map { (seq, e) -> obj("seq" to seq, "entry" to e) } else emptyList<Json>(),
            ),
        )
    }

    /**
     * A capsule opened from its QR (an insurer, the police): the share code comes in the request body (it lives in
     * the link's fragment, never in a URL the server logs). Encrypted records, seals and anchors only; the keys are
     * in the link. Each opening is shown to the owner.
     */
    post("/v1/shared") {
        val p = call.receiveParameters()
        val stream = StreamId(p["device"].orEmpty(), p["stream"] ?: "live")
        val id = p["id"].orEmpty()
        if (!service.shareValid(stream, id, p["t"].orEmpty())) return@post call.fail("공유 기간이 끝났거나 잘못된 QR입니다. 운전자에게 다시 요청해 주세요.")
        val c = service.capsules(stream).firstOrNull { it.id == id } ?: return@post call.fail("기록이 아직 서버에 올라오지 않았습니다. 잠시 뒤 다시 열어 주세요.")
        service.logOpened(stream, SHARE_LINK, c.id)
        call.json(
            obj(
                "capsule" to capsuleJson(service, stream, c), "records" to service.recordLines(stream, maxOf(0, c.firstSeq - 1), c.lastSeq),
                "anchors" to service.anchorsCovering(stream, c.lastSeq), "serverKey" to java.util.Base64.getEncoder().encodeToString(service.serverPublicKey()),
                "keys" to emptyList<Json>(), "copies" to emptyList<Json>(), "shared" to true,
            ),
        )
    }

    /** The owner adds an emergency contact: a name and a number, nothing is sent to them. */
    post("/v1/contacts/add") {
        val owner = call.owner(identity) ?: return@post call.unauthorized()
        val p = call.receiveParameters()
        val phone = normalizePhone(p["phone"].orEmpty()) ?: return@post call.fail("휴대폰 번호를 확인해 주세요.")
        val name = p["name"].orEmpty().trim().take(20).ifEmpty { return@post call.fail("이름을 입력해 주세요.") }
        if (phone == identity.ownerPhone(owner)) return@post call.fail("본인 번호는 비상 연락처로 저장할 수 없습니다.")
        identity.addContact(owner.id, name, phone)
        call.ok()
    }

    post("/v1/contacts/remove") {
        val owner = call.owner(identity) ?: return@post call.unauthorized()
        val c = identity.contact(call.receiveParameters()["contact"].orEmpty())?.takeIf { it.ownerId == owner.id } ?: return@post call.unauthorized()
        identity.removeContact(c.id, byContact = false)
        call.ok()
    }

    /** A signed-in contact: everyone they are an emergency contact for, with each car they can open crash records of. */
    get("/v1/contact/me") {
        val mine = call.contacts(identity) ?: return@get call.unauthorized()
        val people = mine.mapNotNull { c ->
            val owner = identity.owner(c.ownerId) ?: return@mapNotNull null
            obj(
                "contact" to c.id, "name" to identity.contactName(c), "owner" to maskPhone(identity.ownerPhone(owner)),
                "devices" to identity.devicesHeldBy(owner.id).map { d -> obj("id" to d, "streams" to service.streamsOf(d).map { it.stream }) },
            )
        }
        call.json(obj("people" to people))
    }

    /** A contact takes themselves off one person's list; the owner sees it on their page. */
    post("/v1/contact/withdraw") {
        val c = call.contact(identity, call.receiveParameters()["contact"]) ?: return@post call.unauthorized()
        identity.removeContact(c.id, byContact = true)
        call.ok()
    }
}

/** A share link made on the phone lasts as long as one shown on the car. */
private const val SHARE_VALID_MS = com.americankimchilabs.whitebox.data.CapsuleShares.VALID_MS

/** A dashcam clip of a few minutes in high definition is a few hundred MB. */
private const val MAX_CLIP_BYTES = 1L shl 30

/** Who opened a capsule, in the opening log, when it was someone holding its QR link. */
private const val SHARE_LINK = "share"

/** A saved clip in one of the owner's slots; [paid]: its capsule is extended (it is never replaced, only deleted). */
private class SlotClip(val clip: ClipStore.Clip, val untilMs: Long, val paid: Boolean)

/** Saved clips that use a slot: of the owner's cars, not past their end — extended ones included. */
private fun clipsInSlots(service: MirrorService, devices: List<String>): List<SlotClip> {
    val now = service.now()
    return (service.clips?.ofDevices(devices) ?: emptyList()).mapNotNull { k ->
        val stream = StreamId(k.device, k.stream)
        val c = service.capsules(stream).firstOrNull { it.id == k.capsuleId } ?: return@mapNotNull null
        val until = service.clipUntil(stream, c)
        val ext = service.library(stream, c).extendedUntil
        SlotClip(k, until, ext != null && now < ext).takeIf { now < until }
    }
}

private fun slotJson(s: SlotClip) = s.clip.let { k ->
    obj("id" to k.id, "savedAtMs" to k.savedAtMs, "bytes" to k.bytes, "name" to k.name, "capsule" to k.capsuleId, "untilMs" to s.untilMs, "paid" to s.paid)
}

/** The owner's plan as the phone shows it: what it includes, how much of it is used, and what can be bought. */
private fun planJson(service: MirrorService, identity: Identity, ownerId: String, devices: List<String>): Json {
    val plans = service.plans
    val plan = plans?.plan(ownerId) ?: Plans.Plan(Entitlements.DEFAULT, null, 0)
    val e = Entitlements(plan.tier)
    val now = service.now()
    val kept = devices.sumOf { d -> service.streamsOf(d).filter { it.stream == "live" }.sumOf { st -> service.capsules(st).count { service.library(st, it).usesSlot(now) } } }
    return obj(
        "tier" to plan.tier.name, "previous" to plan.previous?.name, "changedAtMs" to plan.changedAtMs,
        "historyDays" to (plans?.historyDays(ownerId) ?: e.historyDays), "planHistoryDays" to e.historyDays,
        "librarySlots" to e.librarySlots, "libraryUsed" to kept, "clipSlots" to e.clipSlots, "clipsUsed" to clipsInSlots(service, devices).size,
        "handover" to e.handover, "accidentReport" to e.accidentReport, "monthlyWon" to e.monthlyWon,
        "upgrades" to Entitlements.above(plan.tier).map { it.name },
        "tiers" to Tier.entries.map { t -> Entitlements(t).let { obj("tier" to t.name, "monthlyWon" to it.monthlyWon, "historyDays" to it.historyDays, "librarySlots" to it.librarySlots, "clipSlots" to it.clipSlots, "handover" to it.handover, "accidentReport" to it.accidentReport) } },
        "extensions" to Extension.entries.map { obj("years" to it.years, "priceWon" to it.priceWon) },
        "paymentNotice" to plans?.payments?.notice,
    )
}

/** Who may read a car's capsules: its owner (for the epochs they hold), or a contact of that owner (a 큰 사고 only). */
private class CapsuleAccess(val epochs: Set<Int>, private val contacts: List<Pair<Identity.Contact, Set<Int>>>) {
    /** Null for the owner; otherwise the family member's entries (one per owner who listed them) for this car. */
    val contact: Identity.Contact? get() = contacts.firstOrNull()?.first

    /** A capsule of another owner's time (the car was sold) stays theirs. */
    fun mayRead(capsuleEpochs: Set<Int>) = capsuleEpochs.isEmpty() || epochs.containsAll(capsuleEpochs)

    /** Which of the family member's entries lets them open a capsule of [capsuleEpochs] (for the opening log). */
    fun contactFor(capsuleEpochs: Set<Int>) = contacts.firstOrNull { (_, e) -> capsuleEpochs.isEmpty() || e.containsAll(capsuleEpochs) }?.first
}

private fun ApplicationCall.capsuleAccess(identity: Identity, device: String): CapsuleAccess? {
    owner(identity)?.let { o ->
        val epochs = identity.wraps(device, o.id).map { it.epoch }.toSet()
        if (epochs.isNotEmpty()) return CapsuleAccess(epochs, emptyList())
    }
    // One person may be listed by both the seller and the buyer of a car: each listing opens its own owner's time.
    val listed = contacts(identity).orEmpty().map { c -> c to identity.wraps(device, c.ownerId).map { it.epoch }.toSet() }.filter { it.second.isNotEmpty() }
    if (listed.isEmpty()) return null
    return CapsuleAccess(listed.flatMap { it.second }.toSet(), listed)
}

private fun capsuleJson(service: MirrorService, stream: StreamId, c: MirrorService.CapsuleInfo): Json {
    val now = service.now()
    val item = service.library(stream, c)
    return obj(
        "id" to c.id, "trigger" to c.trigger, "severe" to c.severe,
        // 충돌: a crash-level hit without an airbag (a 큰 사고 is a crash too).
        "crash" to (c.severe || runCatching { CapsuleTrigger.valueOf(c.trigger).crash }.getOrDefault(false)),
        "firstSeq" to c.firstSeq, "lastSeq" to c.lastSeq,
        "final" to c.final, "sealedAtMs" to c.sealedAtMs, "seals" to c.seals, "attestation" to c.attestation,
        "escrowed" to c.escrowed, "answer" to c.answer,
        // Where it stands in the library: how long its records and its clip stay, and whether the end is near.
        "eventMs" to c.eventMs, "status" to item.status(now).name, "keptUntilMs" to item.recordsUntil,
        "clipUntilMs" to service.clipUntil(stream, c), "extendedUntilMs" to item.extendedUntil,
        // Deleted within the week: a kept moment near its 30 days, or one held after leaving a full library.
        "warn" to (!c.severe && (item.left || item.extendedUntil == null) && Retention.warn(item.recordsUntil, now)),
        "clips" to (service.clips?.of(stream.deviceId, stream.stream, c.id) ?: emptyList()).map(::clipJson),
    )
}

private fun clipJson(k: ClipStore.Clip) = obj("id" to k.id, "savedAtMs" to k.savedAtMs, "bytes" to k.bytes, "offsetMs" to k.offsetMs, "name" to k.name)

/** One record per kind every [everyMs] (markers always), by the records' own times. */
private fun thinned(records: List<Pair<Long, String>>, everyMs: Long): List<Pair<Long, String>> {
    if (everyMs <= 0) return records
    val last = HashMap<String, Long>()
    return records.filter { (_, e) ->
        val type = e.substringBefore('|')
        if (type != "S" && type != "L") return@filter true
        val key = if (type == "S") e.split("|")[1] else "L"
        val at = runCatching { com.americankimchilabs.whitebox.data.EntryCodec.timestamp(e).wallMillis }.getOrNull() ?: return@filter true
        val prev = last[key]
        if (prev != null && at - prev < everyMs) false else { last[key] = at; true }
    }
}

private fun resource(path: String): String =
    Thread.currentThread().contextClassLoader.getResource(path)?.readText() ?: error("missing $path")

private fun ApplicationCall.base() = "${request.origin.scheme}://${request.origin.serverHost}:${request.origin.serverPort}"

private fun ApplicationCall.setSession(role: String, token: String) = response.cookies.append(
    Cookie(cookie(role), token, httpOnly = true, path = "/", maxAge = 30 * 24 * 3600, secure = request.origin.scheme == "https", extensions = mapOf("SameSite" to "Strict")),
)

private fun ApplicationCall.owner(identity: Identity): Identity.Owner? =
    identity.session(request.cookies[cookie(Identity.OWNER)])?.takeIf { it.role == Identity.OWNER }?.let { identity.owner(it.id) }

/** Everyone the signed-in contact is an emergency contact for (their session is their number's keyed hash). */
private fun ApplicationCall.contacts(identity: Identity): List<Identity.Contact>? =
    identity.session(request.cookies[cookie(Identity.CONTACT)])?.takeIf { it.role == Identity.CONTACT }?.let { identity.contactsByPhoneHash(it.id) }

/** The signed-in contact's entry for [contactId], if it is theirs. */
private fun ApplicationCall.contact(identity: Identity, contactId: String?): Identity.Contact? = contacts(identity)?.firstOrNull { it.id == contactId }

private fun wrapList(ws: List<Identity.Wrap>) = ws.map { obj("epoch" to it.epoch, "sealed" to it.sealed) }

private suspend fun ApplicationCall.json(body: Json) = respondText(body.text, ContentType.Application.Json)
private suspend fun ApplicationCall.json(list: List<Json>) = respondText(list.joinToString(",", "[", "]") { it.text }, ContentType.Application.Json)
private suspend fun ApplicationCall.ok() = json(obj("ok" to true))
private suspend fun ApplicationCall.fail(message: String) = respondText(obj("ok" to false, "error" to message).text, ContentType.Application.Json, HttpStatusCode.BadRequest)
private suspend fun ApplicationCall.unauthorized() = respondText(obj("ok" to false, "error" to "다시 인증해 주세요.").text, ContentType.Application.Json, HttpStatusCode.Unauthorized)

/** A JSON document; strings never become structure, so user input cannot change its shape. */
class Json(val text: String)

/** A JSON object from Kotlin values (strings, numbers, booleans, null, lists, nested [Json]). */
fun obj(vararg pairs: Pair<String, Any?>): Json = Json(pairs.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:${value(v)}" })

private fun value(v: Any?): String = when (v) {
    null -> "null"
    is Json -> v.text
    is Number, is Boolean -> v.toString()
    is List<*> -> v.joinToString(",", "[", "]") { value(it) }
    else -> str(v.toString())
}

private fun str(s: String) = buildString {
    append('"')
    for (ch in s) when {
        ch == '"' -> append("\\\"")
        ch == '\\' -> append("\\\\")
        ch == '\n' -> append("\\n")
        ch < ' ' -> append("\\u%04x".format(ch.code))
        else -> append(ch)
    }
    append('"')
}
