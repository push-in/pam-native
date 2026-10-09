package dev.pam.nativeapp.modules

import android.app.Activity
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamActivity
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Location.watch / next / stop over a mock GPS provider (React Native watchPosition parity). */
@RunWith(AndroidJUnit4::class)
class LocationModuleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(LocationManager::class.java)
    private lateinit var module: LocationModule
    private var activity: Activity? = null

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
        module = LocationModule(context)
        // Android 10+ only delivers updates to a foreground app (no background location), like the real app.
        activity = instrumentation.startActivitySync(
            Intent(context, PamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    @After
    fun tearDown() {
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

    private fun call(method: String, values: Map<String, WireValue>): Pair<ModuleResultStatus, ByteArray> {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Pair<ModuleResultStatus, ByteArray>>()
        module.invoke(method, WireMap.encode(values), completion(result, latch))
        assertTrue(latch.await(5, TimeUnit.SECONDS))
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
