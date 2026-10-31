package com.americankimchilabs.whitebox.recorder

import android.content.Context
import com.americankimchilabs.whitebox.data.CapsuleShares
import com.americankimchilabs.whitebox.data.ClaimClient
import com.americankimchilabs.whitebox.data.FileEventStore
import com.americankimchilabs.whitebox.data.Outbox
import com.americankimchilabs.whitebox.data.Recorder
import com.americankimchilabs.whitebox.data.Signer
import com.americankimchilabs.whitebox.domain.RecorderPermission
import com.americankimchilabs.whitebox.platform.KeystoreSigner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Where the signals come from. LIVE is the car (Pleos + AAOS); REPLAY plays the scripted demo drive from
 * :vehicle-fake for clean screen recording. Each has its own chain: replayed data never enters the live evidence.
 */
enum class SourceMode { LIVE, REPLAY }

/**
 * The running recorder, shared by the service (writer) and the UI (reader) in one process. [claim] and [webBase]
 * exist when a mirror is configured: the phone connection and the address of its pages.
 */
class ActiveSession(
    val mode: SourceMode,
    val recorder: Recorder,
    val store: FileEventStore,
    val claim: ClaimClient? = null,
    val webBase: String? = null,
    val outbox: Outbox? = null,
    /** The evidence packet's QR links; null without a server. */
    val shares: CapsuleShares? = null,
)

object AppGraph {
    private val _session = MutableStateFlow<ActiveSession?>(null)
    val session: StateFlow<ActiveSession?> = _session.asStateFlow()

    val signer: Signer by lazy { KeystoreSigner() }

    private val _missingGrants = MutableStateFlow<Set<RecorderPermission>>(emptySet())
    /** What the grant guard last found missing; the UI turns it into the parked re-grant prompt. */
    val missingGrants: StateFlow<Set<RecorderPermission>> = _missingGrants.asStateFlow()

    internal fun publishMissing(missing: Set<RecorderPermission>) {
        _missingGrants.value = missing
    }

    fun chainDir(context: Context, mode: SourceMode) = File(context.filesDir, "chain/${mode.name.lowercase()}")

    internal fun publish(session: ActiveSession?) {
        _session.value = session
    }
}
