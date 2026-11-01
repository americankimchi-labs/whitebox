package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.core.MirrorChannel
import com.americankimchilabs.whitebox.data.EpochRecordCipher
import com.americankimchilabs.whitebox.data.RecordCipher
import com.americankimchilabs.whitebox.data.MemoryKeyRing
import com.americankimchilabs.whitebox.data.CapsuleShares
import com.americankimchilabs.whitebox.data.ClaimClient
import com.americankimchilabs.whitebox.data.HandoverController
import com.americankimchilabs.whitebox.data.LibraryController
import com.americankimchilabs.whitebox.domain.Entitlements
import com.americankimchilabs.whitebox.data.FileEventStore
import com.americankimchilabs.whitebox.data.MirrorProtocol
import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import com.americankimchilabs.whitebox.data.Outbox
import com.americankimchilabs.whitebox.data.Recorder
import com.americankimchilabs.whitebox.data.SealStore
import com.americankimchilabs.whitebox.data.Signer
import com.americankimchilabs.whitebox.domain.AudioEvent
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.Chirp
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.IgnitionState
import com.americankimchilabs.whitebox.domain.VehicleSignal
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
 * dead-man switch seals what it received; with `--drive-on` the drive ends normally (stillness, all-clear). Like the
 * car, it plays the dashcam sync chirp every few seconds while driving and records each playback; at the end it
 * writes what a dashcam in the cabin would have heard (a WAV named by a dashcam clock running 2.7 s fast), to try
 * the dashcam sync on the capsule page.
 *
 *     ./gradlew :server:run                          # terminal 1, then open http://localhost:8080
 *     ./gradlew :server:demoCar                      # terminal 2
 *     ./gradlew :server:demoCar --args="--drive-on"
 *     ./gradlew :server:demoCar --args="--phone"     # first connect a phone (browser) to this car, then drive
 *     ./gradlew :server:demoCar --args="--phone --handover"  # the drive as a 대리 운전 trip, with its report
 *
 * Each run is a new car (fresh key and chain), shown on the dashboard as its own `replay` row.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.firstOrNull { it.startsWith("ws://") || it.startsWith("wss://") } ?: "ws://localhost:8080/v1/stream"
    val withHandover = "--handover" in args
    // A handover trip ends normally: parked, engine off, and its report after the grace period.
    val driveOn = "--drive-on" in args || withHandover
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
    val outbox = Outbox(store, seals, channel, dir, consent = { true }, minuteKey = { e, m -> ring.key(e)?.let { RecordCipher.minuteKey(it, m) } }, placeKey = { e, m -> ring.key(e)?.let { RecordCipher.placeKey(it, m) } })
    val recorder = Recorder(store, seals, signer, clock, outbox::onSeal)
    val work = launch(Dispatchers.Default) { outbox.start(this, recorder) }
    val web = url.replaceFirst("ws", "http").substringBefore("/v1/")
    // The evidence packet's QR, as the car shows it on its packet screen.
    val shares = CapsuleShares(store, channel, dir, MirrorProtocol.deviceId(signer.publicKey), MirrorProtocol.STREAM_REPLAY, { e, m -> ring.key(e)?.let { RecordCipher.minuteKey(it, m) } })
    val sharing = launch(Dispatchers.Default) { shares.start(this) }
    // The library and plan, as on the car: the owner's plan comes from the server (Pro unless switched on the dashboard).
    var handoverRef: HandoverController? = null
    val library = LibraryController(recorder, store, dir, clock, channel, acked = { outbox.acked }, holding = { handoverRef?.holding() ?: emptyList() })
    val libraryJob = launch(Dispatchers.Default) { library.start(this) }
    launch(Dispatchers.Default) { library.tier.collect { say("plan: $it (${Entitlements(it).historyDays}-day history, ${Entitlements(it).librarySlots} library slots)") } }
    // Handover mode, as on the car; the grace before a trip is final is shortened to 20 s for the demonstration.
    val handover = HandoverController(recorder, store, dir, clock, channel, graceMs = if (withHandover) DEMO_GRACE_MS else Tuning.HANDOVER_GRACE_MS, allowed = { library.entitlements.handover })
    handoverRef = handover
    val handoverJob = launch(Dispatchers.Default) { handover.start(this) }
    val voice = launch(Dispatchers.Default) { handover.say.collect { say("voice to the driver: $it") } }

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
    if (withHandover) {
        if (handover.armOnCar()) say("handover mode (대리 운전) armed: it starts when the car leaves Park; watch the 대리 운전 card on the phone page")
        else say("handover mode is not in this owner's plan (Free): switch the plan on the dashboard to try it")
    }
    say(if (driveOn) "driving the demo drive (≈ 70 s), ending normally" else "driving the demo drive; crash at ${DemoDrive.IMPACT_AT_MS / 1000.0} s, then power loss")
    val fixes = launch { ScriptedLocationSource(DemoDrive.locations(), clock).fixes().collect { recorder.record(ChainEntry.Location(it)) } }
    val driveStartWall = System.currentTimeMillis()
    val played = mutableListOf<Long>()
    val chirps = launch {
        var id = 0
        while (true) {
            val at = clock.now()
            recorder.record(ChainEntry.Audio(AudioEvent.Beacon(id++, at), clock.now()))
            synchronized(played) { played += at.wallMillis }
            delay(Tuning.CHIRP_PERIOD_S * 1_000L)
        }
    }
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
    chirps.cancel()
    val wav = writeDashcamStandIn(dir, driveStartWall, System.currentTimeMillis(), synchronized(played) { played.toList() })
    val qrLink = recorder.capsules.value.lastOrNull { it.capsule.trigger.crash }?.capsule?.let { shares.link(it, web) }

    if (withHandover) {
        recorder.record(ChainEntry.Signal(SignalSample(VehicleSignal.Ignition(IgnitionState.OFF), clock.now())))
        say("parked and the engine switched off: the trip is ending; final in ${DEMO_GRACE_MS / 1000} s unless the car drives off again")
        withTimeoutOrNull(DEMO_GRACE_MS + 10_000) { handover.phase.first { it == com.americankimchilabs.whitebox.domain.HandoverPhase.OFF } }
        recorder.capsules.value.lastOrNull { it.capsule.trigger == com.americankimchilabs.whitebox.domain.CapsuleTrigger.HANDOVER && it.final }?.let { trip ->
            say("trip report sealed (${trip.capsule.id}); its QR, as the car shows it:\n\n    ${shares.link(trip.capsule, web)}\n")
        }
    }
    if (driveOn) {
        delay(Tuning.NORMAL_BATCH_INTERVAL_S * 1_000L + 1_000) // the last batch
        say("drive over: the car returned to NORMAL (all-clear) and every record is mirrored; nothing more happens")
    } else {
        delay(500) // frames already handed to the socket finish leaving, as they would from a real car
        say("POWER LOSS: the car stops sending (records up to here are on the server)")
        say("the server seals what it received after ${Tuning.DEADMAN_SEAL_SILENCE_S} s of silence (nobody is texted; the emergency contacts can open the crash on their page)")
    }
    channel.close()
    work.cancel()
    say("local chain of this run: ${dir.absolutePath}")
    say("dashcam stand-in (pick it under 블랙박스 영상과 맞춰 보기 on the capsule page): ${wav.absolutePath}")
    qrLink?.let { say("the crash's evidence-packet QR (what an insurer or the police would scan; opens without signing in):\n\n    $it\n") }
    sharing.cancel()
    handoverJob.cancel()
    libraryJob.cancel()
    voice.cancel()
    exitProcess(0)
}

/**
 * What a dashcam in the cabin would have recorded of the drive: road noise and engine hum, with each chirp at the
 * instant the car played it, a little quieter than played. Its file name carries the dashcam's own clock, which runs
 * [DASHCAM_CLOCK_ERROR_MS] fast and started recording a second before the drive: the sync must find the true offset.
 */
private fun writeDashcamStandIn(dir: java.io.File, startWall: Long, endWall: Long, played: List<Long>): java.io.File {
    val rate = 48_000
    val recStart = startWall - 1_000
    val n = ((endWall - recStart + 2_000) * rate / 1_000).toInt()
    val pcm = FloatArray(n)
    val random = java.util.Random(7)
    for (i in 0 until n) pcm[i] = (0.04 * random.nextGaussian() + 0.08 * Math.sin(2 * Math.PI * 95.0 * i / rate)).toFloat()
    val chirp = Chirp.samples(rate)
    for (p in played) {
        val at = ((p - recStart) * rate / 1_000).toInt()
        for (i in chirp.indices) if (at + i in 0 until n) pcm[at + i] += chirp[i] * 0.5f
    }
    val name = java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(java.util.Date(recStart + DASHCAM_CLOCK_ERROR_MS))
    val out = dir.resolve("$name.wav")
    val data = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    data.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(n * 2)
    for (v in pcm) data.putShort((v.coerceIn(-1f, 1f) * 32_767).toInt().toShort())
    out.writeBytes(data.array())
    return out
}

private const val DASHCAM_CLOCK_ERROR_MS = 2_700L

/** The handover grace period in the demonstration (on the car: [Tuning.HANDOVER_GRACE_MS]). */
private const val DEMO_GRACE_MS = 20_000L

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
