package com.americankimchilabs.whitebox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.americankimchilabs.whitebox.R
import com.americankimchilabs.whitebox.data.CapsuleCheck
import com.americankimchilabs.whitebox.data.CapsuleSeal
import com.americankimchilabs.whitebox.data.verifyCapsule
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.LocationFix
import com.americankimchilabs.whitebox.domain.MeasureUnit
import com.americankimchilabs.whitebox.domain.VehicleSignal
import com.americankimchilabs.whitebox.recorder.ActiveSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Everything the one-page packet shows, computed from the sealed chain range. */
private class Packet(
    val causeWallMillis: Long,
    val triggered: EscalationEvent.Triggered?,
    /** Speed before and after the trigger's measured change: post = speed at the cause sample, pre = post − ΔV. */
    val speedBefore: Float?,
    val speedAfter: Float?,
    val state: LiveState,
    val location: LocationFix?,
    val speedTrace: List<Pair<Long, Float>>, // (ms relative to the cause, km/h), the 10 s before and 3 s after
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
    val causeNs = upToCause.last().entry.at.elapsedNanos
    val trace = events.mapNotNull { e ->
        val s = (e.entry as? ChainEntry.Signal)?.sample?.signal as? VehicleSignal.Speed ?: return@mapNotNull null
        val rel = (e.entry.at.elapsedNanos - causeNs) / 1_000_000
        if (rel in -10_000..3_000) rel to s.metersPerSecond * 3.6f else null
    }
    val after = state.kmh
    return Packet(
        causeWallMillis = upToCause.last().entry.at.wallMillis,
        triggered = triggered,
        speedBefore = if (after != null && triggered?.trigger?.unit == MeasureUnit.DELTA_KMH) after - triggered.measured else null,
        speedAfter = after,
        state = state,
        location = upToCause.mapNotNull { (it.entry as? ChainEntry.Location)?.fix }.lastOrNull(),
        speedTrace = trace,
        eventCount = events.size,
        check = verifyCapsule(seal, session.store),
    )
}

@Composable
fun EvidencePacket(labels: Labels, seal: CapsuleSeal, session: ActiveSession, onBack: () -> Unit) {
    var packet by remember(seal) { mutableStateOf<Packet?>(null) }
    LaunchedEffect(seal) { packet = withContext(Dispatchers.IO) { buildPacket(seal, session) } }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.packet_back)) }
            Text("  " + stringResource(R.string.packet_title), style = MaterialTheme.typography.headlineSmall)
        }
        val p = packet ?: run {
            Text(stringResource(R.string.packet_verifying), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
            return@Column
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f)) {
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(220.dp).border(2.dp, Color.Gray), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.packet_qr), color = Color.Gray, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.packet_trace), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 20.dp))
                SpeedTrace(p.speedTrace)
            }
        }
    }
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
private fun Field(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.padding(top = 12.dp)) {
        Text(label, color = Color.Gray, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(170.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

@Composable
private fun SpeedTrace(points: List<Pair<Long, Float>>) {
    val axis = MaterialTheme.colorScheme.outline
    val line = MaterialTheme.colorScheme.primary
    Canvas(Modifier.width(420.dp).height(170.dp).padding(top = 8.dp)) {
        if (points.size < 2) return@Canvas
        val maxV = points.maxOf { it.second }.coerceAtLeast(10f)
        fun x(ms: Long) = (ms + 10_000f) / 13_000f * size.width
        fun y(v: Float) = size.height - v / maxV * size.height
        drawLine(axis, Offset(x(0), 0f), Offset(x(0), size.height), strokeWidth = 2f) // the cause instant
        drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        points.zipWithNext { a, b -> drawLine(line, Offset(x(a.first), y(a.second)), Offset(x(b.first), y(b.second)), strokeWidth = 4f) }
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

private fun fingerprint(publicKey: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(publicKey).hex().take(16).chunked(4).joinToString(":")
