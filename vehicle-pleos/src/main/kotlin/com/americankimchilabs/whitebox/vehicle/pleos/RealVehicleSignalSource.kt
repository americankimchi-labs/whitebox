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
 * The real car as one [VehicleSignalSource]: Pleos SDK signals merged with AAOS speed and ignition.
 * This is the only entry point :app uses, so no Pleos type crosses the module boundary.
 */
class RealVehicleSignalSource(context: Context, clock: Clock) : VehicleSignalSource, Closeable {

    private val pleos = PleosSignalSource(context, clock)
    private val aaos = CarPropertySignalSource(context, clock)

    override suspend fun supportedKinds(): Set<SignalKind> = pleos.supportedKinds() + aaos.supportedKinds()

    override fun samples(): Flow<SignalSample> = merge(pleos.samples(), aaos.samples())

    override fun close() {
        pleos.close()
        aaos.close()
    }
}
