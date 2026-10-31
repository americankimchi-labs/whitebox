package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** One session cookie per role, so a phone (or one test browser) can be owner, contact and recovering phone at once. */
private fun cookie(role: String) = "wb_$role"

/**
 * The phone side: pages for the owner (claim the car, see it, manage emergency contacts, answer a takeover), for a
 * contact (consent, crash alerts, recovery approval, withdrawal) and for recovery on a new phone. All decryption
 * happens in the phone's browser; these routes only move wrapped keys and encrypted records.
 */
fun Route.phoneRoutes(service: MirrorService, identity: Identity) {
    for (page in listOf("claim", "me", "contact", "recover")) {
        get("/$page") { call.respondText(resource("web/$page.html"), ContentType.Text.Html) }
    }
    get("/wb.js") { call.respondText(resource("web/wb.js"), ContentType.Text.JavaScript) }

    post("/v1/otp") {
        val p = call.receiveParameters()
        val phone = normalizePhone(p["phone"].orEmpty()) ?: return@post call.fail("휴대폰 번호를 확인해 주세요.")
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
                // A car sold on shows nothing about its life since (no state, no activity, no record counts).
                "streams" to service.statuses().filter { it.stream.deviceId == d }.map { s ->
                    if (d in current) obj("stream" to s.stream.stream, "records" to s.records, "level" to s.level?.name, "silentForMs" to s.silentForMs)
                    else obj("stream" to s.stream.stream)
                },
            )
        }
        val contacts = identity.contactsOf(owner.id).map { c ->
            obj(
                "id" to c.id, "name" to identity.contactName(c), "phone" to maskPhone(identity.contactPhone(c)), "state" to c.state, "key" to c.ecdhKey,
                "holds" to identity.devicesOf(owner.id).flatMap { d -> identity.wraps(d, c.id).map { obj("device" to d, "epoch" to it.epoch) } },
            )
        }
        val incoming = identity.transfersFrom(owner.id).map { obj("id" to it.id, "phone" to it.masked, "createdMs" to it.createdMs, "autoAtMs" to it.autoAtMs) }
        val outgoing = identity.transfersTo(owner.id).map { obj("state" to it.state, "createdMs" to it.createdMs, "autoAtMs" to it.autoAtMs) }
        call.json(
            obj(
                "phone" to maskPhone(identity.ownerPhone(owner)), "key" to owner.ecdhKey, "devices" to devices, "contacts" to contacts,
                "transfersIn" to incoming, "transfersOut" to outgoing,
            ),
        )
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
     * The last records of a car the caller holds keys for, as stored (values encrypted; the browser decrypts),
     * limited to the key epochs the caller holds: a previous owner sees their own time, a new owner theirs.
     */
    get("/v1/records") {
        val q = call.request.queryParameters
        val device = q["device"].orEmpty()
        val holder = call.owner(identity)?.id?.takeIf { identity.wraps(device, it).isNotEmpty() }
            ?: call.contacts(identity)?.firstOrNull { it.state == Identity.ACTIVE && identity.wraps(device, it.id).isNotEmpty() }?.id
            ?: return@get call.unauthorized()
        val epochs = identity.wraps(device, holder).map { it.epoch }.toSet()
        val kind = q["kind"]
        val last = (q["last"]?.toIntOrNull() ?: 20).coerceIn(1, 500)
        val records = service.lastRecords(StreamId(device, q["stream"] ?: "live"), 20_000)
            .filter { (_, e) -> (kind == null || e.startsWith("$kind|")) && sealedEpoch(e)?.let { it in epochs } ?: false }
            .takeLast(last)
        call.json(records.map { (seq, entry) -> obj("seq" to seq, "entry" to entry) })
    }

    post("/v1/contacts/invite") {
        val owner = call.owner(identity) ?: return@post call.unauthorized()
        val p = call.receiveParameters()
        val phone = normalizePhone(p["phone"].orEmpty()) ?: return@post call.fail("휴대폰 번호를 확인해 주세요.")
        val name = p["name"].orEmpty().trim().take(20).ifEmpty { return@post call.fail("이름을 입력해 주세요.") }
        identity.invite(owner.id, name, phone, call.base())
        call.ok()
    }

    get("/v1/invite") {
        val c = identity.contactByInvite(call.request.queryParameters["token"].orEmpty()) ?: return@get call.fail("초대가 만료되었거나 이미 처리되었습니다.")
        val owner = identity.owner(c.ownerId) ?: return@get call.fail("초대가 만료되었거나 이미 처리되었습니다.")
        call.json(obj("owner" to maskPhone(identity.ownerPhone(owner)), "name" to identity.contactName(c), "phone" to maskPhone(identity.contactPhone(c)), "state" to c.state))
    }

    post("/v1/contacts/accept") {
        val p = call.receiveParameters()
        if (p["consent"] != "yes") return@post call.fail("동의가 필요합니다.")
        val phone = normalizePhone(p["phone"].orEmpty()) ?: return@post call.fail("휴대폰 번호를 확인해 주세요.")
        val session = identity.accept(p["token"].orEmpty(), phone, p["otp"].orEmpty(), p["key"].orEmpty())
            ?: return@post call.fail("초대받은 번호와 인증번호를 확인해 주세요.")
        call.setSession(Identity.CONTACT, identity.newSession(Identity.CONTACT, session))
        call.ok()
    }

    /** The owner's phone wrapped car keys for a contact (when approving, and in the background after a new epoch). */
    post("/v1/contacts/wrap") {
        val owner = call.owner(identity) ?: return@post call.unauthorized()
        val p = call.receiveParameters()
        val contact = identity.contact(p["contact"].orEmpty())?.takeIf { it.ownerId == owner.id && it.state != Identity.REMOVED } ?: return@post call.unauthorized()
        val wraps = p.wraps()
        val mine = identity.devicesOf(owner.id).toSet()
        if (wraps.isEmpty() || wraps.keys.any { it.first !in mine }) return@post call.fail("잘못된 요청입니다.")
        wraps.forEach { (de, s) -> identity.putWrap(de.first, contact.id, de.second, s) }
        identity.activate(owner.id, contact.id)
        call.ok()
    }

    /** The owner removes a contact (also cancels an invitation not yet answered). */
    post("/v1/contacts/remove") {
        val owner = call.owner(identity) ?: return@post call.unauthorized()
        val c = identity.contact(call.receiveParameters()["contact"].orEmpty())?.takeIf { it.ownerId == owner.id } ?: return@post call.unauthorized()
        if (identity.removeContact(c.id, byContact = false)) service.rotate(identity.devicesOf(owner.id))
        call.ok()
    }

    get("/v1/contact/me") {
        val mine = call.contacts(identity) ?: return@get call.unauthorized()
        val people = mine.mapNotNull { c ->
            val owner = identity.owner(c.ownerId) ?: return@mapNotNull null
            obj(
                "contact" to c.id, "owner" to maskPhone(identity.ownerPhone(owner)), "state" to c.state,
                "devices" to (identity.devicesOf(owner.id) + identity.devicesHeldBy(c.id)).distinct().map { d ->
                    obj(
                        "id" to d,
                        "wraps" to wrapList(identity.wraps(d, c.id)),
                        "events" to service.statuses().filter { it.stream.deviceId == d }.flatMap { s ->
                            s.notices.map { obj("type" to "notice", "stream" to s.stream.stream, "atMs" to it.substringBefore('|').toLongOrNull()) }
                        },
                    )
                },
                "recoveries" to if (c.state == Identity.ACTIVE) identity.pendingRecoveries(owner.id).map { obj("id" to it.id, "key" to it.ecdhKey) } else emptyList<Json>(),
            )
        }
        call.json(obj("people" to people))
    }

    /** A contact withdraws their consent for one person. */
    post("/v1/contact/withdraw") {
        val c = call.contact(identity, call.receiveParameters()["contact"]) ?: return@post call.unauthorized()
        if (identity.removeContact(c.id, byContact = true)) service.rotate(identity.devicesOf(c.ownerId))
        call.ok()
    }

    post("/v1/recover/start") {
        val p = call.receiveParameters()
        val phone = normalizePhone(p["phone"].orEmpty()) ?: return@post call.fail("휴대폰 번호를 확인해 주세요.")
        val (_, session) = identity.startRecovery(phone, p["otp"].orEmpty(), p["key"].orEmpty(), call.base())
            ?: return@post call.fail("연결된 차량이 없는 번호이거나 인증번호가 맞지 않습니다.")
        call.setSession(Identity.RECOVERING, session)
        call.ok()
    }

    /** Once a contact completed the recovery, this phone becomes the owner's: it gets an owner session. */
    get("/v1/recover/me") {
        val s = identity.session(call.request.cookies[cookie(Identity.RECOVERING)])?.takeIf { it.role == Identity.RECOVERING } ?: return@get call.unauthorized()
        val r = identity.recovery(s.id) ?: return@get call.unauthorized()
        if (r.done) call.setSession(Identity.OWNER, identity.newSession(Identity.OWNER, r.ownerId))
        call.json(obj("done" to r.done, "key" to r.ecdhKey, "ownsCar" to identity.devicesOf(r.ownerId).isNotEmpty()))
    }

    /** An active contact re-wrapped every key it holds for the recovering phone. */
    post("/v1/recover/wrap") {
        val p = call.receiveParameters()
        val c = call.contact(identity, p["contact"])?.takeIf { it.state == Identity.ACTIVE } ?: return@post call.unauthorized()
        if (!identity.completeRecovery(p["recovery"].orEmpty(), c.id, p.wraps())) return@post call.fail("이미 처리되었거나 키가 모두 오지 않았습니다.")
        call.ok()
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

/** Device, epoch and wrap from repeated form fields. */
private fun io.ktor.http.Parameters.wraps(): Map<Pair<String, Int>, String> {
    val d = getAll("device").orEmpty()
    val e = getAll("epoch").orEmpty().map { it.toIntOrNull() ?: -1 }
    val s = getAll("sealed").orEmpty()
    if (d.size != e.size || d.size != s.size) return emptyMap()
    return d.indices.associate { (d[it] to e[it]) to s[it] }
}

/** The key epoch of an encrypted record, or null for a record without encrypted values. */
private fun sealedEpoch(entry: String): Int? {
    val f = entry.split("|")
    val field = when (f[0]) {
        "S" -> f.getOrNull(2)
        "L" -> f.getOrNull(1)
        else -> null
    } ?: return null
    return if (com.americankimchilabs.whitebox.data.RecordCipher.isSealed(field)) com.americankimchilabs.whitebox.data.RecordCipher.epochOf(field) else null
}

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
