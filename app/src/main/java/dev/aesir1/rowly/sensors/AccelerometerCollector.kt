package dev.aesir1.rowly.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * The only Android-aware part of the stroke pipeline: unpacks [SensorEvent] and hands the numbers
 * to a [StrokeRateSource]. Everything interesting happens in [StrokeRateDetector].
 */
class AccelerometerCollector(
    context: Context,
    private val onReading: (Reading) -> Unit,
    private val source: StrokeRateSource,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val isAvailable: Boolean get() = accelerometer != null

    fun start() {
        val sensor = accelerometer ?: return
        // SENSOR_DELAY_GAME is ~50 Hz: far more than the 2 Hz of signal we keep, but the detector
        // resamples anyway and a slower rate would make the timestamp jitter proportionally worse.
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        source.onSample(event.values[0], event.values[1], event.values[2], event.timestamp)
            ?.let(onReading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
