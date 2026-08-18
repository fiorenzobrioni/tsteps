package com.callbackdev.tsteps.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.provider.Settings
import com.callbackdev.tsteps.domain.StepReading
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Thin wrapper over the hardware step counter. tsteps never keeps a listener
 * registered in the background: it *samples* — register, flush the hardware FIFO,
 * take one value, unregister. The counter hardware keeps counting on its own
 * regardless; sampling is enough to compute deltas, and it is what keeps the
 * battery promise (no service, no wake locks, no continuous sensor stream).
 */
class StepSensorReader(context: Context) {

    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    /** False on devices without the sensor — the UI says so in the `//` channel. */
    val isAvailable: Boolean get() = sensor != null

    /**
     * One sample of the counter, or null if the sensor is missing or silent past
     * [timeoutMillis]. STEP_COUNTER is a wake-up-on-change sensor: registering
     * always delivers the current value promptly, so the timeout is a guard, not
     * an expected path.
     */
    suspend fun readCurrent(timeoutMillis: Long = 5_000L): StepReading? {
        val stepSensor = sensor ?: return null
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sensorManager.unregisterListener(this)
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.success(event.toReading()))
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensorManager.registerListener(
                    listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL
                )
                // Push whatever sits in the hardware FIFO out to the listener now.
                sensorManager.flush(listener)
                continuation.invokeOnCancellation {
                    sensorManager.unregisterListener(listener)
                }
            }
        }
    }

    private fun SensorEvent.toReading(): StepReading {
        // event.timestamp is elapsed-realtime nanos; convert to wall clock so the
        // attribution can place the sample on the local calendar.
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - timestamp) / 1_000_000L
        return StepReading(
            cumulativeSteps = values[0].toLong(),
            bootCount = bootCount(),
            timestampMillis = System.currentTimeMillis() - ageMillis
        )
    }

    /** Monotonic across reboots since API 24 — the reset detector for the anchor. */
    private fun bootCount(): Int =
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, 0)
}
