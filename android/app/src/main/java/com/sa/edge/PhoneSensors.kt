package com.sa.edge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
class PhoneSensors(
    context: Context,
) : SensorEventListener, LocationListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)

    @Volatile var lat: Double = 0.0
    @Volatile var lng: Double = 0.0
    @Volatile var altitude: Double = 0.0
    @Volatile var heading: Double = 0.0
    @Volatile var hasFix: Boolean = false

    fun start() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        // Permission is validated by MainActivity before start.
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                this,
            )
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
        } catch (_: SecurityException) {
            /* caller handles */
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, 3)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, 3)
            }
        }
        if (SensorManager.getRotationMatrix(rotation, null, gravity, geomagnetic)) {
            SensorManager.getOrientation(rotation, orientation)
            var deg = Math.toDegrees(orientation[0].toDouble())
            if (deg < 0) deg += 360.0
            heading = deg
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        lat = location.latitude
        lng = location.longitude
        altitude = location.altitude
        hasFix = true
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
