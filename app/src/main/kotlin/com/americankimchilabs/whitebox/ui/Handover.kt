package com.americankimchilabs.whitebox.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.americankimchilabs.whitebox.R
import com.americankimchilabs.whitebox.data.CapsuleSeal
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.HandoverEvent
import com.americankimchilabs.whitebox.domain.HandoverEventKind
import com.americankimchilabs.whitebox.domain.HandoverPhase
import com.americankimchilabs.whitebox.domain.HandoverSource
import com.americankimchilabs.whitebox.domain.Tuning
import com.americankimchilabs.whitebox.domain.VehicleSignal
import com.americankimchilabs.whitebox.recorder.ActiveSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val HandoverColor = BrandPurple

/**
 * Brings the car's maps app forward so a destination can be set. Only from the visible screen: Android does not let a
 * background service open another app. The system picks the maps app the owner chose as default.
 */
fun openMaps(context: Context) {
    val maps = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(maps)
    } catch (e: ActivityNotFoundException) {
        // A head unit without a default maps app: the car's own navigation, if installed.
        context.packageManager.getLaunchIntentForPackage(PLEOS_NAVIGATION)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            ?: Log.i("WhiteBox/Handover", "no maps app to open")
    }
}

private const val PLEOS_NAVIGATION = "ai.umos.maps.android.navigation.app"

/** While driving: one read-only line, nothing to press. */
@Composable
fun HandoverDrivingBanner(phase: HandoverPhase) {
    if (phase == HandoverPhase.ACTIVE) {
        Text(
            stringResource(R.string.handover_driving), color = Color.White, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().background(HandoverColor, RoundedCornerShape(8.dp)).padding(12.dp),
        )
    }
}

/** Parked: the way in (off), or where the trip stands; the hired driver cannot turn it off once the trip started. */
@Composable
fun HandoverBar(session: ActiveSession, phase: HandoverPhase, since: Long, onOpen: () -> Unit) {
    val handover = session.handover ?: return
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    when (phase) {
        HandoverPhase.OFF -> Unit // the button sits at the end of the status header (HandoverButton)
        else -> Row(
            Modifier.fillMaxWidth().background(HandoverColor, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val text = when (phase) {
                HandoverPhase.ARMED -> stringResource(R.string.handover_armed)
                HandoverPhase.ACTIVE -> stringResource(R.string.handover_active_parked)
                else -> stringResource(R.string.handover_ending, ((since + handover.graceMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1)
            }
            Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (phase == HandoverPhase.ARMED) {
                OutlinedButton(contentPadding = BigPadding, onClick = { openMaps(context) }, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.handover_set_destination), color = Color.White) }
                OutlinedButton(contentPadding = BigPadding, onClick = { scope.launch(Dispatchers.IO) { handover.cancelOnCar() } }, modifier = Modifier.ccpFocus(CircleShape)) {
                    Text(stringResource(R.string.handover_cancel), color = Color.White)
                }
            }
        }
    }
}

/** Opens [HandoverSheet]; at the right end of the status header while parked and handover mode is off. */
@Composable
fun HandoverButton(onOpen: () -> Unit) {
    OutlinedButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp), modifier = Modifier.ccpFocus(CircleShape)) {
        Text(stringResource(R.string.handover_button), style = MaterialTheme.typography.titleMedium)
    }
}

/** What handover mode does, said plainly before it is turned on (parked). */
@Composable
fun HandoverSheet(session: ActiveSession, onClose: () -> Unit) {
    val handover = session.handover ?: return
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.handover_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        listOf(R.string.handover_what, R.string.handover_share, R.string.handover_rules, R.string.handover_end, R.string.handover_end_lock, R.string.handover_destination)
            .forEach { Bullet(stringResource(it)) }
        if (!handover.allowed()) {
            // Not in the owner's plan: said plainly, with where to change it; no sales button on the car.
            Text(stringResource(R.string.handover_plan_needed), style = MaterialTheme.typography.titleMedium, color = HandoverColor)
            Text(stringResource(R.string.handover_plan_phone).bySentence(), style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
            OutlinedButton(contentPadding = BigPadding, onClick = onClose, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.handover_close)) }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(contentPadding = BigPadding, onClick = { scope.launch(Dispatchers.IO) { handover.armOnCar() }; onClose() }, modifier = Modifier.ccpFocus(CircleShape)) {
                Text(stringResource(R.string.handover_turn_on), style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(contentPadding = BigPadding, onClick = { openMaps(context) }, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.handover_set_destination)) }
            OutlinedButton(contentPadding = BigPadding, onClick = onClose, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.handover_close)) }
        }
    }
}

/** A handover trip's report on the car: the summary, every event with its time, the speed over the trip, the QR. */
@Composable
fun TripReport(labels: Labels, seal: CapsuleSeal, session: ActiveSession, onBack: () -> Unit) {
    var trip by remember(seal) { mutableStateOf<Trip?>(null) }
    var link by remember(seal) { mutableStateOf<String?>(null) }
    LaunchedEffect(seal) {
        trip = withContext(Dispatchers.IO) { Trip.of(session.store.read(seal.capsule.firstSeq, seal.capsule.lastSeq).map { it.entry }) }
        link = withContext(Dispatchers.IO) { session.webBase?.let { session.shares?.link(seal.capsule, it) } }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text("  " + stringResource(R.string.trigger_handover) + if (seal.final) "" else stringResource(R.string.handover_report_provisional), style = MaterialTheme.typography.headlineSmall)
        }
        val t = trip ?: run { Text(stringResource(R.string.packet_verifying), modifier = Modifier.padding(16.dp)); return@Column }
        Row(Modifier.padding(top = 16.dp).verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f).ccpRow().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ReportRow(stringResource(R.string.handover_report_time), "${labels.time(t.startWall)} – ${labels.time(t.endWall)}")
                ReportRow(stringResource(R.string.handover_report_duration), stringResource(R.string.handover_minutes, ((t.endWall - t.startWall) / 60_000).coerceAtLeast(1)))
                t.km?.let { ReportRow(stringResource(R.string.handover_report_distance), "%.1f km".format(it)) }
                ReportRow(stringResource(R.string.handover_report_top_speed), "%.0f km/h".format(t.topKmh))
                t.armedBy?.let { ReportRow(stringResource(R.string.handover_report_armed), labels.source(it)) }
                t.ended?.let { ReportRow(stringResource(R.string.handover_report_ended), labels.endReason(it.reason)) }
                Text(stringResource(R.string.handover_report_events, t.events.size), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                if (t.events.isEmpty()) Text(stringResource(R.string.handover_report_none).bySentence(), style = MaterialTheme.typography.bodyLarge)
                t.events.forEach { (wall, e) -> Text("${labels.time(wall)}  ${labels.handoverEvent(e)}", style = MaterialTheme.typography.bodyLarge) }
                SpeedOverTrip(t)
            }
            Column(Modifier.width(220.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val l = link
                if (l != null) {
                    Image(remember(l) { qr(l, 440) }, contentDescription = stringResource(R.string.handover_report_qr), modifier = Modifier.size(220.dp))
                    Text(stringResource(R.string.handover_report_qr).bySentence(), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.packet_qr_none).bySentence(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ReportRow(label: String, value: String) {
    Row {
        Text(label, color = Color.Gray, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(150.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Speed over the whole trip with a mark at each event. */
@Composable
private fun SpeedOverTrip(t: Trip) {
    if (t.speeds.size < 2) return
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(top = 12.dp)) {
        val span = (t.endWall - t.startWall).coerceAtLeast(1).toFloat()
        val vmax = maxOf(60f, t.topKmh)
        // Subtract in Long: a wall clock in a Float is only good to about two minutes.
        fun x(w: Long) = (w - t.startWall).toFloat() / span * size.width
        fun y(v: Float) = size.height - v / vmax * size.height
        drawRect(Color(0x11000000), size = size)
        t.events.forEach { (w, _) -> drawLine(Color(0xFFE65100), Offset(x(w), 0f), Offset(x(w), size.height), strokeWidth = 3f) }
        val path = Path()
        t.speeds.forEachIndexed { i, (w, v) -> if (i == 0) path.moveTo(x(w), y(v)) else path.lineTo(x(w), y(v)) }
        drawPath(path, HandoverColor, style = Stroke(width = 3f))
    }
}

/** A trip's figures from its sealed records. */
class Trip(
    val startWall: Long,
    val endWall: Long,
    val topKmh: Float,
    val km: Float?,
    val armedBy: HandoverSource?,
    val ended: HandoverEvent.Ended?,
    val events: List<Pair<Long, HandoverEvent.Observed>>,
    val speeds: List<Pair<Long, Float>>,
) {
    companion object {
        fun of(entries: List<ChainEntry>): Trip {
            val speeds = entries.mapNotNull { e -> ((e as? ChainEntry.Signal)?.sample?.signal as? VehicleSignal.Speed)?.let { e.at.wallMillis to it.metersPerSecond * 3.6f } }
            val odo = entries.mapNotNull { ((it as? ChainEntry.Signal)?.sample?.signal as? VehicleSignal.Odometer)?.distance }
            val handover = entries.filterIsInstance<ChainEntry.Handover>()
            var km = 0f
            for (i in 1 until speeds.size) km += (speeds[i - 1].second + speeds[i].second) / 2 * (speeds[i].first - speeds[i - 1].first) / 3_600_000f
            return Trip(
                startWall = entries.first().at.wallMillis,
                endWall = entries.last().at.wallMillis,
                topKmh = speeds.maxOfOrNull { it.second } ?: 0f,
                // The odometer when the car reports it (Korean cars: km), else the speed integrated over time.
                km = if (odo.size >= 2) odo.last() - odo.first() else km.takeIf { speeds.size > 1 },
                armedBy = handover.firstNotNullOfOrNull { (it.event as? HandoverEvent.Started)?.by },
                ended = handover.lastOrNull { it.event is HandoverEvent.Ended }?.event as? HandoverEvent.Ended,
                events = handover.mapNotNull { h -> (h.event as? HandoverEvent.Observed)?.takeIf { it.kind != HandoverEventKind.NO_ROUTE }?.let { h.at.wallMillis to it } },
                speeds = speeds,
            )
        }
    }
}
