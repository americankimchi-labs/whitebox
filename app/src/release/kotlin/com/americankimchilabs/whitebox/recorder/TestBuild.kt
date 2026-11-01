package com.americankimchilabs.whitebox.recorder

import android.content.Intent
import android.os.Bundle
import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.Tier
import kotlinx.coroutines.flow.Flow

/**
 * The release build records only what the car reports: no scripted drive, no plan set on the car, no test signals.
 * The test build's version of this object provides them.
 */
internal object TestBuild {
    const val REPLAY = false

    fun replay(clock: Clock): Pair<Flow<ChainEntry>, Flow<ChainEntry>>? = null

    fun tier(intent: Intent?): Tier? = null

    fun tier(saved: String?): Tier? = null

    fun airbag(intent: Intent?, clock: Clock): ChainEntry? = null

    fun take(intent: Intent): Bundle? = null
}
