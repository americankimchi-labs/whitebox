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
import com.americankimchilabs.whitebox.R
import com.americankimchilabs.whitebox.data.FileEventStore
import com.americankimchilabs.whitebox.data.Recorder
import com.americankimchilabs.whitebox.data.SealStore
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.GearPosition
import com.americankimchilabs.whitebox.domain.RecorderEvent
import com.americankimchilabs.whitebox.domain.RecorderPermission
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.SessionRecord
import com.americankimchilabs.whitebox.domain.StartReason
import com.americankimchilabs.whitebox.domain.VehicleSignal
import com.americankimchilabs.whitebox.domain.VoiceLine
import com.americankimchilabs.whitebox.domain.checkGrants
import com.americankimchilabs.whitebox.domain.classifyStart
import com.americankimchilabs.whitebox.platform.AndroidClock
import com.americankimchilabs.whitebox.platform.FallbackSpeechOutput
import com.americankimchilabs.whitebox.platform.PlatformAudioBeacon
import com.americankimchilabs.whitebox.platform.PlatformAudioFocus
import com.americankimchilabs.whitebox.platform.PlatformConnectivity
import com.americankimchilabs.whitebox.platform.PlatformSpeechOutput
import com.americankimchilabs.whitebox.platform.LoggingMirror
import com.americankimchilabs.whitebox.platform.PlatformLocationSource
import com.americankimchilabs.whitebox.platform.PlatformMotionSource
import com.americankimchilabs.whitebox.platform.RoadGradeTracker
import com.americankimchilabs.whitebox.platform.SessionFile
import com.americankimchilabs.whitebox.platform.bootCount
import com.americankimchilabs.whitebox.platform.recorderGrants
import com.americankimchilabs.whitebox.ui.voiceLineText
import com.americankimchilabs.whitebox.vehicle.fake.DemoDrive
import com.americankimchilabs.whitebox.vehicle.fake.ScriptedLocationSource
import com.americankimchilabs.whitebox.vehicle.fake.ScriptedSignalSource
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
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
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

    /** Speech objects live as long as the service: the car's speech service drops callbacks once a client is gone. */
    private val credentials by lazy {
        BuildConfig.PLEOS_CLIENT_ID.takeIf { it.isNotEmpty() }?.let { PleosCredentials(it, BuildConfig.PLEOS_CLIENT_SECRET) }
    }
    private val pleosVoice by lazy { PleosSpeechOutput(this, credentials) }
    private val platformVoice by lazy { PlatformSpeechOutput(this) }
    private val answerListener by lazy { PleosPhraseListener(this, onDeviceOnly = false, credentials) }
    private val offlineListener by lazy { PleosPhraseListener(this, onDeviceOnly = true, credentials) }
    private val beacon by lazy { PlatformAudioBeacon() }

    /** Road grade from the head unit's accelerometer (live only); its learned mounting survives restarts. */
    private val grade by lazy { RoadGradeTracker(java.io.File(filesDir, "motion-mount")) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val reason = when {
            intent == null -> StartReason.STICKY_RESTART
            intent.getBooleanExtra(EXTRA_BOOT, false) -> StartReason.BOOT
            else -> StartReason.APP_OPENED
        }
        val requested = intent?.getStringExtra(EXTRA_SOURCE)?.let(SourceMode::valueOf) ?: mode ?: SourceMode.LIVE
        if (!promote()) {
            // Not allowed to be foreground (a sticky restart after a boot start has no exemption). Stop cleanly
            // rather than crash-loop; the next start records the gap (STOPPED_WITHOUT_RESTART).
            Log.e(TAG, "foreground not allowed; recording stops until the app is opened or the next boot")
            stopSelf()
            return START_NOT_STICKY
        }
        if (mode != requested || intent?.getBooleanExtra(EXTRA_RESTART_REPLAY, false) == true) {
            startSession(requested, reason)
        } else {
            guardGrants()
        }
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
        val store = FileEventStore(dir)
        val recorder = Recorder(store, SealStore(dir), AppGraph.signer, AndroidClock, LoggingMirror)
        mode = next
        inbox = Channel(Channel.UNLIMITED)
        sessionFile = SessionFile(java.io.File(dir, "session"))
        AppGraph.publish(ActiveSession(next, recorder, store))

        val boot = bootCount()
        val started = classifyStart(sessionFile!!.read(), if (next == SourceMode.REPLAY) StartReason.APP_OPENED else reason, boot, AndroidClock.now())
        session = SessionRecord(boot, AndroidClock.now(), cleanStop = false, restartCount = started.restartCount)
        inbox.trySend(ChainEntry.Recorder(started, AndroidClock.now()))
        grants = null
        guardGrants()

        jobs += lifecycleScope.launch(Dispatchers.IO) { consume(recorder) }
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
        SourceMode.REPLAY -> ScriptedSignalSource(DemoDrive.signals(), AndroidClock).samples().map { ChainEntry.Signal(it) } to
            ScriptedLocationSource(DemoDrive.locations(), AndroidClock).fixes().map { ChainEntry.Location(it) }
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

        fun start(context: Context, source: SourceMode? = null, restartReplay: Boolean = false) {
            context.startForegroundService(
                Intent(context, RecorderService::class.java)
                    .apply { source?.let { putExtra(EXTRA_SOURCE, it.name) } }
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
