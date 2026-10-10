package dev.pam.nativeapp.modules

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.pam.nativeapp.PamActivity
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Device position. With Google Play Services, `current`, `watch` and `lastKnown` go
 * through the fused provider (React Native's `locationProvider: 'playServices'`);
 * without it, through the platform LocationManager. Failures are "<code>: <detail>"
 * ([LocationFailure]).
 *
 * @param usePlayServices null detects Google Play Services; tests force a path.
 */
@SuppressLint("MissingPermission")
internal class LocationModule(
    private val context: Context,
    usePlayServices: Boolean? = null,
) : NativeModule, AutoCloseable {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = Collections.synchronizedSet(mutableSetOf<LocationListener>())
    private val pendingFused = Collections.synchronizedSet(mutableSetOf<CancellationTokenSource>())
    private val nextWatch = AtomicInteger(1)
    private val watches = ConcurrentHashMap<Int, LocationWatch>()
    private val playServices: Boolean by lazy {
        usePlayServices ?: runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
    }
    private val fused: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            when (method) {
                "current" -> current(payload, completion)
                "watch" -> watch(payload, completion)
                "lastKnown" -> lastKnown(completion)
                "next" -> (watches[subscription(payload)] ?: error("Unknown location subscription"))
                    .channel.next(completion)
                "stop" -> {
                    stop(subscription(payload))
                    completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                }
                "servicesEnabled" -> completion.complete(
                    ModuleResultStatus.SUCCESS,
                    WireMap.encode(mapOf("enabled" to WireValue.Flag(servicesEnabled()))),
                )
                "requestServices" -> requestServices(completion)
                "openSettings" -> openSettings(completion)
                else -> error("Unknown location method $method")
            }
        }.onFailure { error ->
            completion.complete(ModuleResultStatus.FAILURE, locationFailurePayload(error))
        }
    }

    private fun current(payload: ByteArray, completion: ModuleCompletion) {
        requireReady()
        val values = WireMap.decode(payload)
        val highAccuracy = (values["highAccuracy"] as? WireValue.Flag)?.value ?: true
        val timeoutMs = ((values["timeoutMs"] as? WireValue.Integer)?.value ?: 10_000L)
            .coerceIn(1_000L, 60_000L)
        val maximumAgeMs = ((values["maximumAgeMs"] as? WireValue.Integer)?.value ?: 30_000L)
            .coerceIn(0L, 300_000L)
        if (playServices) {
            fusedCurrent(highAccuracy, timeoutMs, maximumAgeMs, completion)
        } else {
            managerCurrent(highAccuracy, timeoutMs, maximumAgeMs, completion)
        }
    }

    /** Fused `lastLocation` when recent enough, else `getCurrentLocation` bounded by `timeoutMs`. */
    private fun fusedCurrent(
        highAccuracy: Boolean,
        timeoutMs: Long,
        maximumAgeMs: Long,
        completion: ModuleCompletion,
    ) {
        val pending = FusedRequest(completion)
        // getCurrentLocation's own duration is the bound; the guard covers a provider that never answers.
        main.postDelayed(pending.guard, timeoutMs + FUSED_TIMEOUT_GRACE_MS)

        fun request() {
            val request = CurrentLocationRequest.Builder()
                .setPriority(priority(highAccuracy))
                .setDurationMillis(timeoutMs)
                .setMaxUpdateAgeMillis(maximumAgeMs)
                .build()
            fused.getCurrentLocation(request, pending.cancellation.token)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        pending.finish(ModuleResultStatus.SUCCESS, encode(location))
                    } else {
                        pending.finish(ModuleResultStatus.FAILURE, LocationFailure.TIMEOUT.payload(TIMED_OUT))
                    }
                }
                .addOnFailureListener { error -> pending.finish(ModuleResultStatus.FAILURE, locationFailurePayload(error)) }
        }
        fused.lastLocation
            .addOnSuccessListener { location ->
                if (location != null && isFreshLocation(location.time, System.currentTimeMillis(), maximumAgeMs)) {
                    pending.finish(ModuleResultStatus.SUCCESS, encode(location))
                } else {
                    request()
                }
            }
            .addOnFailureListener { request() }
    }

    private inner class FusedRequest(private val completion: ModuleCompletion) {
        val cancellation = CancellationTokenSource()
        private val completed = AtomicBoolean()
        val guard = Runnable { finish(ModuleResultStatus.FAILURE, LocationFailure.TIMEOUT.payload(TIMED_OUT)) }

        init {
            pendingFused += cancellation
        }

        fun finish(status: ModuleResultStatus, payload: ByteArray) {
            if (!completed.compareAndSet(false, true)) return
            main.removeCallbacks(guard)
            pendingFused -= cancellation
            cancellation.cancel()
            completion.complete(status, payload)
        }
    }

    private fun managerCurrent(
        highAccuracy: Boolean,
        timeoutMs: Long,
        maximumAgeMs: Long,
        completion: ModuleCompletion,
    ) {
        val providers = preferredProviders(highAccuracy)
        if (providers.isEmpty()) {
            throw LocationException(LocationFailure.DISABLED, NO_PROVIDER)
        }

        val cached = providers
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { location -> isFreshLocation(location.time, System.currentTimeMillis(), maximumAgeMs) }
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
                completion.complete(ModuleResultStatus.FAILURE, LocationFailure.TIMEOUT.payload(TIMED_OUT))
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
                    completion.complete(ModuleResultStatus.FAILURE, locationFailurePayload(error))
                }
            }
        }
    }

    /**
     * Continuous updates (React Native watchPosition): the provider wakes the
     * listener only after `distanceFilterMeters` of movement and at most every
     * `intervalMs`; fixes are buffered in a WatchChannel read by `next`.
     */
    private fun watch(payload: ByteArray, completion: ModuleCompletion) {
        requireReady()
        val values = WireMap.decode(payload)
        val highAccuracy = (values["highAccuracy"] as? WireValue.Flag)?.value ?: true
        val intervalMs = ((values["intervalMs"] as? WireValue.Integer)?.value ?: 5_000L)
            .coerceIn(0L, 3_600_000L)
        val distance = when (val value = values["distanceFilterMeters"]) {
            is WireValue.Decimal -> value.value
            is WireValue.Integer -> value.value.toDouble()
            else -> 0.0
        }.coerceIn(0.0, 100_000.0).toFloat()
        val id = nextWatch.getAndIncrement()
        val channel = WatchChannel()
        if (playServices) {
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.locations.forEach { location -> channel.offer(encode(location)) }
                }
            }
            watches[id] = LocationWatch(channel) { fused.removeLocationUpdates(callback) }
            val request = LocationRequest.Builder(priority(highAccuracy), intervalMs)
                .setMinUpdateIntervalMillis(intervalMs)
                .setMinUpdateDistanceMeters(distance)
                .build()
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { stop(id) }
        } else {
            val providers = preferredProviders(highAccuracy)
            if (providers.isEmpty()) {
                throw LocationException(LocationFailure.DISABLED, NO_PROVIDER)
            }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    channel.offer(encode(location))
                }

                override fun onProviderDisabled(provider: String) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            watches[id] = LocationWatch(channel) { main.post { manager.removeUpdates(listener) } }
            main.post {
                runCatching {
                    providers.forEach { provider ->
                        manager.requestLocationUpdates(provider, intervalMs, distance, listener, Looper.getMainLooper())
                    }
                }.onFailure { stop(id) }
            }
        }
        completion.complete(
            ModuleResultStatus.SUCCESS,
            WireMap.encode(mapOf("subscription" to WireValue.Integer(id.toLong()))),
        )
    }

    /** Fused `lastLocation`, then the newest fix any LocationManager provider still holds. */
    private fun lastKnown(completion: ModuleCompletion) {
        if (!hasPermission()) throw LocationException(LocationFailure.PERMISSION, PERMISSION_REQUIRED)
        fun fallback() {
            val location = managerLastKnown()
            if (location != null) {
                completion.success(location)
            } else {
                completion.complete(
                    ModuleResultStatus.FAILURE,
                    LocationFailure.UNAVAILABLE.payload("No last known location"),
                )
            }
        }
        if (!playServices) {
            fallback()
            return
        }
        fused.lastLocation
            .addOnSuccessListener { location -> if (location != null) completion.success(location) else fallback() }
            .addOnFailureListener { fallback() }
    }

    private fun managerLastKnown(): Location? =
        runCatching { manager.allProviders }.getOrDefault(emptyList())
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull(Location::getTime)

    private fun servicesEnabled(): Boolean =
        runCatching { LocationManagerCompat.isLocationEnabled(manager) }.getOrDefault(false)

    /**
     * Play Services' "turn on location" dialog (SettingsClient), like React Native's
     * PushinLocationSettings.requestEnable. Without Play Services or an activity no dialog
     * is possible: Enabled when the switch is already on, otherwise Unavailable.
     */
    private fun requestServices(completion: ModuleCompletion) {
        fun resolve(result: LocationServicesResult) {
            completion.complete(
                ModuleResultStatus.SUCCESS,
                WireMap.encode(mapOf("result" to WireValue.Integer(result.wire))),
            )
        }
        fun withoutDialog() = resolve(
            if (servicesEnabled()) LocationServicesResult.ENABLED else LocationServicesResult.UNAVAILABLE,
        )
        val activity = context as? PamActivity
        if (activity == null || !playServices) {
            withoutDialog()
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, SETTINGS_INTERVAL_MS).build()
        val settings = LocationSettingsRequest.Builder()
            .addLocationRequest(request)
            .setAlwaysShow(true)
            .build()
        LocationServices.getSettingsClient(activity).checkLocationSettings(settings)
            .addOnSuccessListener { resolve(LocationServicesResult.ENABLED) }
            .addOnFailureListener { error ->
                if (error !is ResolvableApiException || activity.isFinishing || activity.isDestroyed) {
                    withoutDialog()
                    return@addOnFailureListener
                }
                runCatching {
                    activity.launchIntentSenderForResult(error.resolution.intentSender) { resultCode, _ ->
                        resolve(LocationServicesResult.fromResolution(resultCode))
                    }
                }.onFailure { withoutDialog() }
            }
    }

    private fun openSettings(completion: ModuleCompletion) {
        main.post {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = try {
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
            completion.complete(
                ModuleResultStatus.SUCCESS,
                WireMap.encode(mapOf("opened" to WireValue.Flag(opened))),
            )
        }
    }

    /** Permission first, then the system switch: the order React Native reports them in. */
    private fun requireReady() {
        if (!hasPermission()) throw LocationException(LocationFailure.PERMISSION, PERMISSION_REQUIRED)
        if (!servicesEnabled()) throw LocationException(LocationFailure.DISABLED, NO_PROVIDER)
    }

    private fun stop(id: Int) {
        val watch = watches.remove(id) ?: return
        watch.channel.close()
        watch.release()
    }

    private fun subscription(payload: ByteArray): Int =
        ((WireMap.decode(payload)["subscription"] as? WireValue.Integer)?.value
            ?: error("Missing location subscription")).toInt()

    private fun priority(highAccuracy: Boolean): Int =
        if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY

    private fun preferredProviders(highAccuracy: Boolean): List<String> {
        val requested = if (highAccuracy) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        }
        return requested.filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
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
        pendingFused.toList().forEach(CancellationTokenSource::cancel)
        pendingFused.clear()
        watches.keys.toList().forEach(::stop)
    }

    private class LocationWatch(val channel: WatchChannel, val release: () -> Unit)

    private fun ModuleCompletion.success(location: Location) {
        complete(ModuleResultStatus.SUCCESS, encode(location))
    }

    private fun encode(location: Location): ByteArray =
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
        )

    private companion object {
        const val PERMISSION_REQUIRED = "Location permission is required"
        const val NO_PROVIDER = "No enabled location provider"
        const val TIMED_OUT = "Timed out while obtaining location"
        const val FUSED_TIMEOUT_GRACE_MS = 2_000L
        const val SETTINGS_INTERVAL_MS = 10_000L
    }
}
