package com.americankimchilabs.whitebox.platform

import android.util.Log
import com.americankimchilabs.whitebox.core.MirrorChannel
import com.americankimchilabs.whitebox.data.MirrorProtocol
import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import com.americankimchilabs.whitebox.data.Signer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.util.concurrent.TimeUnit

private const val TAG = "WhiteBox/Mirror"

/** Reconnect backoff: 1 s doubling to 30 s while the server stays unreachable (tunnels, dead zones). */
private const val BACKOFF_MIN_MS = 1_000L
private const val BACKOFF_MAX_MS = 30_000L

/**
 * The mirror's always-open WebSocket (OkHttp): connected at all times while recording, so a burst never waits for
 * a TCP + TLS handshake. Pings every 15 s keep it alive through NAT and proxies; a lost connection is reopened
 * with backoff. On each connection the car proves its device key by signing the server's challenge; only then is
 * [connected] true. Release builds use wss:// (TLS 1.2+ by OkHttp's defaults).
 */
class WebSocketMirrorChannel(
    private val url: String,
    private val stream: String,
    private val signer: Signer,
) : MirrorChannel, Closeable {

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _connected = MutableStateFlow(false)
    override val connected = _connected.asStateFlow()
    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 256)
    override val incoming = _incoming.asSharedFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile override var serverKey: ByteArray? = null
        private set
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            var backoff = BACKOFF_MIN_MS
            while (isActive) {
                val closed = CompletableDeferred<Unit>()
                socket = client.newWebSocket(Request.Builder().url(url).build(), listener(closed))
                closed.await()
                _connected.value = false
                socket = null
                if (!isActive) break
                if (wasReady) backoff = BACKOFF_MIN_MS // it worked until now: retry quickly
                wasReady = false
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
            }
        }
    }

    @Volatile private var wasReady = false

    private fun listener(closed: CompletableDeferred<Unit>) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val f = MirrorProtocol.decode(text)) {
                is Frame.Challenge -> {
                    val signature = signer.sign(MirrorProtocol.authText(stream, f.nonce).toByteArray())
                    webSocket.send(MirrorProtocol.encode(Frame.Auth(stream, signer.publicKey, signature)))
                }
                is Frame.Ready -> {
                    Log.i(TAG, "mirror connected ($stream)")
                    wasReady = true
                    serverKey = f.serverKey
                    _connected.value = true
                }
                is Frame.Denied -> {
                    Log.w(TAG, "mirror refused the car: ${f.reason}")
                    webSocket.close(1000, null)
                }
                null -> if (!_connected.value) {
                    // Before READY every frame must be understood: anything else means the server runs another
                    // version of the protocol, and the car would otherwise wait silently forever.
                    Log.e(TAG, "mirror speaks a different protocol version (cannot read: ${text.take(40)}); update the app or the server")
                    webSocket.close(1000, null)
                }
                else -> _incoming.tryEmit(text)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (_connected.value) Log.i(TAG, "mirror connection lost: ${t.message}")
            closed.complete(Unit)
        }
    }

    override fun send(frame: String): Boolean = _connected.value && socket?.send(frame) == true

    override fun close() {
        job?.cancel()
        socket?.close(1000, null)
        _connected.value = false
    }
}
