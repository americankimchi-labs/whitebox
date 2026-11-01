package com.americankimchilabs.whitebox.server

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.request.receiveParameters
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.WebSocketDeflateExtension
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.americankimchilabs.whitebox.domain.Entitlements
import com.americankimchilabs.whitebox.domain.Tier
import java.io.File

/**
 * `PORT` (default 8080, as Cloud Run sets it), `WHITEBOX_DATA` (default ./server-data) and `WHITEBOX_DEFAULT_TIER`
 * (FREE, PLUS or PRO: the plan of an owner with none on record; default PRO) configure the server. On Cloud Run:
 * `WHITEBOX_STORE=firestore` keeps everything in Firestore, `WHITEBOX_SERVER_KEY` brings the keys from the secret
 * store (`./gradlew -q :server:newServerKey` makes one), `WHITEBOX_CLIPS` is the mounted bucket for saved clips,
 * `WHITEBOX_DASHBOARD_PASSWORD` protects the dashboard and `WHITEBOX_APP_VERSION` names the newest app version.
 *
 *     ./gradlew :server:run          # the emulator reaches it at ws://10.0.2.2:8080/v1/stream
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dir = File(System.getenv("WHITEBOX_DATA") ?: "server-data")
    val store: Durable = if (System.getenv("WHITEBOX_STORE") == "firestore") FirestoreDurable() else FileDurable(dir)
    val key = System.getenv("WHITEBOX_SERVER_KEY")?.takeIf { it.isNotBlank() }?.let(ServerKey::fromSecret) ?: ServerKey(dir)
    val sms = DemoSmsSender()
    val identity = Identity(store, key, sms)
    // The plan of an owner with none on record; the contest build shows everything (Pro).
    val defaultTier = System.getenv("WHITEBOX_DEFAULT_TIER")?.let { t -> Tier.entries.firstOrNull { it.name == t.uppercase() } } ?: Entitlements.DEFAULT
    val plans = Plans(store, defaultTier, DemoPayment())
    val clips = ClipStore(store, System.getenv("WHITEBOX_CLIPS")?.let(::File) ?: File(dir, "clips"))
    val service = MirrorService(DurableStreamStore(store), key, identity, plans = plans, clips = clips)
    println("White Box mirror on :$port, data in ${if (store is FirestoreDurable) "Firestore" else dir.absolutePath}")
    embeddedServer(Netty, port = port, host = "0.0.0.0") { whiteBox(service, sms) }.start(wait = true)
}

/**
 * [appVersion]: the newest app version on the market, `code|name` (e.g. `2|0.9.0`, set at each release as
 * WHITEBOX_APP_VERSION): the car compares it with its own and, if newer, points the driver to the market. The app never
 * updates itself.
 */
fun Application.whiteBox(
    service: MirrorService,
    sms: DemoSmsSender? = null,
    tickMs: Long = 500,
    appVersion: String? = System.getenv("WHITEBOX_APP_VERSION"),
    /** Protects the dashboard (it shows the demonstration's login codes and switches plans); none locally. */
    dashboardPassword: String? = System.getenv("WHITEBOX_DASHBOARD_PASSWORD")?.takeIf { it.isNotBlank() },
) {
    install(WebSockets) {
        // Keeps the car's channel warm through idle NAT and load-balancer timeouts.
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
        maxFrameSize = 1L shl 20
        // permessage-deflate: the 10 s batches shrink to under half (hashes, times and record structure compress; the
        // encrypted values do not). Small frames, such as each sample of a crash-time burst, go as they are.
        extensions {
            install(WebSocketDeflateExtension) { compressIfBiggerThan(bytes = 1024) }
        }
    }
    launch {
        while (isActive) {
            service.tick()
            delay(tickMs)
        }
    }
    routing {
        get("/v1/app/version") {
            val (code, name) = appVersion?.split("|")?.takeIf { it.size == 2 && it[0].toIntOrNull() != null }
                ?: return@get call.respondText("{}", io.ktor.http.ContentType.Application.Json)
            call.respondText("{\"versionCode\":$code,\"versionName\":\"${name.filter { it.isLetterOrDigit() || it == '.' }}\"}", io.ktor.http.ContentType.Application.Json)
        }
        webSocket("/v1/stream") {
            val session = service.session()
            send(Frame.Text(session.greeting()))
            val pushes = launch { for (p in session.pushes) send(Frame.Text(p)) }
            try {
                receive(session)
            } finally {
                pushes.cancel()
                service.closed(session)
            }
        }
        get("/") { if (call.dashboardAllowed(dashboardPassword)) call.respondText(dashboard(service, sms), ContentType.Text.Html) }
        get("/dashboard/cards") { if (call.dashboardAllowed(dashboardPassword)) call.respondText(dashboardCards(service, sms), ContentType.Text.Html) }
        // The admin switch: an owner's plan without payment (the dashboard is for whoever runs the server).
        post("/dashboard/plan") {
            if (!call.dashboardAllowed(dashboardPassword)) return@post
            val p = call.receiveParameters()
            val device = p["device"].orEmpty()
            val owner = service.identity?.ownerOf(device)
            val tier = Tier.entries.firstOrNull { it.name == p["tier"] }
            if (owner == null || tier == null || service.plans == null) return@post call.respondText("no", status = io.ktor.http.HttpStatusCode.BadRequest)
            service.plans.setTier(owner.id, tier, service.identity.devicesOf(owner.id), "admin")
            service.identity.devicesOf(owner.id).forEach(service::libraryChanged)
            call.respondText("ok")
        }
        service.identity?.let { phoneRoutes(service, it) }
        get("/health") { call.respondText("ok") }
        get("/v1/server-key") { call.respondBytes(service.serverPublicKey(), ContentType.Application.OctetStream) }
    }
}

/** One car connection: every text frame through the session; a frame before authentication closes it. */
private suspend fun DefaultWebSocketServerSession.receive(session: MirrorService.Session) {
    for (frame in incoming) {
        if (frame !is Frame.Text) continue
        val replies = session.onText(frame.readText())
        if (replies == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "authenticate first"))
            return
        }
        replies.forEach { send(Frame.Text(it)) }
    }
}

/**
 * HTTP Basic for the dashboard (any user name, the configured password); answers 401 and returns false otherwise.
 * Without a password (local runs) the dashboard is open.
 */
private suspend fun io.ktor.server.application.ApplicationCall.dashboardAllowed(password: String?): Boolean {
    if (password == null) return true
    val given = request.headers["Authorization"]?.takeIf { it.startsWith("Basic ") }
        ?.let { runCatching { String(java.util.Base64.getDecoder().decode(it.removePrefix("Basic ").trim())) }.getOrNull() }
        ?.substringAfter(':')
    if (given != null && java.security.MessageDigest.isEqual(given.toByteArray(), password.toByteArray())) return true
    response.headers.append("WWW-Authenticate", "Basic realm=\"White Box dashboard\", charset=\"UTF-8\"")
    respondText("dashboard password needed", status = io.ktor.http.HttpStatusCode.Unauthorized)
    return false
}

