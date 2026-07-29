package dev.pam.nativeapp.modules

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
internal class LocationModule(private val context: Context) : NativeModule, AutoCloseable {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = Collections.synchronizedSet(mutableSetOf<LocationListener>())

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            when (method) {
                "current" -> current(payload, completion)
                else -> error("Unknown location method $method")
            }
        }.onFailure { error ->
            completion.complete(
                ModuleResultStatus.FAILURE,
                (error.message ?: "Location operation failed").toByteArray(),
            )
        }
    }

    private fun current(payload: ByteArray, completion: ModuleCompletion) {
        require(hasPermission()) {
            "Location permission is required"
        }
        val values = WireMap.decode(payload)
        val highAccuracy = (values["highAccuracy"] as? WireValue.Flag)?.value ?: true
        val timeoutMs = ((values["timeoutMs"] as? WireValue.Integer)?.value ?: 10_000L)
            .coerceIn(1_000L, 60_000L)
        val maximumAgeMs = ((values["maximumAgeMs"] as? WireValue.Integer)?.value ?: 30_000L)
            .coerceIn(0L, 300_000L)
        val providers = preferredProviders(highAccuracy)
        require(providers.isNotEmpty()) {
            "No enabled location provider"
        }

        val cached = providers
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { location -> System.currentTimeMillis() - location.time <= maximumAgeMs }
            .minByOrNull(Location::getAccuracy)
        if (cached != null) {
            completion.success(cached)
            return
        }

        val completed = AtomicBoolean()
        lateinit var listener: LocationListener
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                listeners.remove(listener)
                manager.removeUpdates(listener)
                completion.complete(
                    ModuleResultStatus.FAILURE,
                    "Timed out while obtaining location".toByteArray(),
                )
            }
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!completed.compareAndSet(false, true)) return
                main.removeCallbacks(timeout)
                listeners.remove(this)
                manager.removeUpdates(this)
                completion.success(location)
            }

            override fun onProviderDisabled(provider: String) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listeners += listener
        main.post {
            runCatching {
                providers.forEach { provider ->
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
                main.postDelayed(timeout, timeoutMs)
            }.onFailure { error ->
                if (completed.compareAndSet(false, true)) {
                    listeners.remove(listener)
                    manager.removeUpdates(listener)
                    completion.complete(
                        ModuleResultStatus.FAILURE,
                        (error.message ?: "Unable to obtain location").toByteArray(),
                    )
                }
            }
        }
    }

    private fun preferredProviders(highAccuracy: Boolean): List<String> {
        val requested = if (highAccuracy) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        }
        return requested.filter(manager::isProviderEnabled)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    override fun close() {
        listeners.toList().forEach(manager::removeUpdates)
        listeners.clear()
    }

    private fun ModuleCompletion.success(location: Location) {
        complete(
            ModuleResultStatus.SUCCESS,
            WireMap.encode(
                mapOf(
                    "latitude" to WireValue.Decimal(location.latitude),
                    "longitude" to WireValue.Decimal(location.longitude),
                    "accuracy" to WireValue.Decimal(location.accuracy.toDouble()),
                    "altitude" to WireValue.Decimal(
                        if (location.hasAltitude()) location.altitude else 0.0,
                    ),
                    "speed" to WireValue.Decimal(
                        if (location.hasSpeed()) location.speed.toDouble() else 0.0,
                    ),
                    "bearing" to WireValue.Decimal(
                        if (location.hasBearing()) location.bearing.toDouble() else 0.0,
                    ),
                    "timestamp" to WireValue.Integer(location.time),
                ),
            ),
        )
    }
}
