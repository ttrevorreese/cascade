package com.cascade.alarm.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** SPEC §6.4: the hardware step counter, not Health Connect. */
object StepSensor {
    fun hasSensor(context: Context): Boolean =
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /** ACTIVITY_RECOGNITION is a runtime permission from Android 10. */
    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun usable(context: Context): Boolean = hasSensor(context) && hasPermission(context)
}

/** The step counter's running total (steps since boot) while composed; null until the first reading. */
@Composable
fun rememberStepCounter(): State<Long?> {
    val context = LocalContext.current
    val total = remember { mutableStateOf<Long?>(null) }
    DisposableEffect(context) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                total.value = event.values[0].toLong()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // No batching: progress should move as the user walks.
        if (sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, 0)
        onDispose { manager?.unregisterListener(listener) }
    }
    return total
}
