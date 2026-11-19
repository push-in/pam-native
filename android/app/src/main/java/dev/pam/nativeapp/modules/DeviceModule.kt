package dev.pam.nativeapp.modules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class DeviceModule(private val context: Context) : NativeModule, AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    // Battery/power broadcasts and the network callback outlive any one
    // Activity: on the application context they never leak a recreated
    // surface (the registry moves watches to the new one).
    private val appContext: Context = context.applicationContext ?: context
    private val nextId = AtomicInteger(1)
    private val watches = ConcurrentHashMap<Int, DeviceWatch>()

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            when (method) {
                "status" -> completion.complete(ModuleResultStatus.SUCCESS, statusPayload())
                "watch" -> start(payload, completion)
                "next" -> watch(payload).channel.next(completion)
                "stop" -> {
                    stop(id(payload))
                    completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                }
                else -> error("Unknown device method $method")
            }
        }.onFailure {
            completion.complete(ModuleResultStatus.FAILURE, (it.message ?: "Device status failed").toByteArray())
        }
    }

    /**
     * A watch delivers the first status, then only changes, driven by the
     * system (default network callback, battery and power-save broadcasts)
     * instead of a timer: a watching screen no longer wakes the main thread
     * and PHP every interval, in the foreground or the background. Changes
     * closer together than `intervalMs` are coalesced into one delivery.
     */
    private fun start(payload: ByteArray, completion: ModuleCompletion) {
        val values = WireMap.decode(payload)
        val interval = ((values["intervalMs"] as? WireValue.Integer)?.value ?: 1_000)
            .coerceIn(250, 60_000)
        val fields = DeviceStatusFields.of((values["fields"] as? WireValue.Integer)?.value)
        val id = nextId.getAndIncrement()
        val channel = WatchChannel()
        val changes = DeviceStatusChanges()
        lateinit var runnable: Runnable
        runnable = Runnable {
            val watch = watches[id] ?: return@Runnable
            watch.pending = false
            val status = statusValues()
            // Only the watched fields count as a change; the delivery carries all.
            if (changes.accept(WireMap.encode(DeviceStatusFields.select(status, fields)))) {
                channel.offer(WireMap.encode(status))
                watch.lastDeliveryMs = SystemClock.uptimeMillis()
            }
        }
        watches[id] = DeviceWatch(channel, runnable, interval, fields)
        main.post {
            syncSources()
            runnable.run()
        }
        completion.complete(
            ModuleResultStatus.SUCCESS,
            WireMap.encode(mapOf("subscription" to WireValue.Integer(id.toLong()))),
        )
    }

    /**
     * A system change of [source] fields: every watch of them samples once, no
     * sooner than its interval after the last delivery.
     */
    private fun changed(source: Int) {
        main.post {
            val now = SystemClock.uptimeMillis()
            for (watch in watches.values) {
                if (watch.pending || watch.fields and source == 0) continue
                watch.pending = true
                val wait = (watch.lastDeliveryMs + watch.intervalMs - now).coerceAtLeast(0)
                main.postDelayed(watch.runnable, wait)
            }
        }
    }

    /** Field mask whose system sources are registered (main thread). */
    private var registeredFields = 0
    private val broadcasts = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = changed(
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                DeviceStatusFields.BATTERY
            } else {
                DeviceStatusFields.POWER_SAVE
            },
        )
    }
    // API 26+ delivers on [main]; Android 7.x has no Handler overload, so the
    // callback hops to the main thread itself.
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = networkChanged()
    }

    private fun networkChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            changed(DeviceStatusFields.NETWORK)
        } else {
            main.post { changed(DeviceStatusFields.NETWORK) }
        }
    }

    /**
     * Registers only the system sources the live watches ask for: a
     * network-only watch never listens to battery steps or the charger.
     * Main thread; called when a watch starts or stops.
     */
    private fun syncSources() {
        val wanted = watches.values.fold(0) { mask, watch -> mask or watch.fields }
        if (wanted == registeredFields) return
        val broadcastFields = DeviceStatusFields.BATTERY or DeviceStatusFields.POWER_SAVE
        if (wanted and broadcastFields != registeredFields and broadcastFields) {
            if (registeredFields and broadcastFields != 0) runCatching { appContext.unregisterReceiver(broadcasts) }
            val filter = IntentFilter().apply {
                if (wanted and DeviceStatusFields.BATTERY != 0) addAction(Intent.ACTION_BATTERY_CHANGED)
                if (wanted and DeviceStatusFields.POWER_SAVE != 0) addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            if (filter.countActions() > 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(broadcasts, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    appContext.registerReceiver(broadcasts, filter)
                }
            }
        }
        val wantsNetwork = wanted and DeviceStatusFields.NETWORK != 0
        if (wantsNetwork != (registeredFields and DeviceStatusFields.NETWORK != 0)) {
            runCatching {
                val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
                when {
                    !wantsNetwork -> connectivity.unregisterNetworkCallback(network)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                        connectivity.registerDefaultNetworkCallback(network, main)
                    else -> connectivity.registerDefaultNetworkCallback(network)
                }
            }
        }
        registeredFields = wanted
    }

    private fun statusPayload(): ByteArray = WireMap.encode(statusValues())

    private fun statusValues(): Map<String, WireValue> {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val type = when {
            capabilities == null -> 1
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 3
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 4
            else -> 5
        }
        val expensive = capabilities?.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
        ) == false
        val lowPower = context.getSystemService(PowerManager::class.java).isPowerSaveMode
        return mapOf(
            "batteryLevel" to WireValue.Decimal(
                if (level >= 0 && scale > 0) level.toDouble() / scale else -1.0,
            ),
            "charging" to WireValue.Flag(charging),
            "networkType" to WireValue.Integer(type.toLong()),
            "expensiveNetwork" to WireValue.Flag(expensive),
            "lowPowerMode" to WireValue.Flag(lowPower),
        )
    }

    private fun watch(payload: ByteArray): DeviceWatch =
        watches[id(payload)] ?: error("Unknown device subscription")

    private fun id(payload: ByteArray): Int =
        ((WireMap.decode(payload)["subscription"] as? WireValue.Integer)?.value
            ?: error("Missing device subscription")).toInt()

    private fun stop(id: Int) {
        watches.remove(id)?.let {
            main.removeCallbacks(it.runnable)
            it.channel.close()
        }
        main.post(::syncSources)
    }

    override fun close() {
        watches.keys.toList().forEach(::stop)
    }

    private class DeviceWatch(
        val channel: WatchChannel,
        val runnable: Runnable,
        val intervalMs: Long,
        val fields: Int,
    ) {
        var pending = false
        var lastDeliveryMs = 0L
    }
}

/** Passes the first status and then only statuses that differ from the last one passed. */
internal class DeviceStatusChanges {
    private var last: ByteArray? = null

    fun accept(status: ByteArray): Boolean {
        if (last?.contentEquals(status) == true) return false
        last = status
        return true
    }
}

/** `DeviceStatusField` bitmask of a watch (PHP `DeviceStatusField`). */
internal object DeviceStatusFields {
    const val BATTERY = 1
    const val NETWORK = 2
    const val POWER_SAVE = 4
    const val ALL = BATTERY or NETWORK or POWER_SAVE

    /** Absent or empty asks for every field (watches from older SDKs). */
    fun of(value: Long?): Int = (value ?: 0L).toInt().and(ALL).takeIf { it != 0 } ?: ALL

    private val keys = mapOf(
        "batteryLevel" to BATTERY,
        "charging" to BATTERY,
        "networkType" to NETWORK,
        "expensiveNetwork" to NETWORK,
        "lowPowerMode" to POWER_SAVE,
    )

    /** The entries of [status] a watch of [fields] compares. */
    fun <V> select(status: Map<String, V>, fields: Int): Map<String, V> =
        status.filterKeys { key -> (keys[key] ?: ALL) and fields != 0 }
}
