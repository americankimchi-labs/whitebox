package com.americankimchilabs.whitebox.vehicle.pleos

import ai.pleos.playground.navi.constants.CameraType
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteStartInfo
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
 * Road context from the navigation through the Pleos NaviHelper SDK: the current speed limit, the caution points
 * (school zone, downhill, wrong-way alert, …) and enforcement cameras ahead, the nearest speed enforcement with its
 * limit and distance (handover mode judges speeding there), and the route (guiding, re-routed, arrived), as changes
 * only ([RoadContextChanges]). Listening needs no permission: the NAVI_* permissions guard route requests and queries,
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

            override fun onCameraAlert(cameraAlerts: List<CameraAlert>) {
                send(changes.list(RoadContextType.ENFORCEMENT_CAMERA, cameraAlerts.associate { it.id to it.type.name }))
                send(changes.enforcement(nearestEnforcement(cameraAlerts)))
            }

            override fun onRouteStarted(info: RouteStartInfo) = send(changes.route("GUIDING"))
            override fun onRoutingCompleted() = send(changes.route("GUIDING"))
            override fun onReRouteEnded() = send(changes.route("REROUTED"))
            override fun onDestinationArrived(info: DestinationArrivedInfo) = send(changes.route("ARRIVED"))
            override fun onRouteCancelled() = send(changes.route("CANCELLED"))
            override fun onNavigationStatus(status: NavigationStatus) = when (status) {
                NavigationStatus.ROUTE_DRIVE -> send(changes.route("GUIDING"))
                NavigationStatus.FREE_DRIVE, NavigationStatus.INACTIVE -> send(changes.route("NONE"))
                else -> Unit
            }

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

/** Speed-enforcement camera types: a fixed point, and the parts of an enforced section (구간단속). */
private val POINT_CAMERAS = setOf(
    CameraType.SPEED_CAMERA_FIX, CameraType.SPEED_CAMERA_MOV, CameraType.SPEED_CAMERA_TRAFFIC_LIGHT, CameraType.SPEED_CAMERA_REAR,
    CameraType.VARIABLE_SPEED_CAMERA, CameraType.SPEED_CAMERA_SCHOOL_ZONE, CameraType.SPEED_CAMERA_SCHOOL_ZONE_TRAFFIC_LIGHT,
)
private val SECTION_CAMERAS = setOf(CameraType.SPEED_CAMERA_SECTION_START, CameraType.SECTION_SPEED_ALERT, CameraType.SPEED_CAMERA_SECTION_END)

/** The speed enforcement that matters now: an enforced section the car is in, else the nearest speed camera. */
internal fun nearestEnforcement(alerts: List<CameraAlert>): Enforcement? {
    val section = alerts.filter { it.type in SECTION_CAMERAS && it.speedLimit > 0 }.minByOrNull { it.remainDistance }
    if (section != null) return Enforcement(true, section.speedLimit, section.sectionAvrSpeed, section.remainDistance)
    return alerts.filter { it.type in POINT_CAMERAS && it.speedLimit > 0 }.minByOrNull { it.remainDistance }
        ?.let { Enforcement(false, it.speedLimit, 0, it.remainDistance) }
}

/** One speed enforcement ahead: km/h, and metres to the camera or the section's end. */
internal data class Enforcement(val section: Boolean, val limit: Int, val average: Int, val remainingM: Int)

/**
 * Turns the navigation's repeated reports into change events: a new speed limit; each newly announced caution
 * point or camera (by id, so one passed twice is announced twice); "NONE" when a non-empty list empties.
 */
internal class RoadContextChanges {
    private var speedLimit: Int? = null
    private val active = HashMap<RoadContextType, Set<String>>()

    private var enforcement: String? = null
    private var route: String? = null

    /**
     * The nearest enforcement as `kind;limit;average;remaining`, distance rounded to 10 m so a steady approach is a few
     * records, not one per report; "NONE" once none is left.
     */
    @Synchronized
    fun enforcement(e: Enforcement?): List<VehicleSignal.RoadContext> {
        val value = e?.let { "${if (it.section) "SECTION" else "POINT"};${it.limit};${it.average};${it.remainingM / 10 * 10}" }
            ?: if (enforcement != null) "NONE" else return emptyList()
        if (value == enforcement || (value == "NONE" && enforcement == null)) return emptyList()
        enforcement = if (value == "NONE") null else value
        return listOf(VehicleSignal.RoadContext(RoadContextType.SPEED_ENFORCEMENT, value))
    }

    /** Route state changes; a re-route is an event every time it happens (the route itself is still being guided). */
    @Synchronized
    fun route(state: String): List<VehicleSignal.RoadContext> {
        if (state == "REROUTED") {
            route = "GUIDING"
            return listOf(VehicleSignal.RoadContext(RoadContextType.ROUTE, state))
        }
        if (state == route) return emptyList()
        route = state
        return listOf(VehicleSignal.RoadContext(RoadContextType.ROUTE, state))
    }

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
