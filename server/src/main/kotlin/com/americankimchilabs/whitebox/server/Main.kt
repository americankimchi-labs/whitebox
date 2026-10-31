package com.americankimchilabs.whitebox.server

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
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
import java.io.File

/**
 * `PORT` (default 8080, as Cloud Run sets it) and `WHITEBOX_DATA` (default ./server-data) configure the server.
 *
 *     ./gradlew :server:run          # the emulator reaches it at ws://10.0.2.2:8080/v1/stream
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dir = File(System.getenv("WHITEBOX_DATA") ?: "server-data")
    val service = MirrorService(FileStreamStore(dir), ServerKey(dir), LoggingNotifier(dir))
    println("White Box mirror on :$port, data in ${dir.absolutePath}")
    embeddedServer(Netty, port = port, host = "0.0.0.0") { whiteBox(service) }.start(wait = true)
}

fun Application.whiteBox(service: MirrorService, tickMs: Long = 500) {
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
            try {
                receive(session)
            } finally {
                service.closed(session)
            }
        }
        get("/") { call.respondText(dashboard(service), ContentType.Text.Html) }
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
