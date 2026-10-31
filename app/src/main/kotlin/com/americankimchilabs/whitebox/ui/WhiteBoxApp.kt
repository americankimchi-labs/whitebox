package com.americankimchilabs.whitebox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.americankimchilabs.whitebox.R
import com.americankimchilabs.whitebox.data.CapsuleSeal
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.DriveEvent
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.GearPosition
import com.americankimchilabs.whitebox.domain.RecorderPermission
import com.americankimchilabs.whitebox.domain.TurnSignalState
import com.americankimchilabs.whitebox.domain.VehicleSignal
import com.americankimchilabs.whitebox.domain.regrantPromptAllowed
import com.americankimchilabs.whitebox.platform.recorderGrants
import com.americankimchilabs.whitebox.recorder.ActiveSession
import com.americankimchilabs.whitebox.platform.DataConsent
import com.americankimchilabs.whitebox.recorder.AppGraph
import com.americankimchilabs.whitebox.recorder.SourceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Latest value of each signal, for the status header. */
data class LiveState(
    val gear: GearPosition? = null,
    val kmh: Float? = null,
    val brake: Boolean? = null,
    val turn: TurnSignalState? = null,
    val steering: Float? = null,
) {
    fun with(e: ChainEntry): LiveState = when (val s = (e as? ChainEntry.Signal)?.sample?.signal) {
        is VehicleSignal.Gear -> copy(gear = s.position)
        is VehicleSignal.Speed -> copy(kmh = s.metersPerSecond * 3.6f)
        is VehicleSignal.BrakePedal -> copy(brake = s.pressed)
        is VehicleSignal.TurnSignal -> copy(turn = s.state)
        is VehicleSignal.SteeringAngle -> copy(steering = s.degrees)
        else -> this
    }
}

private const val TIMELINE_ROWS = 200

private enum class SetupMode { FIRST_RUN, REGRANT }

@Composable
fun WhiteBoxApp(session: ActiveSession?, onSource: (SourceMode) -> Unit, onGrantsChanged: () -> Unit) {
    val context = LocalContext.current
    val labels = remember(context) { Labels(context.resources) }
    if (session == null) {
        Centered(stringResource(R.string.starting))
        return
    }
    val recorder = session.recorder
    var live by remember(session) { mutableStateOf(LiveState()) }
    val rows = remember(session) { mutableStateListOf<DriveEvent>() }

    LaunchedEffect(session) {
        // Seed from the chain so opening the app mid-drive shows the current state, then follow live.
        val filter = RowFilter()
        val head = withContext(Dispatchers.IO) { session.store.head() }
        if (head != null) {
            val recent = withContext(Dispatchers.IO) { session.store.read(maxOf(0, head.lastSeq - 2_000), head.lastSeq) }
            recent.forEach { live = live.with(it.entry) }
            rows += recent.filter { labels.row(it.entry) != null && filter.shows(it.entry) }.takeLast(TIMELINE_ROWS).reversed()
        }
        recorder.events.collect { ev ->
            live = live.with(ev.entry)
            if (labels.row(ev.entry) != null && filter.shows(ev.entry)) {
                rows.add(0, ev)
                if (rows.size > TIMELINE_ROWS) rows.removeAt(rows.lastIndex)
            }
        }
    }

    // Re-read grants whenever the activity resumes (the permission pages are other activities).
    var resumes by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val grants = remember(resumes) { context.recorderGrants() }
    val missing by AppGraph.missingGrants.collectAsState()

    val level by recorder.level.collectAsState()
    val capsules by recorder.capsules.collectAsState()
    val parked = live.gear == null || live.gear == GearPosition.PARK
    var open by remember(session) { mutableStateOf<CapsuleSeal?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    // First run = nothing granted yet. Once started, the flow stays until its done screen even as grants arrive.
    var setup by rememberSaveable { mutableStateOf(if (context.recorderGrants().isEmpty()) SetupMode.FIRST_RUN else null) }
    LaunchedEffect(grants) { if (setup == null && grants.isEmpty()) setup = SetupMode.FIRST_RUN }
    var regrantLater by remember { mutableStateOf(false) }

    val consent by remember(context) { DataConsent.state(context) }.collectAsState()

    // Setup is parked only.
    if (parked && setup == SetupMode.FIRST_RUN) {
        SetupFlow(SetupStep.all(), intro = true, onDone = { setup = null; onGrantsChanged() })
        return
    }
    if (parked && setup == SetupMode.REGRANT) {
        SetupFlow(stepsFor(missing), intro = false, onDone = { setup = null; onGrantsChanged() })
        return
    }
    // Asked once, parked, after setup; never again once answered (changeable on the phone tab).
    if (parked && consent == null) {
        ConsentPrompt(onAnswer = { DataConsent.set(context, it) })
        return
    }

    Column(Modifier.fillMaxSize().padding(32.dp)) {
        StatusHeader(labels, live, level, session.mode)
        Spacer(Modifier.padding(8.dp))
        if (!parked) {
            // Driving: read-only, nothing to scroll or press (distraction-optimized).
            rows.take(4).forEach {
                Text("${labels.time(it.entry.at.wallMillis)}  ${labels.row(it.entry)}", style = MaterialTheme.typography.headlineSmall)
            }
            return@Column
        }
        if (regrantPromptAllowed(missing, live.gear ?: GearPosition.PARK) && !regrantLater) {
            RegrantBanner(missing.joinToString { labels.permission(it) }, onAllow = { setup = SetupMode.REGRANT }, onLater = { regrantLater = true })
        }
        val shown = open
        if (shown != null) {
            // Back (touch or the CCP Back key) leaves the packet first; only the top screen hides the app.
            BackHandler { open = null }
            EvidencePacket(labels, shown, session, onBack = { open = null })
            return@Column
        }
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, Modifier.ccpFocus(RectangleShape), text = { Text(stringResource(R.string.tab_timeline), style = MaterialTheme.typography.titleMedium) })
            Tab(tab == 1, { tab = 1 }, Modifier.ccpFocus(RectangleShape), text = { Text(stringResource(R.string.tab_capsules, capsules.size), style = MaterialTheme.typography.titleMedium) })
            Tab(tab == 2, { tab = 2 }, Modifier.ccpFocus(RectangleShape), text = { Text(stringResource(R.string.tab_phone), style = MaterialTheme.typography.titleMedium) })
        }
        Column(Modifier.weight(1f)) {
            when (tab) {
                0 -> Timeline(labels, rows)
                1 -> CapsuleList(labels, capsules, onOpen = { open = it })
                else -> PhoneTab(session.claim, session.webBase, consent == true, onConsent = { agree ->
                    DataConsent.set(context, agree)
                    if (!agree) session.outbox?.withdrawConsent()
                })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 12.dp)) {
            OutlinedButton(onClick = { onSource(SourceMode.REPLAY) }, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.action_replay)) }
            OutlinedButton(onClick = { onSource(SourceMode.LIVE) }, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.action_live)) }
        }
    }
}

private fun stepsFor(missing: Set<RecorderPermission>) = buildList {
    if (RecorderPermission.CAR_SPEED in missing) add(SetupStep.SPEED)
    if (RecorderPermission.LOCATION in missing) add(SetupStep.LOCATION)
    if (RecorderPermission.BACKGROUND_LOCATION in missing) add(SetupStep.BACKGROUND)
}

@Composable
private fun Centered(text: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun RegrantBanner(names: String, onAllow: () -> Unit, onLater: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFFFF3E0)).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.regrant_banner, names), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Button(onClick = onAllow, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.regrant_action)) }
        TextButton(onClick = onLater, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.regrant_later)) }
    }
    Spacer(Modifier.padding(6.dp))
}

@Composable
private fun StatusHeader(labels: Labels, s: LiveState, level: EscalationLevel, mode: SourceMode) {
    val color = when (level) {
        EscalationLevel.NORMAL -> Color(0xFF2E7D32)
        EscalationLevel.ALERT -> Color(0xFFF9A825)
        EscalationLevel.TRIGGERED -> Color(0xFFC62828)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            labels.level(level),
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.background(color).padding(horizontal = 18.dp, vertical = 8.dp),
        )
        Spacer(Modifier.width(20.dp))
        Text(stringResource(if (mode == SourceMode.REPLAY) R.string.mode_replay else R.string.mode_live), style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.padding(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Stat(stringResource(R.string.stat_speed), labels.speed(s.kmh))
        Stat(stringResource(R.string.stat_gear), labels.gear(s.gear))
        Stat(stringResource(R.string.stat_brake), labels.brake(s.brake))
        Stat(stringResource(R.string.stat_turn), labels.turn(s.turn))
        Stat(stringResource(R.string.stat_steering), labels.steering(s.steering))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.Gray)
        Text(value, fontSize = STAT_SIZE, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Timeline(labels: Labels, rows: List<DriveEvent>) {
    // Unkeyed on purpose: keyed items keep the first visible row anchored, so rows inserted on top would land above
    // the viewport. Unkeyed, the list anchors by index and the newest row stays in view.
    LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        items(rows) {
            Row(Modifier.fillMaxWidth().ccpRow().padding(vertical = 6.dp)) {
                Text(labels.time(it.entry.at.wallMillis), color = Color.Gray, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(170.dp))
                Text("#${it.seq}", color = Color.Gray, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(100.dp))
                Text(labels.row(it.entry) ?: "", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun CapsuleList(labels: Labels, capsules: List<CapsuleSeal>, onOpen: (CapsuleSeal) -> Unit) {
    if (capsules.isEmpty()) {
        Text(stringResource(R.string.no_capsules), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        items(capsules.reversed(), key = { it.capsule.id }) { s ->
            Column(Modifier.fillMaxWidth().ccpFocus().clickable { onOpen(s) }.padding(horizontal = 8.dp, vertical = 14.dp)) {
                Text(labels.trigger(s.capsule.trigger), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(
                        R.string.capsule_meta,
                        labels.dateTime(s.sealedAtWallMillis), s.capsule.firstSeq, s.capsule.lastSeq,
                        stringResource(if (s.final) R.string.capsule_final else R.string.capsule_provisional),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Gray,
                )
            }
        }
    }
}
