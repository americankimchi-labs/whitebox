package com.americankimchilabs.whitebox.recorder

import android.content.Intent
import android.os.Bundle
import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.Tier
import com.americankimchilabs.whitebox.domain.VehicleSignal
import com.americankimchilabs.whitebox.vehicle.fake.DemoDrive
import com.americankimchilabs.whitebox.vehicle.fake.ScriptedLocationSource
import com.americankimchilabs.whitebox.vehicle.fake.ScriptedSignalSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What only a test build can do: everything here is made up for demonstrations and testing, so the release build has
 * a version of this object that does none of it (and no scripted drive inside).
 */
internal object TestBuild {
    /** The scripted demo drive (데모 재생) exists in this build. */
    const val REPLAY = true

    /** The scripted demo drive: signals, then locations. */
    fun replay(clock: Clock): Pair<Flow<ChainEntry>, Flow<ChainEntry>>? =
        ScriptedSignalSource(DemoDrive.signals(), clock).samples().map { ChainEntry.Signal(it) } to
            ScriptedLocationSource(DemoDrive.locations(), clock).fixes().map { ChainEntry.Location(it) }

    /** A plan set on the car itself for a car without the cloud (`--es tier FREE|PLUS|PRO`). */
    fun tier(intent: Intent?): Tier? = intent?.getStringExtra(EXTRA_TIER)?.let { t -> Tier.entries.firstOrNull { it.name == t } }

    /** A plan set that way before (kept across restarts). */
    fun tier(saved: String?): Tier? = saved?.let { t -> Tier.entries.firstOrNull { it.name == t } }

    /**
     * The emulator's VHAL has no airbag property, so `--ez test_airbag true` injects one to show a 큰 사고. It is
     * recorded as a test signal (TEST_AIRBAG everywhere it shows), never as a deployment. On a real vehicle the airbag
     * comes from the Vehicle SDK's safety domain.
     */
    fun airbag(intent: Intent?, clock: Clock): ChainEntry? =
        if (intent?.getBooleanExtra(EXTRA_TEST_AIRBAG, false) == true) {
            ChainEntry.Signal(SignalSample(VehicleSignal.AirbagDeployed("ROW_1_LEFT", deployed = true, test = true), clock.now()))
        } else {
            null
        }

    /** The test-only extras of a launch, removed from it so a later recreation of the activity does not repeat them. */
    fun take(intent: Intent): Bundle? {
        val out = Bundle()
        intent.getStringExtra(EXTRA_TIER)?.let { out.putString(EXTRA_TIER, it) }
        if (intent.getBooleanExtra(EXTRA_TEST_AIRBAG, false)) out.putBoolean(EXTRA_TEST_AIRBAG, true)
        intent.removeExtra(EXTRA_TIER)
        intent.removeExtra(EXTRA_TEST_AIRBAG)
        return out.takeIf { !it.isEmpty }
    }

    private const val EXTRA_TIER = "tier"
    private const val EXTRA_TEST_AIRBAG = "test_airbag"
}
