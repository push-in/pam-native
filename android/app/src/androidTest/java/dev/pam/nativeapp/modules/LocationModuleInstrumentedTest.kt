package dev.pam.nativeapp.modules

import android.app.Activity
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import dev.pam.nativeapp.PamActivity
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Location.watch / next / stop over a mock GPS provider (React Native watchPosition parity),
 * the Play Services fused provider in mock mode, the location services switch and the
 * "<code>: <detail>" failure contract (1.35.0).
 */
@RunWith(AndroidJUnit4::class)
class LocationModuleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(LocationManager::class.java)
    private lateinit var module: LocationModule
    private var activity: Activity? = null
    private val playServices: Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    @Before
    fun setUp() {
        shell("pm grant ${context.packageName} android.permission.ACCESS_FINE_LOCATION")
        shell("appops set ${context.packageName} android:mock_location allow")
        runCatching { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
        @Suppress("DEPRECATION")
        manager.addTestProvider(
            LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true,
            Criteria.POWER_LOW, Criteria.ACCURACY_FINE,
        )
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        // LocationManager path; the fused tests build their own module.
        module = LocationModule(context, usePlayServices = false)
        // Android 10+ only delivers updates to a foreground app (no background location), like the real app.
        activity = instrumentation.startActivitySync(
            Intent(context, PamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    @After
    fun tearDown() {
        setLocationEnabled(true)
        if (playServices) {
            runCatching { Tasks.await(LocationServices.getFusedLocationProviderClient(context).setMockMode(false), 5, TimeUnit.SECONDS) }
        }
        activity?.let { launched -> instrumentation.runOnMainSync { launched.finish() } }
        module.close()
        runCatching { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
    }

    @Test
    fun watchDeliversEachFixUntilStopped() {
        val subscription = call("watch", mapOf(
            "highAccuracy" to WireValue.Flag(true),
            "distanceFilterMeters" to WireValue.Decimal(0.0),
            "intervalMs" to WireValue.Integer(0),
        )).let { (WireMap.decode(it.second)["subscription"] as WireValue.Integer).value }
        val read = CountDownLatch(1)
        val fix = AtomicReference<Pair<ModuleResultStatus, ByteArray>>()
        module.invoke("next", WireMap.encode(mapOf("subscription" to WireValue.Integer(subscription))), completion(fix, read))
        SystemClock.sleep(300)
        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, mock(-23.5505, -46.6333))
        assertTrue("watch must deliver the mock fix", read.await(10, TimeUnit.SECONDS))
        assertEquals(ModuleResultStatus.SUCCESS, fix.get().first)
        val values = WireMap.decode(fix.get().second)
        assertEquals(-23.5505, (values["latitude"] as WireValue.Decimal).value, 0.00001)

        val stopped = CountDownLatch(1)
        val pending = AtomicReference<Pair<ModuleResultStatus, ByteArray>>()
        module.invoke("next", WireMap.encode(mapOf("subscription" to WireValue.Integer(subscription))), completion(pending, stopped))
        assertEquals(ModuleResultStatus.SUCCESS, call("stop", mapOf("subscription" to WireValue.Integer(subscription))).first)
        assertTrue(stopped.await(5, TimeUnit.SECONDS))
        assertEquals("stop must close the pending read", ModuleResultStatus.FAILURE, pending.get().first)
        assertEquals(ModuleResultStatus.FAILURE, call("next", mapOf("subscription" to WireValue.Integer(subscription))).first)
    }


    @Test
    fun locationOffFailsWithTheDisabledCode() {
        setLocationEnabled(false)
        assertFalse("servicesEnabled must follow the system switch", servicesEnabled(module))
        for (usePlayServices in listOf(false, playServices).distinct()) {
            val located = LocationModule(context, usePlayServices)
            try {
                val current = call(located, "current", mapOf(
                    "highAccuracy" to WireValue.Flag(true),
                    "timeoutMs" to WireValue.Integer(2_000),
                    "maximumAgeMs" to WireValue.Integer(0),
                ))
                assertEquals(ModuleResultStatus.FAILURE, current.first)
                assertTrue(current.second.decodeToString(), current.second.decodeToString().startsWith("disabled: "))
                val watch = call(located, "watch", mapOf("highAccuracy" to WireValue.Flag(true)))
                assertEquals(ModuleResultStatus.FAILURE, watch.first)
                assertTrue(watch.second.decodeToString(), watch.second.decodeToString().startsWith("disabled: "))
            } finally {
                located.close()
            }
        }
        setLocationEnabled(true)
        assertTrue("servicesEnabled must follow the system switch", servicesEnabled(module))
    }

    @Test
    fun requestServicesWithoutADialogResolvesEnabledOrUnavailable() {
        assertEquals(1L, servicesResult(call(module, "requestServices", emptyMap())))
        setLocationEnabled(false)
        // No activity (and no Play Services for this module): the app must offer the settings screen.
        assertEquals(3L, servicesResult(call(module, "requestServices", emptyMap())))
    }

    @Test
    fun requestServicesShowsThePlayServicesDialogAndReportsADismissal() {
        assumeTrue("Google Play Services is not available on this device", playServices)
        setLocationEnabled(false)
        val host = activity!!
        val located = LocationModule(host, usePlayServices = true)
        try {
            val latch = CountDownLatch(1)
            val result = AtomicReference<Pair<ModuleResultStatus, ByteArray>>()
            located.invoke("requestServices", WireMap.encode(emptyMap()), completion(result, latch))
            assertFalse("the system dialog must wait for the user", latch.await(4, TimeUnit.SECONDS))
            pressBack()
            assertTrue("dismissing the dialog must resolve", latch.await(10, TimeUnit.SECONDS))
            assertEquals(2L, servicesResult(result.get()))
        } finally {
            located.close()
        }
    }

    @Test
    fun openSettingsOpensTheLocationSourceScreen() {
        val opened = call(module, "openSettings", emptyMap())
        assertEquals(ModuleResultStatus.SUCCESS, opened.first)
        assertEquals(WireValue.Flag(true), WireMap.decode(opened.second)["opened"])
        SystemClock.sleep(1_000)
        pressBack()
    }

    @Test
    fun lastKnownReadsTheLocationManagerProviders() {
        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, mock(-10.25, -20.5))
        SystemClock.sleep(300)
        val known = call(module, "lastKnown", emptyMap())
        assertEquals(known.second.decodeToString(), ModuleResultStatus.SUCCESS, known.first)
        assertEquals(-10.25, (WireMap.decode(known.second)["latitude"] as WireValue.Decimal).value, 0.00001)
    }

    @Test
    fun fusedProviderServesCurrentLastKnownAndWatch() {
        assumeTrue("Google Play Services is not available on this device", playServices)
        val fused = LocationServices.getFusedLocationProviderClient(context)
        Tasks.await(fused.setMockMode(true), 5, TimeUnit.SECONDS)
        val located = LocationModule(context, usePlayServices = true)
        try {
            // An active fused request first: the fused provider records mock fixes for its clients.
            val subscription = call(located, "watch", mapOf(
                "highAccuracy" to WireValue.Flag(true),
                "distanceFilterMeters" to WireValue.Decimal(0.0),
                "intervalMs" to WireValue.Integer(0),
            )).let { (WireMap.decode(it.second)["subscription"] as WireValue.Integer).value }
            val read = CountDownLatch(1)
            val fix = AtomicReference<Pair<ModuleResultStatus, ByteArray>>()
            located.invoke("next", WireMap.encode(mapOf("subscription" to WireValue.Integer(subscription))), completion(fix, read))
            feedFused(fused, -13.5, -23.5, read)
            assertEquals(ModuleResultStatus.SUCCESS, fix.get().first)
            assertEquals(-13.5, (WireMap.decode(fix.get().second)["latitude"] as WireValue.Decimal).value, 0.00001)

            val known = call(located, "lastKnown", emptyMap())
            assertEquals(known.second.decodeToString(), ModuleResultStatus.SUCCESS, known.first)
            assertEquals(-13.5, (WireMap.decode(known.second)["latitude"] as WireValue.Decimal).value, 0.00001)

            // A recent fused fix satisfies maximumAgeMs without a new request.
            val cached = call(located, "current", mapOf(
                "highAccuracy" to WireValue.Flag(false),
                "timeoutMs" to WireValue.Integer(5_000),
                "maximumAgeMs" to WireValue.Integer(60_000),
            ))
            assertEquals(cached.second.decodeToString(), ModuleResultStatus.SUCCESS, cached.first)
            assertEquals(-13.5, (WireMap.decode(cached.second)["latitude"] as WireValue.Decimal).value, 0.00001)
            assertEquals(ModuleResultStatus.SUCCESS, call(located, "stop", mapOf("subscription" to WireValue.Integer(subscription))).first)

            // maximumAgeMs = 0 waits for a fresh fused fix.
            val fresh = CountDownLatch(1)
            val current = AtomicReference<Pair<ModuleResultStatus, ByteArray>>()
            located.invoke("current", WireMap.encode(mapOf(
                "highAccuracy" to WireValue.Flag(true),
                "timeoutMs" to WireValue.Integer(15_000),
                "maximumAgeMs" to WireValue.Integer(0),
            )), completion(current, fresh))
            feedFused(fused, -12.5, -22.5, fresh)
            assertEquals(current.get().second.decodeToString(), ModuleResultStatus.SUCCESS, current.get().first)
            assertEquals(-12.5, (WireMap.decode(current.get().second)["latitude"] as WireValue.Decimal).value, 0.00001)
        } finally {
            located.close()
        }
    }

    private fun feedFused(
        fused: com.google.android.gms.location.FusedLocationProviderClient,
        latitude: Double,
        longitude: Double,
        delivered: CountDownLatch,
    ) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (delivered.count > 0 && SystemClock.uptimeMillis() < deadline) {
            Tasks.await(fused.setMockLocation(mock(latitude, longitude)), 5, TimeUnit.SECONDS)
            delivered.await(500, TimeUnit.MILLISECONDS)
        }
        assertTrue("the fused provider must deliver the mock fix", delivered.await(0, TimeUnit.SECONDS))
    }

    private fun servicesEnabled(target: LocationModule): Boolean {
        val result = call(target, "servicesEnabled", emptyMap())
        assertEquals(ModuleResultStatus.SUCCESS, result.first)
        return (WireMap.decode(result.second)["enabled"] as WireValue.Flag).value
    }

    private fun servicesResult(result: Pair<ModuleResultStatus, ByteArray>): Long {
        assertEquals(result.second.decodeToString(), ModuleResultStatus.SUCCESS, result.first)
        return (WireMap.decode(result.second)["result"] as WireValue.Integer).value
    }

    private fun setLocationEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            shell("cmd location set-location-enabled $enabled")
        } else {
            val sign = if (enabled) "+" else "-"
            shell("settings put secure location_providers_allowed ${sign}gps")
            shell("settings put secure location_providers_allowed ${sign}network")
        }
        // The mock GPS provider keeps its own enabled state below API 28.
        runCatching { manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, enabled) }
        SystemClock.sleep(500)
    }

    private fun pressBack() = shell("input keyevent ${KeyEvent.KEYCODE_BACK}")

    private fun call(method: String, values: Map<String, WireValue>): Pair<ModuleResultStatus, ByteArray> =
        call(module, method, values)

    private fun call(target: LocationModule, method: String, values: Map<String, WireValue>): Pair<ModuleResultStatus, ByteArray> {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Pair<ModuleResultStatus, ByteArray>>()
        target.invoke(method, WireMap.encode(values), completion(result, latch))
        assertTrue("$method must complete", latch.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    private fun completion(
        result: AtomicReference<Pair<ModuleResultStatus, ByteArray>>,
        latch: CountDownLatch,
    ): ModuleCompletion = ModuleCompletion { status, payload ->
        result.set(status to payload)
        latch.countDown()
    }

    private fun mock(latitude: Double, longitude: Double): Location = Location(LocationManager.GPS_PROVIDER).apply {
        this.latitude = latitude
        this.longitude = longitude
        accuracy = 5f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
        SystemClock.sleep(200)
    }
}
