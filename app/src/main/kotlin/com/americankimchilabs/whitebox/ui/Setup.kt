package com.americankimchilabs.whitebox.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.americankimchilabs.whitebox.R

/**
 * The runtime grants the recorder needs, all explained on one screen and asked with one tap. Android still shows its
 * own prompts, at least two: one for speed and location together (the car's speed sits in the location group), then
 * the "Allow all the time" page, which the platform shows only on its own and only after location is granted.
 */
enum class SetupGrant(
    val permissions: List<String>,
    @StringRes val title: Int,
    @StringRes val why: Int,
    @StringRes val lost: Int,
    /** Setup does not finish without it. 'Always' is not: recording starts at ignition anyway, only the place is lost. */
    val required: Boolean,
) {
    SPEED(listOf("android.car.permission.CAR_SPEED"), R.string.grant_speed_title, R.string.grant_speed_why, R.string.grant_speed_lost, true),
    LOCATION(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        R.string.grant_location_title, R.string.grant_location_why, R.string.grant_location_lost, true,
    ),
    ALWAYS(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), R.string.grant_always_title, R.string.grant_always_why, R.string.grant_always_lost, false),
    ;

    fun granted(context: Context) = context.checkSelfPermission(permissions.first()) == PackageManager.PERMISSION_GRANTED
}

private enum class Page { ASK, DENIED, CONSENT, READY }

/**
 * Parked only — the caller decides. First run: the grants on one screen → what stops working if any was refused (and
 * that the records stay protected) → the data-sharing question ([askConsent]) → the "ready" screen saying what White
 * Box records and keeps. A re-grant ([firstRun] false) shows only the grants and, if still refused, what is lost.
 */
@Composable
fun SetupFlow(firstRun: Boolean, askConsent: Boolean, onConsent: (Boolean) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    // Returning from the system pages (or the app settings) re-reads the grants.
    var resumes by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val missing = remember(resumes) { SetupGrant.entries.filterNot { it.granted(context) } }
    var page by rememberSaveable { mutableStateOf(if (missing.isEmpty() && firstRun) Page.READY else Page.ASK) }
    var consentAsked by rememberSaveable { mutableStateOf(!askConsent) }
    // Which of Android's own prompts is open (its name), or null. While one is, this screen keeps its words above the
    // prompt, which Android draws across the middle of the screen and no app can move.
    var prompting by rememberSaveable { mutableStateOf<String?>(null) }

    fun afterGrants() {
        val still = SetupGrant.entries.filterNot { it.granted(context) }
        page = when {
            still.isNotEmpty() -> Page.DENIED
            !firstRun -> return onDone()
            !consentAsked -> Page.CONSENT
            else -> Page.READY
        }
    }
    fun next() {
        if (!firstRun) return onDone()
        page = if (!consentAsked) Page.CONSENT else Page.READY
    }

    val always = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumes++; prompting = null; afterGrants() }
    var askedAt by remember { mutableLongStateOf(0L) }
    // From its second time Android adds "허용하지 않고 다시 묻지 않음" to the prompt; the hint then warns about it.
    var repeatAsk by rememberSaveable { mutableStateOf(false) }
    // Android no longer asks for the car's speed, and the car's settings have no switch for it.
    var speedLocked by rememberSaveable { mutableStateOf(false) }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        resumes++
        val requiredMissing = SetupGrant.entries.any { it.required && !it.granted(context) }
        // An answer back at once means Android showed nothing (the driver chose "don't ask again" before): only the
        // app's settings page can grant it now.
        val unseen = System.currentTimeMillis() - askedAt < UNSEEN_MS
        // Location can be switched on in the app's settings; the car's speed cannot (no switch there for it).
        speedLocked = unseen && !SetupGrant.SPEED.granted(context)
        prompting = when {
            requiredMissing && unseen && !speedLocked -> SETTINGS
            !requiredMissing && !SetupGrant.ALWAYS.granted(context) -> SetupGrant.ALWAYS.name
            else -> null
        }
        if (prompting == null) afterGrants()
    }
    // The 'always' choice and the app's settings are full-screen system pages nothing can sit on: say what to pick
    // first, then open them.
    LaunchedEffect(prompting) {
        when (prompting) {
            SetupGrant.ALWAYS.name -> {
                delay(PAGE_HINT_MS)
                always.launch(SetupGrant.ALWAYS.permissions.first())
            }
            SETTINGS -> {
                delay(PAGE_HINT_MS)
                prompting = SETTINGS_OPEN
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
    // Back from the app's settings: carry on from what is granted now.
    LaunchedEffect(resumes) {
        if (prompting == SETTINGS_OPEN) { prompting = null; afterGrants() }
    }
    fun ask() {
        val pending = SetupGrant.entries.filterNot { it.granted(context) }
        when {
            pending.isEmpty() -> afterGrants()
            pending == listOf(SetupGrant.ALWAYS) -> prompting = SetupGrant.ALWAYS.name
            else -> {
                // Always ask Android first: after "don't ask again" it answers at once without a prompt (then the
                // settings page follows), and a permission whose group is already allowed may be granted silently.
                prompting = SetupGrant.LOCATION.name
                askedAt = System.currentTimeMillis()
                repeatAsk = SetupPrompted.get(context)
                SetupPrompted.set(context)
                foreground.launch((pending - SetupGrant.ALWAYS).flatMap { it.permissions }.toTypedArray())
            }
        }
    }

    prompting?.let { return PromptHint(it, warnNever = repeatAsk) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 64.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 1500.dp)) {
            when (page) {
                Page.ASK -> Ask(if (firstRun) SetupGrant.entries else missing, onAllow = ::ask, onLater = { afterGrants() })
                // First run: without speed and location White Box cannot do its job, so the only way on is to allow
                // them (the second button goes back to the explanation); 'always' alone can be left. A later re-grant
                // can always be put off.
                Page.DENIED -> if (firstRun && missing.any { it.required }) Denied(missing, true, speedLocked, onRetry = ::ask, onOther = { page = Page.ASK })
                    else Denied(missing, false, speedLocked, onRetry = ::ask, onOther = ::next)
                Page.CONSENT -> ConsentPrompt(onAnswer = { onConsent(it); consentAsked = true; page = Page.READY })
                Page.READY -> Ready(missing, onDone)
            }
        }
    }
}

/**
 * Shown while one of Android's prompts is open: what to press and why, all in the top of the screen, so the prompt
 * (drawn across the middle) hides nothing. Before a full-screen system page (the 'always' choice, the app's settings)
 * it is shown for [PAGE_HINT_MS].
 */
@Composable
private fun PromptHint(kind: String, warnNever: Boolean) {
    val (todo, why) = when (kind) {
        SetupGrant.ALWAYS.name -> R.string.prompt_always_do to R.string.prompt_always_why
        SETTINGS, SETTINGS_OPEN -> R.string.prompt_settings_do to R.string.prompt_settings_why
        else -> R.string.prompt_location_do to R.string.prompt_location_why
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 32.dp), verticalArrangement = Arrangement.Top) {
        Text(stringResource(todo), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(why).bySentence(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
        if (warnNever && kind == SetupGrant.LOCATION.name) {
            Text(stringResource(R.string.prompt_never_warning).bySentence(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFFC62828), modifier = Modifier.padding(top = 12.dp))
        }
    }
}

private const val PAGE_HINT_MS = 2_500L

/** A permission answer faster than this came without a prompt on screen. */
private const val UNSEEN_MS = 400L

/** Android stopped asking: the hint before the app's settings page, and the page itself being open. */
private const val SETTINGS = "SETTINGS"
private const val SETTINGS_OPEN = "SETTINGS_OPEN"

@Composable
private fun Ask(grants: List<SetupGrant>, onAllow: () -> Unit, onLater: () -> Unit) {
    Title(stringResource(R.string.grant_title))
    grants.forEach { Point(stringResource(it.title), stringResource(it.why)) }
    Text(stringResource(R.string.grant_never), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.grant_how).bySentence(), style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
    Spacer(Modifier.height(40.dp))
    Buttons(R.string.grant_allow, onAllow, R.string.grant_later, onLater)
}

@Composable
private fun Denied(missing: List<SetupGrant>, required: Boolean, speedLocked: Boolean, onRetry: () -> Unit, onOther: () -> Unit) {
    Title(stringResource(if (required) R.string.denied_title_required else R.string.denied_title))
    missing.forEach { Point(stringResource(it.title), stringResource(it.lost)) }
    Spacer(Modifier.height(8.dp))
    if (speedLocked && SetupGrant.SPEED in missing) Point(stringResource(R.string.speed_locked_title), stringResource(R.string.speed_locked_body))
    Point(stringResource(R.string.denied_safe_title), stringResource(if (required) R.string.denied_safe_required else R.string.denied_safe))
    Spacer(Modifier.height(24.dp))
    Buttons(R.string.denied_retry, onRetry, if (required) R.string.denied_back else R.string.denied_continue, onOther)
}

@Composable
private fun Ready(missing: List<SetupGrant>, onDone: () -> Unit) {
    Title(stringResource(R.string.ready_title))
    Text(stringResource(R.string.ready_lead), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(32.dp))
    Point(stringResource(R.string.intro_what_title), stringResource(R.string.intro_what))
    Point(stringResource(R.string.intro_where_title), stringResource(R.string.intro_where))
    Point(stringResource(R.string.intro_whose_title), stringResource(R.string.intro_whose))
    if (missing.isNotEmpty()) {
        Text(
            stringResource(R.string.ready_missing, missing.map { stringResource(it.title) }.joinToString()),
            style = MaterialTheme.typography.bodyLarge, color = Color.Gray,
        )
    }
    Spacer(Modifier.height(40.dp))
    BigButton(R.string.done_finish, onDone)
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(32.dp))
}

@Composable
private fun Point(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(body.bySentence(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp, bottom = 28.dp))
}

/** Padding of every car button: large enough to hit at arm's length. */
val BigPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp)

/** Setup buttons: large enough to hit at arm's length and read without glasses. */
@Composable
fun BigButton(@StringRes label: Int, onClick: () -> Unit, outlined: Boolean = false) {
    val padding = PaddingValues(horizontal = 48.dp, vertical = 20.dp)
    val text: @Composable () -> Unit = { Text(stringResource(label), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
    if (outlined) OutlinedButton(onClick = onClick, contentPadding = padding, modifier = Modifier.ccpFocus(CircleShape)) { text() }
    else Button(onClick = onClick, contentPadding = padding, modifier = Modifier.ccpFocus(CircleShape)) { text() }
}

@Composable
fun Buttons(@StringRes main: Int, onMain: () -> Unit, @StringRes other: Int, onOther: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        BigButton(main, onMain)
        BigButton(other, onOther, outlined = true)
    }
}

/** A bulleted line whose wrapped lines stay under its text, not under the bullet. */
@Composable
fun Bullet(text: String) {
    Row {
        Text("•", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(32.dp))
        Text(text.bySentence(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** Back from a record to the list: big and filled, so it is the first thing found. */
@Composable
fun BackButton(onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp), modifier = Modifier.ccpFocus(CircleShape)) {
        Text(stringResource(R.string.packet_back), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}


/** Whether the first-run setup was finished: until then every start of the app returns to it (parked). */
object SetupDone {
    private fun prefs(context: Context) = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
    fun get(context: Context) = prefs(context).getBoolean("done", false)
    fun set(context: Context) = prefs(context).edit().putBoolean("done", true).apply()
}

/** Whether Android's permission prompt was shown before (from then on it offers "don't ask again"). */
private object SetupPrompted {
    private fun prefs(context: Context) = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
    fun get(context: Context) = prefs(context).getBoolean("prompted", false)
    fun set(context: Context) = prefs(context).edit().putBoolean("prompted", true).apply()
}
