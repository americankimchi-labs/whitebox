package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.core.MirrorChannel
import com.americankimchilabs.whitebox.data.EpochRecordCipher
import com.americankimchilabs.whitebox.data.MemoryKeyRing
import com.americankimchilabs.whitebox.data.ClaimClient
import com.americankimchilabs.whitebox.data.FileEventStore
import com.americankimchilabs.whitebox.data.MirrorProtocol
import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import com.americankimchilabs.whitebox.data.Outbox
import com.americankimchilabs.whitebox.data.Recorder
import com.americankimchilabs.whitebox.data.SealStore
import com.americankimchilabs.whitebox.data.Signer
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.RecorderEvent
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.StartReason
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.Tuning
import com.americankimchilabs.whitebox.vehicle.fake.DemoDrive
import com.americankimchilabs.whitebox.vehicle.fake.ScriptedLocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CompletionStage
import kotlin.system.exitProcess

/**
 * A simulated car for the mirror, so the dashboard can be explored without the Pleos emulator: the scripted demo
 * drive (lane changes, hard brake, crash) runs in real time through the car's own recorder and outbox code and is
 * mirrored over the same protocol. By default the car "loses power" right after the crash, so the server's
 * dead-man switch seals and notifies; with `--drive-on` the drive ends normally (stillness, all-clear).
 *
 *     ./gradlew :server:run                          # terminal 1, then open http://localhost:8080
 *     ./gradlew :server:demoCar                      # terminal 2
 *     ./gradlew :server:demoCar --args="--drive-on"
 *     ./gradlew :server:demoCar --args="--phone"     # first connect a phone (browser) to this car, then drive
 *
 * Each run is a new car (fresh key and chain), shown on the dashboard as its own `replay` row.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.firstOrNull { it.startsWith("ws://") || it.startsWith("wss://") } ?: "ws://localhost:8080/v1/stream"
    val driveOn = "--drive-on" in args
    val withPhone = "--phone" in args
    val dir = Files.createTempDirectory("whitebox-demo-car").toFile()
    val signer = DemoKey()
    val clock = Clock {
        Timestamp(System.nanoTime(), System.currentTimeMillis())
    }
    val channel = JdkMirrorChannel(url, MirrorProtocol.STREAM_REPLAY, signer, this)
    // Like the car: record values are encrypted with this car's own root keys before they enter the chain.
    val ring = MemoryKeyRing()
    val store = FileEventStore(dir, cipher = EpochRecordCipher(ring))
    val seals = SealStore(dir)
    // A test car takes part in improving detection (a real driver is asked once, and it is off unless they agree).
    val outbox = Outbox(store, seals, channel, dir) { true }
    val recorder = Recorder(store, seals, signer, clock, outbox::onSeal)
    val work = launch(Dispatchers.Default) { outbox.start(this, recorder) }

    say("connecting to $url")
    if (withTimeoutOrNull(10_000) { channel.connected.first { it } } == null) {
        say("no mirror at $url — start it first: ./gradlew :server:run")
        exitProcess(1)
    }
    say("connected as device ${MirrorProtocol.deviceId(signer.publicKey).take(12)}… (stream replay); watch http://localhost:8080")
    recorder.record(ChainEntry.Recorder(RecorderEvent.RecordingStarted(StartReason.APP_OPENED, null, 0), clock.now()))

    if (withPhone) {
        // What the parked car's "휴대폰" tab does: a claim code from the mirror plus this car's own secret.
        val claim = ClaimClient(channel, ring, dir)
        val claimJob = claim.start(this)
        claim.request()
        val waiting = withTimeoutOrNull(10_000) { claim.state.first { it is ClaimClient.State.Waiting } } as? ClaimClient.State.Waiting
        if (waiting == null) {
            say("the mirror gave no claim code")
            exitProcess(1)
        }
        val web = url.replaceFirst("ws", "http").substringBefore("/v1/")
        say("connect a phone: open this in a browser (the QR on a real car holds the same address)")
        println()
        println("    $web/claim#${waiting.code}.${waiting.secret}")
        println()
        say("the one-time code appears on the dashboard (문자, 시연용); waiting up to 10 minutes…")
        if (withTimeoutOrNull(600_000) { claim.state.first { it is ClaimClient.State.Claimed } } == null) {
            say("no phone connected in time")
            exitProcess(1)
        }
        say("phone connected: ${(claim.state.value as ClaimClient.State.Claimed).maskedPhone}; open $web/me in that browser to watch")
        claimJob.cancel()
    }

    val powerLossAtMs = DemoDrive.IMPACT_AT_MS + POWER_LOSS_AFTER_IMPACT_MS
    say(if (driveOn) "driving the demo drive (≈ 70 s), ending normally" else "driving the demo drive; crash at ${DemoDrive.IMPACT_AT_MS / 1000.0} s, then power loss")
    val fixes = launch { ScriptedLocationSource(DemoDrive.locations(), clock).fixes().collect { recorder.record(ChainEntry.Location(it)) } }
    var level = EscalationLevel.NORMAL
    var scriptMs = 0L
    for (step in DemoDrive.signals()) {
        scriptMs += step.afterMillis
        if (!driveOn && scriptMs > powerLossAtMs) break // cut by the script's own timeline, not the wall clock
        delay(step.afterMillis)
        recorder.record(ChainEntry.Signal(SignalSample(step.signal, clock.now())))
        if (recorder.level.value != level) {
            level = recorder.level.value
            say("level $level")
        }
    }
    fixes.cancel()

    if (driveOn) {
        delay(Tuning.NORMAL_BATCH_INTERVAL_S * 1_000L + 1_000) // the last batch
        say("drive over: the car returned to NORMAL (all-clear) and every record is mirrored; nothing more happens")
    } else {
        delay(500) // frames already handed to the socket finish leaving, as they would from a real car
        say("POWER LOSS: the car stops sending (records up to here are on the server)")
        say("the server seals what it received after ${Tuning.DEADMAN_SEAL_SILENCE_S} s of silence and, because the crash was severe, issues the emergency notice after ${Tuning.DEADMAN_NOTIFY_SILENCE_S} s")
    }
    channel.close()
    work.cancel()
    say("local chain of this run: ${dir.absolutePath}")
    exitProcess(0)
}

/** The demo car "loses power" this long after the impact: the crash records go out first. */
private const val POWER_LOSS_AFTER_IMPACT_MS = 300L

private val t0 = System.nanoTime()

private fun say(msg: String) = println("%6.1fs  %s".format((System.nanoTime() - t0) / 1e9, msg))

/** A device key for the simulated car (the real car's lives in the Android Keystore). */
private class DemoKey : Signer {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override val publicKey: ByteArray = pair.public.encoded
    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private)
        update(data)
        sign()
    }
}

/**
 * [MirrorChannel] over the JDK's own WebSocket client (one connection, no reconnect: a demo run is short). Sends
 * go through a queue because the JDK client allows one outstanding send at a time.
 */
private class JdkMirrorChannel(url: String, private val stream: String, private val signer: Signer, scope: CoroutineScope) : MirrorChannel {
    override val connected = MutableStateFlow(false)
    override val incoming = MutableSharedFlow<String>(extraBufferCapacity = 256)
    @Volatile override var serverKey: ByteArray? = null
    private val outgoing = Channel<String>(Channel.UNLIMITED)
    private var ws: WebSocket? = null

    init {
        val listener = object : WebSocket.Listener {
            private val buf = StringBuilder()
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buf.append(data)
                if (last) {
                    val text = buf.toString()
                    buf.setLength(0)
                    when (val f = MirrorProtocol.decode(text)) {
                        is Frame.Challenge -> outgoing.trySend(
                            MirrorProtocol.encode(Frame.Auth(stream, signer.publicKey, signer.sign(MirrorProtocol.authText(stream, f.nonce).toByteArray()))),
                        )
                        is Frame.Ready -> {
                            serverKey = f.serverKey
                            connected.value = true
                        }
                        is Frame.Denied -> println("mirror refused: ${f.reason}")
                        else -> incoming.tryEmit(text)
                    }
                }
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                connected.value = false
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                connected.value = false
            }
        }
        scope.launch(Dispatchers.IO) {
            val s = runCatching { HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI(url), listener).await() }.getOrNull()
                ?: return@launch
            ws = s
            for (frame in outgoing) runCatching { s.sendText(frame, true).await() }
        }
    }

    override fun send(frame: String): Boolean = connected.value && outgoing.trySend(frame).isSuccess

    fun close() {
        connected.value = false
        outgoing.close()
        ws?.abort()
    }
}
