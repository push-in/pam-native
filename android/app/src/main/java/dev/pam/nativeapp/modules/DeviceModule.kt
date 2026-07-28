package dev.pam.nativeapp.modules

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class DeviceModule(private val context: Context) : NativeModule, AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
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

    private fun start(payload: ByteArray, completion: ModuleCompletion) {
        val interval = ((WireMap.decode(payload)["intervalMs"] as? WireValue.Integer)?.value ?: 1_000)
            .coerceIn(250, 60_000)
        val id = nextId.getAndIncrement()
        val channel = WatchChannel()
        lateinit var runnable: Runnable
        runnable = Runnable {
            if (!watches.containsKey(id)) return@Runnable
            channel.offer(statusPayload())
            main.postDelayed(runnable, interval)
        }
        watches[id] = DeviceWatch(channel, runnable)
        main.post(runnable)
        completion.complete(
            ModuleResultStatus.SUCCESS,
            WireMap.encode(mapOf("subscription" to WireValue.Integer(id.toLong()))),
        )
    }

    private fun statusPayload(): ByteArray {
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
        return WireMap.encode(mapOf(
            "batteryLevel" to WireValue.Decimal(
                if (level >= 0 && scale > 0) level.toDouble() / scale else -1.0,
            ),
            "charging" to WireValue.Flag(charging),
            "networkType" to WireValue.Integer(type.toLong()),
            "expensiveNetwork" to WireValue.Flag(expensive),
            "lowPowerMode" to WireValue.Flag(lowPower),
        ))
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
    }

    override fun close() {
        watches.keys.toList().forEach(::stop)
    }

    private data class DeviceWatch(
        val channel: WatchChannel,
        val runnable: Runnable,
    )
}
