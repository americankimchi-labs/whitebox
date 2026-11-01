package com.americankimchilabs.whitebox.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.americankimchilabs.whitebox.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.americankimchilabs.whitebox.data.SleepDetector
import android.os.SystemClock
import com.americankimchilabs.whitebox.R
import com.americankimchilabs.whitebox.data.EpochRecordCipher
import com.americankimchilabs.whitebox.data.RecordCipher
import com.americankimchilabs.whitebox.data.CapsuleShares
import com.americankimchilabs.whitebox.data.ClaimClient
import com.americankimchilabs.whitebox.data.HandoverController
import com.americankimchilabs.whitebox.data.LibraryController
import com.americankimchilabs.whitebox.data.FileEventStore
import com.americankimchilabs.whitebox.data.MirrorProtocol
import com.americankimchilabs.whitebox.data.Outbox
import com.americankimchilabs.whitebox.data.Recorder
import com.americankimchilabs.whitebox.data.SealStore
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.GearPosition
import com.americankimchilabs.whitebox.domain.RecorderEvent
import com.americankimchilabs.whitebox.domain.RecorderPermission
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.SessionRecord
import com.americankimchilabs.whitebox.domain.StartReason
import com.americankimchilabs.whitebox.domain.Tier
import com.americankimchilabs.whitebox.domain.Entitlements
import com.americankimchilabs.whitebox.domain.VehicleSignal
import com.americankimchilabs.whitebox.domain.VoiceLine
import com.americankimchilabs.whitebox.domain.checkGrants
import com.americankimchilabs.whitebox.domain.classifyStart
import com.americankimchilabs.whitebox.platform.AndroidClock
import com.americankimchilabs.whitebox.platform.FallbackSpeechOutput
import com.americankimchilabs.whitebox.platform.DataConsent
import com.americankimchilabs.whitebox.platform.KeystoreKeyRing
import com.americankimchilabs.whitebox.platform.PlatformAudioBeacon
import com.americankimchilabs.whitebox.platform.PlatformAudioFocus
import com.americankimchilabs.whitebox.platform.PlatformConnectivity
import com.americankimchilabs.whitebox.platform.PlatformSpeechOutput
import com.americankimchilabs.whitebox.platform.WebSocketMirrorChannel
import com.americankimchilabs.whitebox.platform.PlatformLocationSource
import com.americankimchilabs.whitebox.platform.PlatformMotionSource
import com.americankimchilabs.whitebox.platform.RoadGradeTracker
import com.americankimchilabs.whitebox.platform.SessionFile
import com.americankimchilabs.whitebox.platform.bootCount
import com.americankimchilabs.whitebox.platform.recorderGrants
import com.americankimchilabs.whitebox.ui.voiceLineText
import com.americankimchilabs.whitebox.vehicle.pleos.GleoAssistant
import com.americankimchilabs.whitebox.vehicle.pleos.PleosCredentials
import com.americankimchilabs.whitebox.vehicle.pleos.PleosPhraseListener
import com.americankimchilabs.whitebox.vehicle.pleos.PleosSpeechOutput
import com.americankimchilabs.whitebox.vehicle.pleos.RealVehicleSignalSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.io.Closeable

private const val TAG = "WhiteBox/Recorder"
private const val CHANNEL = "recorder"
private const val NOTIFICATION_ID = 1

/**
 * The foreground logging service. Starts from BOOT_COMPLETED, from the app,
 * or as a sticky restart; runs in every gear.
 *
 * All chain writes go through one inbox consumed by one coroutine, so the chain order is the arrival order:
 * vehicle signals, location fixes, and the recorder's own gap and grant events (gap honesty).
 */
class RecorderService : LifecycleService() {

    private var mode: SourceMode? = null
    private var inbox = Channel<ChainEntry>(Channel.UNLIMITED)
    private var jobs = mutableListOf<Job>()
    private var sourceJob: Job? = null
    private var closeSource: Closeable? = null
    private var sessionFile: SessionFile? = null
    private var session: SessionRecord? = null
    private var grants: Set<RecorderPermission>? = null
    private var gear: GearPosition? = null

    private var voiceJob: Job? = null
    private var mirrorChannel: WebSocketMirrorChannel? = null

    /** Speech objects live as long as the service: the car's speech service drops callbacks once a client is gone. */
    private val credentials by lazy {
        BuildConfig.PLEOS_CLIENT_ID.takeIf { it.isNotEmpty() }?.let { PleosCredentials(it, BuildConfig.PLEOS_CLIENT_SECRET) }
    }
    private val pleosVoice by lazy { PleosSpeechOutput(this, credentials) }
    private val platformVoice by lazy { PlatformSpeechOutput(this) }
    private val answerListener by lazy { PleosPhraseListener(this, onDeviceOnly = false, credentials) }
    private val offlineListener by lazy { PleosPhraseListener(this, onDeviceOnly = true, credentials) }
    private val beacon by lazy { PlatformAudioBeacon() }

    /** Record values are encrypted with the car's root keys (one per epoch) before they enter the chain. */
    private val keyRing by lazy { KeystoreKeyRing(this) }
    private val recordCipher by lazy { EpochRecordCipher(keyRing) }

    private fun minuteKey(epoch: Int, minute: Long): ByteArray? = keyRing.key(epoch)?.let { RecordCipher.minuteKey(it, minute) }

    /**
     * Where the phone pages live: the mirror's own address over http(s). The emulator reaches its host computer as
     * 10.0.2.2, but a browser on that computer knows it as localhost.
     */
    /**
     * The server's web address for [forPhones] (QR links: the emulator's host is `localhost` to a phone on that
     * computer) or for the car's own requests (the host as the car reaches it).
     */
    private fun webBase(mirrorUrl: String, forPhones: Boolean = true): String? = runCatching {
        val u = java.net.URI(mirrorUrl)
        val host = if (forPhones && u.host == "10.0.2.2") "localhost" else u.host
        "${if (u.scheme == "wss") "https" else "http"}://$host${if (u.port > 0) ":${u.port}" else ""}"
    }.getOrNull()?.takeIf { mirrorUrl.isNotEmpty() }

    /** Road grade from the head unit's accelerometer (live only); its learned mounting survives restarts. */
    private val grade by lazy { RoadGradeTracker(java.io.File(filesDir, "motion-mount")) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val reason = when {
            intent == null -> StartReason.STICKY_RESTART
            intent.getBooleanExtra(EXTRA_BOOT, false) -> StartReason.BOOT
            else -> StartReason.APP_OPENED
        }
        // A release build has no scripted drive: it records only the car.
        val requested = (intent?.getStringExtra(EXTRA_SOURCE)?.let(SourceMode::valueOf) ?: mode ?: SourceMode.LIVE)
            .takeIf { it == SourceMode.LIVE || TestBuild.REPLAY } ?: SourceMode.LIVE
        val mirrorChanged = intent?.getStringExtra(EXTRA_MIRROR)?.let { m ->
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            (prefs.getString(PREF_MIRROR, MIRROR_DEFAULT) != m).also { if (it) prefs.edit().putString(PREF_MIRROR, m).apply() }
        } ?: false
        val tierChanged = TestBuild.tier(intent)?.name?.let { t ->
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            (prefs.getString(PREF_TIER, null) != t).also { if (it) prefs.edit().putString(PREF_TIER, t).apply() }
        } ?: false
        if (!promote()) {
            // Not allowed to be foreground (a sticky restart after a boot start has no exemption). Stop cleanly
            // rather than crash-loop; the next start records the gap (STOPPED_WITHOUT_RESTART).
            Log.e(TAG, "foreground not allowed; recording stops until the app is opened or the next boot")
            stopSelf()
            return START_NOT_STICKY
        }
        if (mode != requested || mirrorChanged || tierChanged || intent?.getBooleanExtra(EXTRA_RESTART_REPLAY, false) == true) {
            startSession(requested, reason)
        } else {
            guardGrants()
        }
        TestBuild.airbag(intent, AndroidClock)?.let { inbox.trySend(it) }
        return START_STICKY
    }

    override fun onDestroy() {
        stopSession()
        listOf(pleosVoice, platformVoice, answerListener, offlineListener, beacon).forEach { runCatching { it.close() } }
        super.onDestroy()
    }

    /** `location` type when allowed (needs background location from boot), else specialUse. */
    private fun promote(): Boolean {
        val special = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), special or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "location FGS type refused, trying without location: ${e.message}")
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), special)
                true
            } catch (e2: RuntimeException) {
                Log.e(TAG, "foreground refused: ${e2.message}")
                false
            }
        }
    }

    private fun startSession(next: SourceMode, reason: StartReason) {
        stopSession()
        val dir = AppGraph.chainDir(this, next)
        if (next == SourceMode.REPLAY) dir.deleteRecursively() // each replay is a clean take
        val store = FileEventStore(dir, cipher = recordCipher)
        val seals = SealStore(dir)
        val stream = if (next == SourceMode.REPLAY) MirrorProtocol.STREAM_REPLAY else MirrorProtocol.STREAM_LIVE
        // Without a configured server the car keeps everything locally (the chain is the original either way).
        val url = if (getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_MIRROR, MIRROR_DEFAULT) == MIRROR_LOCAL) LOCAL_MIRROR_URL else BuildConfig.MIRROR_URL
        val channel = url.takeIf { it.isNotEmpty() }?.let { WebSocketMirrorChannel(it, stream, AppGraph.signer) }
        if (channel == null) Log.i(TAG, "no mirror configured; recording locally only") else Log.i(TAG, "mirror: $url")
        val outbox = channel?.let { Outbox(store, seals, it, dir, consent = { DataConsent.agreed(this) }, minuteKey = ::minuteKey) }
        // A built-in camera's clips would be attached here (CarClips with HttpClipUpload, Plus and Pro): no head unit
        // gives apps camera access today, so there is no CameraClipSource to give it.
        val recorder = Recorder(store, seals, AppGraph.signer, AndroidClock) { outbox?.onSeal(it) }
        mirrorChannel = channel
        // The phone connection belongs to the car, not to one chain: one state for both sources.
        val claim = channel?.let { ClaimClient(it, keyRing, java.io.File(filesDir, "identity").apply { mkdirs() }) }
        val shares = channel?.let { CapsuleShares(store, it, dir, MirrorProtocol.deviceId(AppGraph.signer.publicKey), stream, ::minuteKey) }
        var handoverRef: HandoverController? = null
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val library = LibraryController(
            recorder, store, dir, AndroidClock, channel,
            acked = { outbox?.acked ?: -1 },
            holding = { handoverRef?.holding() ?: emptyList() },
            defaultTier = TestBuild.tier(prefs.getString(PREF_TIER, null)) ?: Entitlements.DEFAULT,
        )
        val handover = HandoverController(recorder, store, dir, AndroidClock, channel, allowed = { library.entitlements.handover })
        handoverRef = handover
        mode = next
        inbox = Channel(Channel.UNLIMITED)
        sessionFile = SessionFile(java.io.File(dir, "session"))
        AppGraph.publish(ActiveSession(next, recorder, store, claim, webBase(url), outbox, shares, handover, library, carBase = webBase(url, forPhones = false)))

        val boot = bootCount()
        val started = classifyStart(sessionFile!!.read(), if (next == SourceMode.REPLAY) StartReason.APP_OPENED else reason, boot, AndroidClock.now())
        session = SessionRecord(boot, AndroidClock.now(), cleanStop = false, restartCount = started.restartCount)
        inbox.trySend(ChainEntry.Recorder(started, AndroidClock.now()))
        grants = null
        guardGrants()

        jobs += lifecycleScope.launch(Dispatchers.IO) { consume(recorder) }
        // A real car suspends at ignition off and freezes the app: once it wakes, the time it slept goes into the chain,
        // so the gap reads as "the car was off", not as a recorder failure. (The emulator only simulates suspend and
        // keeps the app running, so it never writes one.)
        jobs += lifecycleScope.launch {
            val sleep = SleepDetector()
            while (isActive) {
                sleep.check(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())
                    ?.let { inbox.send(ChainEntry.Recorder(RecorderEvent.Slept(it), AndroidClock.now())) }
                delay(SLEEP_CHECK_MS)
            }
        }
        channel?.start(lifecycleScope + Dispatchers.IO)
        claim?.let { jobs += it.start(lifecycleScope + Dispatchers.IO) }
        shares?.let { jobs += it.start(lifecycleScope + Dispatchers.IO) }
        jobs += handover.start(lifecycleScope + Dispatchers.IO)
        jobs += library.start(lifecycleScope + Dispatchers.IO)
        // A plan given at launch (testing a car without the cloud); with the cloud, the owner's account decides.
        if (channel == null) TestBuild.tier(prefs.getString(PREF_TIER, null))?.let { t -> lifecycleScope.launch(Dispatchers.IO) { library.overridePlan(t) } }
        outbox?.let { jobs += it.start(lifecycleScope + Dispatchers.IO, recorder) }
        // Consent withdrawn while this chain was not active: its earlier windows are deleted too.
        if (DataConsent.state(this).value == false) outbox?.withdrawConsent()
        voiceJob = VoiceController(
            recorder = recorder,
            send = { inbox.send(it) },
            clock = AndroidClock,
            speech = FallbackSpeechOutput(pleosVoice, platformVoice),
            answers = answerListener,
            offlineListener = offlineListener,
            focus = PlatformAudioFocus(this),
            connectivity = PlatformConnectivity(this),
            assistant = GleoAssistant,
            beacon = beacon,
            text = ::voiceText,
            handoverLines = handover.say,
            handoverPhase = handover.phase,
            libraryNotices = library.notices,
        ).start(lifecycleScope)
        startSources(next)
        Log.i(TAG, "session $next started: $started")
    }

    /** (Re)subscribes the vehicle and location sources; the chain and the session continue. */
    private fun startSources(m: SourceMode) {
        sourceJob?.cancel()
        closeSource?.close()
        closeSource = null
        val (signals, fixes) = sources(m)
        sourceJob = lifecycleScope.launch {
            merge(signals, fixes).collect { inbox.send(it) }
            Log.i(TAG, "$m source finished")
        }
    }

    private fun sources(m: SourceMode): Pair<Flow<ChainEntry>, Flow<ChainEntry>> = when (m) {
        SourceMode.LIVE -> {
            val vehicle = RealVehicleSignalSource(this, AndroidClock).also { closeSource = it }
            val grades = PlatformMotionSource(this).windows().mapNotNull { w ->
                grade.onWindow(w)?.let { ChainEntry.Signal(SignalSample(VehicleSignal.RoadGrade(it), AndroidClock.now())) }
            }
            merge(vehicle.samples().map { ChainEntry.Signal(it) }, grades) to
                PlatformLocationSource(this, AndroidClock).fixes().map { ChainEntry.Location(it) }
        }
        SourceMode.REPLAY -> TestBuild.replay(AndroidClock) ?: (emptyFlow<ChainEntry>() to emptyFlow())
    }

    private suspend fun consume(recorder: Recorder) {
        var lastAliveWrite = 0L
        for (entry in inbox) {
            val event = recorder.record(entry)
            val signal = (entry as? ChainEntry.Signal)?.sample?.signal
            if (signal is VehicleSignal.Speed && mode == SourceMode.LIVE) {
                grade.onSpeed(entry.sample.sourceElapsedNanos ?: entry.sample.receivedAt.elapsedNanos, signal.metersPerSecond)
            }
            if (signal is VehicleSignal.Gear) {
                val wasParked = gear == GearPosition.PARK
                gear = signal.position
                if (signal.position == GearPosition.PARK && !wasParked) guardGrants()
            }
            // Session last-alive at most 1 Hz (the chain itself has the exact time of the last event).
            if (event.entry.at.elapsedNanos - lastAliveWrite > 1_000_000_000L) {
                lastAliveWrite = event.entry.at.elapsedNanos
                session = session?.copy(lastAlive = event.entry.at)?.also { sessionFile?.write(it) }
            }
        }
    }

    /** Grant guard: every start records what is missing; while running, changes on each shift into Park. */
    private fun guardGrants() {
        val now = recorderGrants()
        val check = checkGrants(grants, now)
        check.events.forEach { inbox.trySend(ChainEntry.Recorder(it, AndroidClock.now())) }
        AppGraph.publishMissing(check.missing)
        grants = now
        // A restored grant (speed, location) only takes effect once its source subscribes again.
        if (check.events.any { it is RecorderEvent.PermissionRestored }) mode?.let { startSources(it) }
    }

    private fun stopSession() {
        if (mode == null) return
        voiceJob?.cancel()
        voiceJob = null
        mirrorChannel?.close()
        mirrorChannel = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        sourceJob?.cancel()
        sourceJob = null
        closeSource?.close()
        closeSource = null
        val recorder = AppGraph.session.value?.recorder
        val dir = AppGraph.chainDir(this, mode!!)
        // A deliberate stop is recorded, so the next start's gap is CLEAN_STOP rather than a crash.
        recorder?.let { r ->
            kotlinx.coroutines.runBlocking { r.record(ChainEntry.Recorder(RecorderEvent.RecordingStopped, AndroidClock.now())) }
        }
        session?.let { sessionFile?.write(it.copy(lastAlive = AndroidClock.now(), cleanStop = true)) }
        AppGraph.session.value?.store?.close()
        AppGraph.publish(null)
        Log.i(TAG, "session $mode stopped (${dir.name})")
        mode = null
    }

    private fun voiceText(line: VoiceLine): String = getString(voiceLineText(line))

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle(getString(R.string.notification_title))
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_BOOT = "boot"
        const val EXTRA_RESTART_REPLAY = "restart_replay"
        const val EXTRA_MIRROR = "mirror"


        /** The mirror on the emulator's host computer (`./gradlew :server:run`); the only address allowed without TLS. */
        const val MIRROR_LOCAL = "local"
        const val MIRROR_DEFAULT = "default"
        private const val LOCAL_MIRROR_URL = "ws://10.0.2.2:8080/v1/stream"
        private const val PREFS = "recorder"
        private const val PREF_MIRROR = "mirror"
        private const val PREF_TIER = "tier"
        private const val SLEEP_CHECK_MS = 5_000L

        fun start(context: Context, source: SourceMode? = null, restartReplay: Boolean = false, mirror: String? = null, extras: android.os.Bundle? = null) {
            context.startForegroundService(
                Intent(context, RecorderService::class.java)
                    .apply { source?.let { putExtra(EXTRA_SOURCE, it.name) } }
                    .apply { mirror?.let { putExtra(EXTRA_MIRROR, it) } }
                    .apply { extras?.let(::putExtras) }
                    .putExtra(EXTRA_RESTART_REPLAY, restartReplay),
            )
        }
    }
}

/** BOOT_COMPLETED for the driver's user starts live recording. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        context.startForegroundService(
            Intent(context, RecorderService::class.java)
                .putExtra(RecorderService.EXTRA_SOURCE, SourceMode.LIVE.name)
                .putExtra(RecorderService.EXTRA_BOOT, true),
        )
    }
}
