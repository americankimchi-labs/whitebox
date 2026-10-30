package com.americankimchilabs.whitebox.platform

import android.util.Log
import com.americankimchilabs.whitebox.core.MirrorStore
import com.americankimchilabs.whitebox.domain.DriveEvent
import com.americankimchilabs.whitebox.domain.IncidentCapsule

/** Local mirror used until the server adapter is configured: logs what would be uploaded, in the order it would be uploaded. */
object LoggingMirror : MirrorStore {
    private const val TAG = "WhiteBox/Mirror"

    override suspend fun uploadBatch(events: List<DriveEvent>): Boolean {
        Log.i(TAG, "batch ${events.firstOrNull()?.seq}..${events.lastOrNull()?.seq}")
        return true
    }

    override suspend fun uploadSealManifest(capsule: IncidentCapsule, headHash: ByteArray): Boolean {
        Log.i(TAG, "seal manifest ${capsule.id} ${capsule.trigger} #${capsule.firstSeq}..#${capsule.lastSeq}")
        return true
    }
}
