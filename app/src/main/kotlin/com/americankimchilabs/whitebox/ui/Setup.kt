package com.americankimchilabs.whitebox.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.americankimchilabs.whitebox.R

/** One runtime grant in the parked setup flow, asked in this order (Android grants background location only after fine). */
enum class SetupStep(val permissions: List<String>, @StringRes val title: Int, @StringRes val body: Int) {
    SPEED(listOf("android.car.permission.CAR_SPEED"), R.string.step_speed_title, R.string.step_speed_body),
    LOCATION(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        R.string.step_location_title, R.string.step_location_body,
    ),

    /** Must be asked on its own, after LOCATION: the platform answers with the "Allow all the time" page. */
    BACKGROUND(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), R.string.step_background_title, R.string.step_background_body),
    NOTIFICATIONS(listOf(Manifest.permission.POST_NOTIFICATIONS), R.string.step_notifications_title, R.string.step_notifications_body),
    ;

    fun granted(context: Context) = context.checkSelfPermission(permissions.first()) == PackageManager.PERMISSION_GRANTED

    companion object {
        fun all(): List<SetupStep> =
            if (Build.VERSION.SDK_INT >= 33) entries else entries - NOTIFICATIONS
    }
}

/**
 * The first-run flow (with [intro]) or a re-grant of [steps] only (without). Parked only — the caller decides.
 * Each step is skipped when already granted; a denial just moves on (the recorder writes it into the chain).
 */
@Composable
fun SetupFlow(steps: List<SetupStep>, intro: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    // -1 = intro, steps.size = done screen
    var index by rememberSaveable { mutableIntStateOf(if (intro) -1 else firstPending(steps, 0, context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        index = firstPending(steps, index + 1, context)
    }

    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 900.dp)) {
            when {
                index < 0 -> Intro(onStart = { index = firstPending(steps, 0, context) })
                index < steps.size -> Step(
                    steps[index], index, steps.size,
                    onAllow = { launcher.launch(steps[index].permissions.toTypedArray()) },
                    onSkip = { index = firstPending(steps, index + 1, context) },
                )
                else -> Done(steps, context, onDone)
            }
        }
    }
}

private fun firstPending(steps: List<SetupStep>, from: Int, context: Context): Int {
    var i = from
    while (i < steps.size && steps[i].granted(context)) i++
    return i
}

@Composable
private fun Intro(onStart: () -> Unit) {
    Text(stringResource(R.string.intro_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(32.dp))
    Point(R.string.intro_what_title, R.string.intro_what)
    Point(R.string.intro_where_title, R.string.intro_where)
    Point(R.string.intro_whose_title, R.string.intro_whose)
    Spacer(Modifier.height(32.dp))
    Button(onClick = onStart) { Text(stringResource(R.string.intro_start), style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun Point(@StringRes title: Int, @StringRes body: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 20.dp))
}

@Composable
private fun Step(step: SetupStep, index: Int, total: Int, onAllow: () -> Unit, onSkip: () -> Unit) {
    Text(stringResource(R.string.step_count, index + 1, total), style = MaterialTheme.typography.labelLarge, color = Color.Gray)
    Text(stringResource(step.title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(step.body), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(32.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = onAllow) { Text(stringResource(R.string.step_allow), style = MaterialTheme.typography.titleMedium) }
        TextButton(onClick = onSkip) { Text(stringResource(R.string.step_skip), style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun Done(steps: List<SetupStep>, context: Context, onDone: () -> Unit) {
    Text(stringResource(R.string.done_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.done_body), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(16.dp))
    steps.forEach {
        val name = stringResource(it.title)
        Text(
            if (it.granted(context)) stringResource(R.string.done_granted, name) else stringResource(R.string.done_denied, name),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    Spacer(Modifier.height(32.dp))
    Button(onClick = onDone) { Text(stringResource(R.string.done_finish), style = MaterialTheme.typography.titleMedium) }
}
