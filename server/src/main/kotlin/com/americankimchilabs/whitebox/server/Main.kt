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
 * (FREE, PLUS or PRO: the plan of an owner with none on record; default PRO) configure the server.
 *
 *     ./gradlew :server:run          # the emulator reaches it at ws://10.0.2.2:8080/v1/stream
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dir = File(System.getenv("WHITEBOX_DATA") ?: "server-data")
    val key = ServerKey(dir)
    val sms = DemoSmsSender()
    val identity = Identity(dir, key, sms)
    // The plan of an owner with none on record; the contest build shows everything (Pro).
    val defaultTier = System.getenv("WHITEBOX_DEFAULT_TIER")?.let { t -> Tier.entries.firstOrNull { it.name == t.uppercase() } } ?: Entitlements.DEFAULT
    val plans = Plans(dir, defaultTier, DemoPayment())
    val service = MirrorService(FileStreamStore(dir), key, identity, plans = plans, clips = ClipStore(dir))
    println("White Box mirror on :$port, data in ${dir.absolutePath}")
    embeddedServer(Netty, port = port, host = "0.0.0.0") { whiteBox(service, sms) }.start(wait = true)
}

fun Application.whiteBox(service: MirrorService, sms: DemoSmsSender? = null, tickMs: Long = 500) {
    install(WebSockets) {
        // Keeps the car's channel warm through idle NAT and load-balancer timeouts.
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
        maxFrameSize = 1L shl 20
    }
    launch {
        while (isActive) {
            service.tick()
            delay(tickMs)
        }
    }
    routing {
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
        get("/") { call.respondText(dashboard(service, sms), ContentType.Text.Html) }
        get("/dashboard/cards") { call.respondText(dashboardCards(service, sms), ContentType.Text.Html) }
        // The admin switch: an owner's plan without payment (the dashboard is for whoever runs the server).
        post("/dashboard/plan") {
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
