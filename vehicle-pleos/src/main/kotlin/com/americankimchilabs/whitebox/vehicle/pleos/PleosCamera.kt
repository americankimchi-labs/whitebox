package com.americankimchilabs.whitebox.vehicle.pleos

import android.content.Context
import com.americankimchilabs.whitebox.core.CameraClipSource

/**
 * The car's built-in camera as a source of clips. Pleos Connect gives apps no camera recordings today: the Vehicle
 * SDK 2.3.2 has no camera domain and no permission covers one, so there is no source. This is where its adapter goes
 * on the day the platform opens that access; the recording service already hands whatever [clipSource] returns to
 * the clip pipeline (encryption with the record's key, device signature, upload), so nothing else has to change.
 */
object PleosCamera {
    @Suppress("UNUSED_PARAMETER")
    fun clipSource(context: Context): CameraClipSource? = null
}
