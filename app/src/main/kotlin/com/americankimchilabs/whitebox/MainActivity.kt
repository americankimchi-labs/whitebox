package com.americankimchilabs.whitebox

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.americankimchilabs.whitebox.recorder.AppGraph
import com.americankimchilabs.whitebox.recorder.RecorderService
import com.americankimchilabs.whitebox.recorder.RecorderService.Companion.MIRROR_DEFAULT
import com.americankimchilabs.whitebox.recorder.RecorderService.Companion.MIRROR_LOCAL
import com.americankimchilabs.whitebox.recorder.SourceMode
import com.americankimchilabs.whitebox.ui.WhiteBoxApp
import com.americankimchilabs.whitebox.ui.WhiteBoxTheme

/**
 * Opening the app (re)starts the recorder service — also how a stopped package (fresh install, Force Stop) comes back.
 *
 *   adb shell am start --user 10 -n com.americankimchilabs.whitebox/.MainActivity --es source REPLAY
 *
 * `source` = LIVE (car) or REPLAY (scripted demo drive, restarted from the top each time).
 *
 * `mirror` = `local` sends the mirror to a server on the emulator's host computer (ws://10.0.2.2:8080, started with
 * `./gradlew :server:run`), `default` returns to the build's own mirror. Only these two fixed values exist, so no
 * app can redirect the mirror to an address of its choosing:
 *
 *   adb shell am start --user 10 -n com.americankimchilabs.whitebox/.MainActivity --es mirror local
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) startFrom(intent) // not again on a configuration change
        // Pleos review rule: Back (incl. the CCP Back key) on the top screen hides the app instead of finishing it.
        // Screens above the top one (the evidence packet) register their own handler, which takes precedence.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
        setContent {
            WhiteBoxTheme {
                Surface(Modifier.fillMaxSize()) {
                    val session by AppGraph.session.collectAsState()
                    WhiteBoxApp(
                        session,
                        onSource = { RecorderService.start(this, it, restartReplay = it == SourceMode.REPLAY) },
                        // Re-run the grant guard, so restored grants are written into the chain.
                        onGrantsChanged = { RecorderService.start(this) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startFrom(intent)
    }

    private fun startFrom(intent: Intent) {
        val source = intent.getStringExtra(RecorderService.EXTRA_SOURCE)?.let(SourceMode::valueOf)
        val mirror = intent.getStringExtra(RecorderService.EXTRA_MIRROR)?.takeIf { it == MIRROR_LOCAL || it == MIRROR_DEFAULT }
        RecorderService.start(this, source, restartReplay = source == SourceMode.REPLAY, mirror = mirror)
    }
}
