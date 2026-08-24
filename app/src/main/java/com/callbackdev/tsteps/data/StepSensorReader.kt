package com.callbackdev.tsteps.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.SystemClock
import android.provider.Settings
import com.callbackdev.tsteps.domain.StepReading
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the ViewModel and the workers see of the sensor — an interface so tests
 * can feed synthetic readings without Robolectric shadows.
 */
interface StepSource {
    /** False on devices without the sensor — the UI says so in the `//` channel. */
    val isAvailable: Boolean

    /** One sample of the counter, or null if the sensor is missing or silent. */
    suspend fun readCurrent(timeoutMillis: Long = 5_000L): StepReading?

    /**
     * Live readings while collected — for the main screen's ticking count. The
     * listener lives exactly as long as the collector: cancel the flow and the
     * sensor is released. Never collected from the background.
     */
    fun readings(): Flow<StepReading>
}

/**
 * Thin wrapper over the hardware step counter. tsteps never keeps a listener
 * registered in the background: it *samples* — register, flush the hardware FIFO,
 * take one value, unregister ([readCurrent]) — or streams only while the main
 * screen is on the glass ([readings]). The counter hardware keeps counting on its
 * own regardless; that is what keeps the battery promise (no service, no wake
 * locks, no continuous background sensor stream).
 */
class StepSensorReader(context: Context) : StepSource {

    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    override val isAvailable: Boolean get() = sensor != null

    /**
     * STEP_COUNTER is a wake-up-on-change sensor: registering delivers a value
     * promptly, so the timeout is a guard, not an expected path. That first value
     * is not necessarily the *current* one though — a batching counter hands out
     * whatever last reached the AP, which can be minutes old. So the sample waits
     * for the flush to drain the hardware FIFO and keeps the newest event it saw,
     * instead of unregistering on the first one and throwing the fresher batch
     * away (the old code did, and the ↻ tap paid for it).
     */
    override suspend fun readCurrent(timeoutMillis: Long): StepReading? {
        val stepSensor = sensor ?: return null
        // Held outside the timeout on purpose: an event that arrived before a
        // flush that never completed is still a valid sample, and reporting it
        // beats reporting silence (which the widget would wear as `# stale`).
        val latest = AtomicReference<StepReading?>(null)
        withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : SensorEventListener2 {
                    // Written from the flush fallback below (caller's thread) and
                    // read from the sensor callbacks (main looper).
                    @Volatile
                    private var drained = false

                    override fun onSensorChanged(event: SensorEvent) {
                        // Flushed events arrive in timestamp order, so last wins.
                        latest.set(event.toReading())
                        if (drained) settle()
                    }

                    override fun onFlushCompleted(sensor: Sensor?) {
                        drained = true
                        // The FIFO can drain before the on-change event lands;
                        // then the next reading is the one that settles this.
                        if (latest.get() != null) settle()
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

                    private fun settle() {
                        sensorManager.unregisterListener(this)
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.success(Unit))
                        }
                    }
                }
                sensorManager.registerListener(
                    listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL
                )
                // Push whatever sits in the hardware FIFO out to the listener now.
                // false = nothing to drain (no batching, or the register did not
                // take), so the first event is already the whole answer.
                if (!sensorManager.flush(listener)) listener.onFlushCompleted(stepSensor)
                continuation.invokeOnCancellation {
                    sensorManager.unregisterListener(listener)
                }
            }
        }
        return latest.get()
    }

    override fun readings(): Flow<StepReading> = callbackFlow {
        val stepSensor = sensor
        if (stepSensor == null) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(event.toReading())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // UI rate: the count on screen should tick with the stride, and the
        // listener only exists while the screen shows it.
        sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_UI)
        sensorManager.flush(listener)
        awaitClose { sensorManager.unregisterListener(listener) }
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
