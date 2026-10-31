package com.americankimchilabs.whitebox.vehicle.pleos

import android.content.Context
import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.core.VehicleSignalSource
import com.americankimchilabs.whitebox.domain.SignalKind
import com.americankimchilabs.whitebox.domain.SignalSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import java.io.Closeable

/**
 * The real car as one [VehicleSignalSource]: Pleos SDK signals merged with AAOS speed, gear, ignition and parking
 * brake, and the navigation's road context.
 * This is the only entry point :app uses, so no Pleos type crosses the module boundary.
 */
class RealVehicleSignalSource(context: Context, clock: Clock) : VehicleSignalSource, Closeable {

    private val pleos = PleosSignalSource(context, clock)
    private val aaos = CarPropertySignalSource(context, clock)
    private val navi = NaviRoadContextSource(context, clock)

    override suspend fun supportedKinds(): Set<SignalKind> = pleos.supportedKinds() + aaos.supportedKinds() + navi.supportedKinds()

    override fun samples(): Flow<SignalSample> = merge(pleos.samples(), aaos.samples(), navi.samples())

    override fun close() {
        pleos.close()
        aaos.close()
        navi.close()
    }
}
