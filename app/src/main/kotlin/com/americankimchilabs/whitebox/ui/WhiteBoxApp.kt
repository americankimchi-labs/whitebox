package com.americankimchilabs.whitebox.ui

import kotlinx.coroutines.flow.MutableStateFlow
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.HandoverPhase
import com.americankimchilabs.whitebox.domain.Entitlements
import com.americankimchilabs.whitebox.domain.LibraryItem
import com.americankimchilabs.whitebox.domain.LibraryStatus
import com.americankimchilabs.whitebox.domain.Retention
import com.americankimchilabs.whitebox.domain.Tier
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.americankimchilabs.whitebox.platform.UpdateCheck
import com.americankimchilabs.whitebox.BuildConfig
import com.americankimchilabs.whitebox.data.CapsuleSeal
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.DriveEvent
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.GearPosition
import com.americankimchilabs.whitebox.domain.TurnSignalState
import com.americankimchilabs.whitebox.domain.VehicleSignal
import com.americankimchilabs.whitebox.domain.regrantPromptAllowed
import com.americankimchilabs.whitebox.platform.recorderGrants
import com.americankimchilabs.whitebox.recorder.ActiveSession
import com.americankimchilabs.whitebox.platform.DataConsent
import com.americankimchilabs.whitebox.recorder.AppGraph
import com.americankimchilabs.whitebox.recorder.SourceMode
import com.americankimchilabs.whitebox.recorder.TestBuild
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
    fun with(e: ChainEntry): LiveState = (e as? ChainEntry.Signal)?.sample?.signal?.let { with(it) } ?: this

    fun with(s: VehicleSignal): LiveState = when (s) {
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
    val sealing by recorder.sealing.collectAsState()
    val allCapsules by recorder.capsules.collectAsState()
    val libraryItems by (session.library?.items ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<LibraryItem>()) }).collectAsState()
    val tier by (session.library?.tier ?: remember { kotlinx.coroutines.flow.MutableStateFlow(Entitlements.DEFAULT) }).collectAsState()
    val leftNotice by (session.library?.notices ?: remember { kotlinx.coroutines.flow.MutableSharedFlow<LibraryItem>() }).collectAsState(initial = null)
    // The library shows what is kept: airbag crashes, kept moments and extended ones (a seal the library has not seen yet too).
    val capsules = remember(allCapsules, libraryItems) {
        val byId = libraryItems.associateBy { it.id }
        val now = System.currentTimeMillis()
        allCapsules.filter { byId[it.capsule.id]?.visible(now) ?: true }
    }
    val parked = live.gear == null || live.gear == GearPosition.PARK
    var open by remember(session) { mutableStateOf<CapsuleSeal?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    // The first-run setup returns at every start until it is finished (every grant given, the ready screen passed).
    var setup by rememberSaveable { mutableStateOf(if (!SetupDone.get(context)) SetupMode.FIRST_RUN else null) }
    var regrantLater by remember { mutableStateOf(false) }

    val consent by remember(context) { DataConsent.state(context) }.collectAsState()
    val handoverPhase by (session.handover?.phase ?: remember { MutableStateFlow(HandoverPhase.OFF) }).collectAsState()
    val handoverSince by (session.handover?.since ?: remember { MutableStateFlow(0L) }).collectAsState()
    var handoverSheet by remember(session) { mutableStateOf(false) }
    // The maps app comes forward for a destination when handover mode asks and this screen is showing.
    LaunchedEffect(session) { session.handover?.openMaps?.collect { openMaps(context) } }

    // Setup is parked only.
    if (parked && setup != null) {
        SetupFlow(
            firstRun = setup == SetupMode.FIRST_RUN,
            askConsent = consent == null,
            onConsent = { DataConsent.set(context, it) },
            onDone = { if (setup == SetupMode.FIRST_RUN) SetupDone.set(context); setup = null; onGrantsChanged() },
        )
        return
    }
    // Asked once, parked (part of the first-run setup); never again once answered (changeable on the phone tab).
    if (parked && consent == null) {
        ConsentScreen(onAnswer = { DataConsent.set(context, it) })
        return
    }

    Column(Modifier.fillMaxSize().padding(32.dp)) {
        // Handover mode is turned on from here, parked, when it is off (the sheet explains it first).
        val handoverButton = parked && session.handover != null && handoverPhase == HandoverPhase.OFF && !handoverSheet
        StatusHeader(labels, live, level, sealing, session.mode, onHandover = if (handoverButton) ({ handoverSheet = true }) else null)
        Spacer(Modifier.padding(8.dp))
        if (!parked) {
            // Driving: read-only, nothing to scroll or press (distraction-optimized).
            HandoverDrivingBanner(handoverPhase)
            rows.take(4).forEach {
                Text("${labels.time(it.entry.at.wallMillis)}  ${labels.row(it.entry)}", style = MaterialTheme.typography.headlineSmall)
            }
            return@Column
        }
        if (regrantPromptAllowed(missing, live.gear ?: GearPosition.PARK) && !regrantLater) {
            RegrantBanner(missing.joinToString { labels.permission(it) }, onAllow = { setup = SetupMode.REGRANT }, onLater = { regrantLater = true })
        }
        if (handoverSheet && handoverPhase == HandoverPhase.OFF) {
            BackHandler { handoverSheet = false }
            HandoverSheet(session, onClose = { handoverSheet = false })
            return@Column
        }
        UpdateNotice(session.carBase)
        HandoverBar(session, handoverPhase, handoverSince, onOpen = { handoverSheet = true })
        Spacer(Modifier.padding(4.dp))
        val shown = open
        if (shown != null) {
            // Back (touch or the CCP Back key) leaves the packet first; only the top screen hides the app.
            BackHandler { open = null }
            if (shown.capsule.trigger == CapsuleTrigger.HANDOVER) TripReport(labels, shown, session, onBack = { open = null })
            else EvidencePacket(labels, shown, session, onBack = { open = null })
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
                1 -> CapsuleList(labels, capsules, libraryItems, tier, leftNotice.takeIf { parked }, onOpen = { open = it })
                else -> PhoneTab(session.claim, session.webBase, consent == true, handoverOn = handoverPhase != HandoverPhase.OFF, onConsent = { agree ->
                    DataConsent.set(context, agree)
                    if (!agree) session.outbox?.withdrawConsent()
                })
            }
        }
        // Switching the source would stop the real recording: never while a hired driver has the car. A release build
        // has no scripted drive, so no switch either.
        if (TestBuild.REPLAY && handoverPhase == HandoverPhase.OFF) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 12.dp)) {
            OutlinedButton(contentPadding = BigPadding, onClick = { onSource(SourceMode.REPLAY) }, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.action_replay)) }
            OutlinedButton(contentPadding = BigPadding, onClick = { onSource(SourceMode.LIVE) }, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.action_live)) }
        }
    }
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
        Text(stringResource(R.string.regrant_banner, names).bySentence(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Button(contentPadding = BigPadding, onClick = onAllow, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.regrant_action)) }
        TextButton(contentPadding = BigPadding, onClick = onLater, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.regrant_later)) }
    }
    Spacer(Modifier.padding(6.dp))
}

/** A newer version is on the market (parked only): one line and the market link; the app never updates itself. */
@Composable
private fun UpdateNotice(base: String?) {
    val context = LocalContext.current
    var latest by remember(base) { mutableStateOf<UpdateCheck.Latest?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(base) { latest = base?.let { UpdateCheck.latest(it) } }
    val l = latest ?: return
    if (dismissed || l.code <= BuildConfig.VERSION_CODE) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).background(BrandPurple.copy(alpha = 0.08f), RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.update_available, l.name).bySentence(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(contentPadding = BigPadding, onClick = { if (!UpdateCheck.openMarket(context)) dismissed = true }, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.update_open_market)) }
        TextButton(contentPadding = BigPadding, onClick = { dismissed = true }, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.update_later)) }
    }
}

@Composable
private fun StatusHeader(labels: Labels, s: LiveState, level: EscalationLevel, sealing: CapsuleTrigger?, mode: SourceMode, onHandover: (() -> Unit)? = null) {
    val color = when (level) {
        EscalationLevel.NORMAL -> Color(0xFF2E7D32)
        EscalationLevel.ALERT -> Color(0xFFF9A825)
        EscalationLevel.TRIGGERED -> Color(0xFFC62828)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            labels.level(level, sealing),
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.background(color).padding(horizontal = 18.dp, vertical = 8.dp),
        )
        Spacer(Modifier.width(20.dp))
        Text(stringResource(if (mode == SourceMode.REPLAY) R.string.mode_replay else R.string.mode_live), style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.padding(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Stat(stringResource(R.string.stat_speed), labels.speed(s.kmh))
        Stat(stringResource(R.string.stat_gear), labels.gear(s.gear))
        Stat(stringResource(R.string.stat_brake), labels.brake(s.brake))
        Stat(stringResource(R.string.stat_turn), labels.turn(s.turn))
        Stat(stringResource(R.string.stat_steering), labels.steering(s.steering))
        if (onHandover != null) {
            Spacer(Modifier.weight(1f))
            HandoverButton(onHandover)
        }
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
                Text(labels.row(it.entry) ?: "", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * The library: the plan and how many slots are used, a notice when a moment had to leave (parked only), and each kept
 * item with how long it stays.
 */
@Composable
private fun CapsuleList(
    labels: Labels,
    capsules: List<CapsuleSeal>,
    items: List<LibraryItem>,
    tier: Tier,
    leftNotice: LibraryItem?,
    onOpen: (CapsuleSeal) -> Unit,
) {
    val now = System.currentTimeMillis()
    val byId = items.associateBy { it.id }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            stringResource(R.string.library_header, labels.plan(tier), items.count { it.usesSlot(now) }, Entitlements(tier).librarySlots),
            style = MaterialTheme.typography.bodyLarge, color = Color.Gray, modifier = Modifier.padding(horizontal = 8.dp),
        )
        leftNotice?.let { n ->
            Column(Modifier.fillMaxWidth().padding(8.dp).background(Color(0x14E65100), RoundedCornerShape(8.dp)).padding(12.dp)) {
                // An older unpaid one, or the new moment itself when every slot is paid: held for an upgrade either way.
                Text(stringResource(R.string.library_left_notice, labels.trigger(n.trigger), labels.dateTime(n.eventWall)).bySentence(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(
                        when (tier) {
                            Tier.FREE -> R.string.library_left_phone_free
                            Tier.PLUS -> R.string.library_left_phone_plus
                            Tier.PRO -> R.string.library_left_phone_pro
                        },
                    ).bySentence(),
                    style = MaterialTheme.typography.bodyMedium, color = Color.Gray,
                )
            }
        }
        if (capsules.isEmpty()) {
            Text(stringResource(R.string.no_capsules).bySentence(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(capsules.reversed(), key = { it.capsule.id }) { s ->
                // A card per record, tinted by kind, with its open button at the end: it reads as something to tap.
                val tint = cardTint(s.capsule.trigger)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
                        .background(tint.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                        .border(2.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .ccpFocus(RoundedCornerShape(16.dp)).clickable { onOpen(s) }
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(labels.trigger(s.capsule.trigger), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(
                                R.string.capsule_meta,
                                labels.dateTime(s.sealedAtWallMillis),
                                stringResource(if (s.final) R.string.capsule_final else R.string.capsule_provisional),
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Gray,
                        )
                        byId[s.capsule.id]?.let { item -> Text(keptFor(labels, item, now), style = MaterialTheme.typography.bodyLarge, color = if (item.status(now) == LibraryStatus.KEPT && Retention.warn(item.recordsUntil, now)) Color(0xFFE65100) else Color.Gray) }
                    }
                    Spacer(Modifier.width(24.dp))
                    Button(onClick = { onOpen(s) }, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp)) {
                        Text(
                            stringResource(if (s.capsule.trigger == CapsuleTrigger.HANDOVER) R.string.capsule_open_report else R.string.capsule_open),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/** 큰 사고 red, 충돌 amber, a 대리 운전 report purple, any other moment blue-grey. */
private fun cardTint(t: CapsuleTrigger) = when {
    t.severe -> Color(0xFFC62828)
    t == CapsuleTrigger.HANDOVER -> BrandPurple
    t.crash -> Color(0xFFE65100)
    else -> Color(0xFF455A64)
}

@Composable
private fun keptFor(labels: Labels, item: LibraryItem, now: Long): String {
    val kept = when (item.status(now)) {
        LibraryStatus.SEVERE -> return stringResource(R.string.library_severe)
        LibraryStatus.EXTENDED -> stringResource(R.string.library_extended, labels.date(item.recordsUntil))
        else -> {
            val days = ((item.recordsUntil - now + Retention.DAY_MS - 1) / Retention.DAY_MS).toInt().coerceAtLeast(0)
            stringResource(if (Retention.warn(item.recordsUntil, now)) R.string.library_days_left_soon else R.string.library_days_left, days)
        }
    }
    // 큰 사고 / 충돌 / 위험 순간: a handover report is named by its title alone.
    return when {
        item.trigger == CapsuleTrigger.HANDOVER -> kept
        item.trigger.crash -> stringResource(R.string.library_kind_crash, kept)
        else -> stringResource(R.string.library_kind_moment, kept)
    }
}
