package com.americankimchilabs.whitebox.vehicle.pleos

import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.core.VehicleSignalSource
import com.americankimchilabs.whitebox.domain.RoadContextType
import com.americankimchilabs.whitebox.domain.SignalKind
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.VehicleSignal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable

private const val TAG = "WhiteBox/Navi"

/** A navigation service that has not answered by then is treated as absent (it never blocks recording). */
private const val CONNECT_TIMEOUT_MS = 5_000L

/**
 * Road context from the navigation through the Pleos NaviHelper SDK: the current speed limit, and the caution
 * points (school zone, downhill, wrong-way alert, …) and enforcement cameras ahead, as changes only
 * ([RoadContextChanges]). Listening needs no permission: the NAVI_* permissions guard route requests and queries,
 * which this source never makes.
 *
 * Only navigation-equipped cars have the navigation service; elsewhere [supportedKinds] is empty. On the Pleos Connect
 * emulator the navigation position does not follow the simulated drive, so the speed limit stays 0 and no caution
 * point or camera is announced; the change tracking is covered by unit tests instead.
 */
class NaviRoadContextSource(context: Context, private val clock: Clock) : VehicleSignalSource, Closeable {

    private val navi = NaviHelper(context.applicationContext)
    private val ready = CompletableDeferred<Boolean>()
    private var initStarted = false

    private fun connect(): CompletableDeferred<Boolean> {
        synchronized(this) {
            if (!initStarted) {
                initStarted = true
                navi.initialize({ ready.complete(true) }, { e ->
                    Log.i(TAG, "navigation unavailable: ${e.message}")
                    ready.complete(false)
                })
            }
        }
        return ready
    }

    private suspend fun connected() = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connect().await() } ?: false

    override suspend fun supportedKinds(): Set<SignalKind> = if (connected()) setOf(SignalKind.ROAD_CONTEXT) else emptySet()

    override fun samples(): Flow<SignalSample> = callbackFlow {
        if (!connected()) {
            Log.i(TAG, "no navigation; road context off")
            close()
            return@callbackFlow
        }
        val changes = RoadContextChanges()
        fun send(signals: List<VehicleSignal.RoadContext>) {
            if (signals.isEmpty()) return
            val receivedAt = clock.now()
            signals.forEach { trySendBlocking(SignalSample(it, receivedAt)) }
        }
        val listener = object : NaviHelperEventListener {
            override fun onCurrentRoadSpeedLimit(speedLimit: Int) = send(changes.speedLimit(speedLimit))

            override fun onCameraAlert(cameraAlerts: List<CameraAlert>) =
                send(changes.list(RoadContextType.ENFORCEMENT_CAMERA, cameraAlerts.associate { it.id to it.type.name }))

            override fun onCautionPointInfo(cautionPoints: List<CautionPoint>) =
                send(changes.list(RoadContextType.CAUTION_POINT, cautionPoints.associate { it.id to it.type.name }))
        }
        navi.addListener(listener)
        awaitClose { navi.removeListener(listener) }
    }

    override fun close() {
        if (initStarted) navi.release()
    }
}

/**
 * Turns the navigation's repeated reports into change events: a new speed limit; each newly announced caution
 * point or camera (by id, so one passed twice is announced twice); "NONE" when a non-empty list empties.
 */
internal class RoadContextChanges {
    private var speedLimit: Int? = null
    private val active = HashMap<RoadContextType, Set<String>>()

    @Synchronized
    fun speedLimit(kmh: Int): List<VehicleSignal.RoadContext> {
        if (kmh == speedLimit) return emptyList()
        speedLimit = kmh
        return listOf(VehicleSignal.RoadContext(RoadContextType.SPEED_LIMIT, kmh.toString()))
    }

    /** [items] maps each announced id to its type name. */
    @Synchronized
    fun list(type: RoadContextType, items: Map<String, String>): List<VehicleSignal.RoadContext> {
        val before = active[type].orEmpty()
        active[type] = items.keys
        if (items.isEmpty()) return if (before.isNotEmpty()) listOf(VehicleSignal.RoadContext(type, "NONE")) else emptyList()
        return items.filterKeys { it !in before }.values.distinct().map { VehicleSignal.RoadContext(type, it) }
    }
}
