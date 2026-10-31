package com.americankimchilabs.whitebox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.americankimchilabs.whitebox.R
import com.americankimchilabs.whitebox.data.CapsuleCheck
import com.americankimchilabs.whitebox.data.CapsuleSeal
import com.americankimchilabs.whitebox.data.verifyCapsule
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.EvidenceTimeline
import com.americankimchilabs.whitebox.domain.LanePoint
import com.americankimchilabs.whitebox.domain.LaneStep
import com.americankimchilabs.whitebox.domain.LocationFix
import com.americankimchilabs.whitebox.domain.MeasureUnit
import com.americankimchilabs.whitebox.domain.TurnSignalState
import com.americankimchilabs.whitebox.domain.VehicleSignal
import com.americankimchilabs.whitebox.recorder.ActiveSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.math.abs

/** Everything the one-page packet shows, computed from the sealed chain range. */
private class Packet(
    val causeWallMillis: Long,
    val triggered: EscalationEvent.Triggered?,
    /** Speed before and after the trigger's measured change: post = speed at the cause sample, pre = post − ΔV. */
    val speedBefore: Float?,
    val speedAfter: Float?,
    val state: LiveState,
    val location: LocationFix?,
    val timeline: EvidenceTimeline,
    val eventCount: Int,
    val check: CapsuleCheck,
)

private fun buildPacket(seal: CapsuleSeal, session: ActiveSession): Packet {
    val events = session.store.read(seal.capsule.firstSeq, seal.capsule.lastSeq)
    val escalations = events.mapNotNull { (it.entry as? ChainEntry.Escalation)?.event }
    // The trigger that names the capsule (a crash escalates a near-miss capsule), else its first.
    val triggers = escalations.filterIsInstance<EscalationEvent.Triggered>()
    val triggered = triggers.lastOrNull { it.trigger == seal.capsule.trigger } ?: triggers.firstOrNull()
    val causeSeq = triggered?.causeSeq
        ?: escalations.filterIsInstance<EscalationEvent.LevelChanged>().firstOrNull { it.level == EscalationLevel.ALERT }?.causeSeq
        ?: seal.capsule.lastSeq
    val upToCause = events.filter { it.seq <= causeSeq }
    var state = LiveState()
    upToCause.forEach { state = state.with(it.entry) }
    val after = state.kmh
    return Packet(
        causeWallMillis = upToCause.last().entry.at.wallMillis,
        triggered = triggered,
        speedBefore = if (after != null && triggered?.trigger?.unit == MeasureUnit.DELTA_KMH) after - triggered.measured else null,
        speedAfter = after,
        state = state,
        location = upToCause.mapNotNull { (it.entry as? ChainEntry.Location)?.fix }.lastOrNull(),
        timeline = EvidenceTimeline.build(events, causeSeq),
        eventCount = events.size,
        check = verifyCapsule(seal, session.store),
    )
}

@Composable
fun EvidencePacket(labels: Labels, seal: CapsuleSeal, session: ActiveSession, onBack: () -> Unit) {
    var packet by remember(seal) { mutableStateOf<Packet?>(null) }
    LaunchedEffect(seal) { packet = withContext(Dispatchers.IO) { buildPacket(seal, session) } }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.packet_back)) }
            Text("  " + stringResource(R.string.packet_title), style = MaterialTheme.typography.headlineSmall)
        }
        val p = packet ?: run {
            Text(stringResource(R.string.packet_verifying), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
            return@Column
        }
        // Each block is a rotary stop, so turning the knob walks down the page and the scroll follows.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Column(Modifier.weight(1f).ccpRow().padding(8.dp)) { Summary(labels, seal, p) }
                Column(Modifier.weight(1f).ccpRow().padding(8.dp)) {
                    Section(stringResource(R.string.packet_lines))
                    if (p.timeline.lines.isEmpty()) Line(stringResource(R.string.packet_no_data))
                    p.timeline.lines.forEach { Line(labels.controlLine(it)) }
                    Section(stringResource(R.string.packet_context), top = 28.dp)
                    val context = labels.context(p.timeline.context)
                    if (context.isEmpty()) Line(stringResource(R.string.packet_no_data))
                    context.forEach { (k, v) -> Field(k, v, labelWidth = 150.dp, top = 6.dp) }
                }
                Box(Modifier.padding(top = 8.dp).size(220.dp).border(2.dp, Color.Gray), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.packet_qr), color = Color.Gray, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).ccpRow().padding(8.dp)) {
                Section(stringResource(R.string.packet_lanes))
                Lanes(labels, p.timeline)
            }
        }
    }
}

@Composable
private fun Summary(labels: Labels, seal: CapsuleSeal, p: Packet) {
    VerificationBadge(p.check)
    Field(stringResource(R.string.packet_time), labels.dateTime(p.causeWallMillis))
    Field(stringResource(R.string.packet_type), labels.trigger(seal.capsule.trigger))
    Field(
        stringResource(R.string.packet_speed),
        if (p.triggered != null && p.speedBefore != null && p.speedAfter != null) {
            stringResource(
                R.string.packet_speed_transition,
                p.speedBefore, p.speedAfter, p.triggered.measured,
                labels.window(p.triggered.trigger) ?: "",
            )
        } else {
            labels.speed(p.speedAfter)
        },
    )
    Field(
        stringResource(R.string.packet_controls),
        stringResource(
            R.string.packet_controls_value,
            labels.brake(p.state.brake), labels.turn(p.state.turn), labels.gear(p.state.gear), labels.steering(p.state.steering),
        ),
    )
    Field(
        stringResource(R.string.packet_location),
        p.location?.let { stringResource(R.string.packet_location_value, it.latitude, it.longitude, it.accuracyMeters ?: 0f) }
            ?: stringResource(R.string.packet_no_location),
    )
    Field(stringResource(R.string.packet_range), stringResource(R.string.packet_range_value, seal.capsule.firstSeq, seal.capsule.lastSeq, p.eventCount))
    Field(
        stringResource(R.string.packet_seal),
        stringResource(if (seal.final) R.string.packet_seal_final else R.string.packet_seal_provisional, labels.dateTime(seal.sealedAtWallMillis)),
    )
    Field(stringResource(R.string.packet_hash), seal.headHash.hex().take(32) + "…", mono = true)
    Field(stringResource(R.string.packet_key), fingerprint(seal.publicKey), mono = true)
}

@Composable
private fun Section(title: String, top: Dp = 0.dp) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = top, bottom = 4.dp))
}

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun VerificationBadge(check: CapsuleCheck) {
    val (text, color) = when (check) {
        CapsuleCheck.Verified -> stringResource(R.string.packet_verified) to Color(0xFF2E7D32)
        is CapsuleCheck.Failed -> stringResource(R.string.packet_failed, check.reason) to Color(0xFFC62828)
    }
    Text(text, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.background(color).padding(14.dp))
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false, labelWidth: Dp = 170.dp, top: Dp = 12.dp) {
    Row(Modifier.padding(top = top)) {
        Text(label, color = Color.Gray, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(labelWidth))
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

private val LANE_LABEL = 150.dp
private val BrakeColor = Color(0xFFC62828)
private val TurnColor = Color(0xFFF9A825)

/** Window position of [ms] (relative to the cause) across a lane [width] px wide. */
private fun x(ms: Long, width: Float) =
    (ms + EvidenceTimeline.PRE_MS).toFloat() / (EvidenceTimeline.PRE_MS + EvidenceTimeline.POST_MS) * width

/** Consecutive (from, to, value) spans of a step lane, closed at [endMs]. */
private fun <T> spans(steps: List<LaneStep<T>>, endMs: Long) =
    steps.mapIndexedNotNull { i, s ->
        val to = (steps.getOrNull(i + 1)?.atMs ?: endMs).coerceAtMost(endMs)
        if (to > s.atMs) Triple(s.atMs, to, s.value) else null
    }

/**
 * Speed, steering, brake, turn signal and gear on one time axis, with the cause instant marked across all lanes.
 * Shaded = outside the sealed range (no record there).
 */
@Composable
private fun Lanes(labels: Labels, tl: EvidenceTimeline) {
    val measurer = rememberTextMeasurer()
    val axis = MaterialTheme.colorScheme.outline
    val shade = MaterialTheme.colorScheme.surfaceVariant
    val speedColor = MaterialTheme.colorScheme.primary
    val steerColor = MaterialTheme.colorScheme.tertiary
    val text = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    val faint = text.copy(color = Color.Gray)
    val left = stringResource(R.string.lane_left)
    val right = stringResource(R.string.lane_right)
    val cause = stringResource(R.string.lane_cause)

    @Composable
    fun Lane(label: String, height: Dp, draw: DrawScope.() -> Unit) {
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = Color.Gray, modifier = Modifier.width(LANE_LABEL))
            Canvas(Modifier.weight(1f).height(height)) {
                val w = size.width
                if (tl.startMs > -EvidenceTimeline.PRE_MS) drawRect(shade, Offset.Zero, Size(x(tl.startMs, w), size.height))
                if (tl.endMs < EvidenceTimeline.POST_MS) drawRect(shade, Offset(x(tl.endMs, w), 0f), Size(w - x(tl.endMs, w), size.height))
                drawLine(axis, Offset(0f, size.height), Offset(w, size.height), strokeWidth = 1f)
                draw()
                drawLine(BrakeColor, Offset(x(0, w), 0f), Offset(x(0, w), size.height), strokeWidth = 3f)
            }
        }
    }

    fun DrawScope.polyline(points: List<LanePoint>, color: Color, y: (Float) -> Float) =
        points.zipWithNext { a, b -> drawLine(color, Offset(x(a.atMs, size.width), y(a.value)), Offset(x(b.atMs, size.width), y(b.value)), strokeWidth = 4f) }

    // Scale labels sit on a background so a trace passing under them stays readable.
    fun DrawScope.label(s: String, at: Offset, style: TextStyle = faint, back: Color? = null) {
        val m = measurer.measure(s, style)
        if (back != null) drawRect(back, at, Size(m.size.width + 8f, m.size.height.toFloat()))
        drawText(m, topLeft = Offset(at.x + 4f, at.y))
    }
    val background = MaterialTheme.colorScheme.surface

    val maxKmh = (((tl.speedKmh.maxOfOrNull { it.value } ?: 0f) / 20f).toInt() + 1) * 20f
    Lane(stringResource(R.string.lane_speed), 110.dp) {
        polyline(tl.speedKmh, speedColor) { size.height - it / maxKmh * size.height }
        label("%.0f km/h".format(maxKmh), Offset(2f, 0f), back = background)
    }

    val maxDeg = maxOf(90f, tl.steering.maxOfOrNull { abs(it.value) } ?: 0f)
    Lane(stringResource(R.string.lane_steering), 84.dp) {
        val mid = size.height / 2
        drawLine(axis, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
        polyline(tl.steering, steerColor) { mid - it / maxDeg * mid }
        label("+%.0f°".format(maxDeg), Offset(2f, 0f), back = background)
        label("−%.0f°".format(maxDeg), Offset(2f, size.height - 26.dp.toPx()), back = background)
    }

    Lane(stringResource(R.string.lane_brake), 30.dp) {
        spans(tl.brake, tl.endMs).filter { it.third }.forEach { (from, to, _) ->
            drawRect(BrakeColor, Offset(x(from, size.width), 4f), Size(x(to, size.width) - x(from, size.width), size.height - 8f))
        }
    }

    Lane(stringResource(R.string.lane_turn), 30.dp) {
        spans(tl.turn, tl.endMs).filter { it.third != TurnSignalState.OFF }.forEach { (from, to, state) ->
            val x0 = x(from, size.width)
            drawRect(TurnColor, Offset(x0, 4f), Size(x(to, size.width) - x0, size.height - 8f))
            label(if (state == TurnSignalState.LEFT) left else right, Offset(x0 + 6f, 2f), text)
        }
    }

    Lane(stringResource(R.string.lane_gear), 30.dp) {
        spans(tl.gear, tl.endMs).forEach { (from, _, g) ->
            val x0 = x(from, size.width)
            drawLine(axis, Offset(x0, 0f), Offset(x0, size.height), strokeWidth = 2f)
            label(labels.gear(g), Offset(x0 + 6f, 2f), text)
        }
    }

    // Time axis: a tick per second, labelled every 5 s; 0 is the cause.
    Row(Modifier.padding(top = 4.dp)) {
        Spacer(Modifier.width(LANE_LABEL))
        Canvas(Modifier.weight(1f).height(30.dp)) {
            for (sec in -EvidenceTimeline.PRE_MS / 1000..EvidenceTimeline.POST_MS / 1000) {
                val px = x(sec * 1000, size.width)
                drawLine(axis, Offset(px, 0f), Offset(px, if (sec % 5 == 0L) 12f else 6f), strokeWidth = 2f)
                if (sec % 5 == 0L) {
                    val s = if (sec == 0L) cause else "%+d s".format(sec)
                    val m = measurer.measure(s, faint)
                    drawText(m, topLeft = Offset((px - m.size.width / 2f).coerceIn(0f, size.width - m.size.width), 12f))
                }
            }
        }
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

private fun fingerprint(publicKey: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(publicKey).hex().take(16).chunked(4).joinToString(":")
