package com.americankimchilabs.whitebox.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.americankimchilabs.whitebox.core.MotionSource
import com.americankimchilabs.whitebox.domain.MotionWindow
import com.americankimchilabs.whitebox.domain.Vec3
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "WhiteBox/Motion"
private const val PERIOD_US = 20_000 // 50 Hz
private const val WINDOW_NS = 1_000_000_000L

/**
 * The head unit's accelerometer through the platform SensorManager (no permission), averaged into 1 s windows.
 * Uses the calibrated accelerometer if the unit has one, else the uncalibrated one with its bias removed, else the
 * automotive limited-axes variant. Callbacks run on their own thread, never the main one.
 *
 * On the Pleos Connect emulator the accelerometer holds a fixed pose that ignores the simulated drive, so the mount
 * never calibrates and no grade is recorded; whether production head units carry an accelerometer is not yet known.
 */
class PlatformMotionSource(context: Context) : MotionSource {

    private val sensors = context.applicationContext.getSystemService(SensorManager::class.java)

    private fun accelerometer(): Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
        ?: if (Build.VERSION.SDK_INT >= 33) sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED) else null

    override fun windows(): Flow<MotionWindow> = callbackFlow {
        val sensor = accelerometer()
        if (sensor == null) {
            Log.i(TAG, "no accelerometer; road grade off")
            close()
            return@callbackFlow
        }
        val unbiased = sensor.type != Sensor.TYPE_ACCELEROMETER // uncalibrated variants carry their bias in [3..5]
        var start = 0L
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        var n = 0
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = e.values
                val x = if (unbiased) v[0] - v[3] else v[0]
                val y = if (unbiased) v[1] - v[4] else v[1]
                val z = if (unbiased) v[2] - v[5] else v[2]
                if (n == 0) start = e.timestamp
                sx += x; sy += y; sz += z; n++
                if (e.timestamp - start >= WINDOW_NS) {
                    trySendBlocking(MotionWindow(start, e.timestamp, Vec3((sx / n).toFloat(), (sy / n).toFloat(), (sz / n).toFloat()), n))
                    sx = 0.0; sy = 0.0; sz = 0.0; n = 0
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        val thread = HandlerThread(TAG).apply { start() }
        sensors.registerListener(listener, sensor, PERIOD_US, Handler(thread.looper))
        Log.i(TAG, "accelerometer: ${sensor.stringType} (${sensor.name})")
        awaitClose {
            sensors.unregisterListener(listener)
            thread.quitSafely()
        }
    }
}
