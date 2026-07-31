package dev.pam.nativeapp.modules

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.pam.nativeapp.PamActivity
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Collections

internal class SystemModule(private val context: Context) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private val sensorListeners = Collections.synchronizedSet(
        mutableSetOf<SensorEventListener>(),
    )

    fun invoke(
        operation: NativeOperation,
        payload: ByteArray,
        completion: ModuleCompletion,
    ) {
        if (closed.get()) {
            completion.complete(ModuleResultStatus.FAILURE, "System module is closed".toByteArray())
            return
        }
        runCatching {
            when (operation) {
                NativeOperation.ALERT -> alert(payload, completion)
                NativeOperation.TOAST -> toast(payload, completion)
                NativeOperation.SHARE -> share(payload, completion)
                NativeOperation.OPEN_URL -> openUrl(payload, completion)
                NativeOperation.CAN_OPEN_URL -> canOpenUrl(payload, completion)
                NativeOperation.VIBRATE -> vibrate(payload, completion)
                NativeOperation.DEVICE_INFO -> deviceInfo(completion)
                NativeOperation.KEYBOARD_DISMISS -> dismissKeyboard(completion)
                NativeOperation.PERMISSION_CHECK -> checkPermission(payload, completion)
                NativeOperation.PERMISSION_REQUEST -> requestPermission(payload, completion)
                NativeOperation.CLOSE_APP -> closeApp(completion)
                NativeOperation.HAPTIC -> haptic(payload, completion)
                NativeOperation.CLIPBOARD_SET_TEXT -> clipboardSetText(payload, completion)
                NativeOperation.CLIPBOARD_GET_TEXT -> clipboardGetText(completion)
                NativeOperation.CLIPBOARD_HAS_TEXT -> clipboardHasText(completion)
                NativeOperation.SENSOR_READ -> sensorRead(payload, completion)
                else -> error("Operation ${operation.name} is not a system operation")
            }
        }.onFailure { error ->
            completion.complete(
                ModuleResultStatus.FAILURE,
                (error.message ?: "System operation failed").toByteArray(),
            )
        }
    }

    private fun closeApp(completion: ModuleCompletion) {
        main.post {
            (context as? Activity)?.finish()
            completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
        }
    }

    private fun alert(payload: ByteArray, completion: ModuleCompletion) {
        val activity = context as? PamActivity ?: error("Alert requires an active PamActivity")
        val values = WireMap.decode(payload)
        val title = values.text("title")
        val message = values.text("message")
        main.post {
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
                .setOnDismissListener { completion.success() }
                .show()
        }
    }

    private fun toast(payload: ByteArray, completion: ModuleCompletion) {
        val values = WireMap.decode(payload)
        val message = values.text("message")
        val duration = if (values.flag("long", false)) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        main.post {
            Toast.makeText(context, message, duration).show()
            completion.success()
        }
    }

    private fun share(payload: ByteArray, completion: ModuleCompletion) {
        val values = WireMap.decode(payload)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, values.text("text"))
            values.textOrNull("title")?.takeIf(String::isNotEmpty)?.let {
                putExtra(Intent.EXTRA_TITLE, it)
            }
        }
        val chooser = Intent.createChooser(intent, values.textOrNull("title")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        completion.success()
    }

    private fun openUrl(payload: ByteArray, completion: ModuleCompletion) {
        val uri = safeUri(WireMap.decode(payload).text("url"))
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        require(intent.resolveActivity(context.packageManager) != null) {
            "No Android activity can open this URL"
        }
        context.startActivity(intent)
        completion.success()
    }

    private fun canOpenUrl(payload: ByteArray, completion: ModuleCompletion) {
        val uri = safeUri(WireMap.decode(payload).text("url"))
        val supported = Intent(Intent.ACTION_VIEW, uri).resolveActivity(context.packageManager) != null
        completion.complete(
            ModuleResultStatus.SUCCESS,
            WireMap.encode(mapOf("supported" to WireValue.Flag(supported))),
        )
    }

    private fun vibrate(payload: ByteArray, completion: ModuleCompletion) {
        val milliseconds = WireMap.decode(payload)
            .integer("milliseconds", 30L)
            .coerceIn(1L, 10_000L)
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
        completion.success()
    }

    private fun haptic(payload: ByteArray, completion: ModuleCompletion) {
        val feedback = WireMap.decode(payload).integer("feedback", 1L).coerceIn(1L, 7L)
        main.post {
            val target = (context as? Activity)?.window?.decorView
            val constant = when (feedback.toInt()) {
                1 -> HapticFeedbackConstants.CLOCK_TICK
                2 -> HapticFeedbackConstants.KEYBOARD_TAP
                3 -> HapticFeedbackConstants.VIRTUAL_KEY
                4 -> HapticFeedbackConstants.LONG_PRESS
                5 -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
                    else HapticFeedbackConstants.VIRTUAL_KEY
                6, 7 -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
                    else HapticFeedbackConstants.LONG_PRESS
                else -> HapticFeedbackConstants.VIRTUAL_KEY
            }
            target?.performHapticFeedback(constant)
            completion.success()
        }
    }

    private fun deviceInfo(completion: ModuleCompletion) {
        main.post {
            runCatching {
                val metrics = context.resources.displayMetrics
                val density = metrics.density
                val appearance = when (
                    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                ) {
                    Configuration.UI_MODE_NIGHT_YES -> APPEARANCE_DARK
                    else -> APPEARANCE_LIGHT
                }
                val activity = context as? PamActivity
                val appState = if (activity?.hasWindowFocus() == true) {
                    APP_STATE_ACTIVE
                } else {
                    APP_STATE_BACKGROUND
                }
                val visibleInsets = activity?.window?.decorView
                    ?.let(ViewCompat::getRootWindowInsets)
                    ?.getInsets(
                        WindowInsetsCompat.Type.systemBars() or
                            WindowInsetsCompat.Type.displayCutout(),
                    )
                val stableInsets = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    activity !== null
                ) {
                    stableWindowSafeArea(activity)
                } else {
                    intArrayOf(0, 0, 0, 0)
                }
                val rootInsets = activity?.rootHost?.stableSafeAreaInsets
                val safeLeft = maxOf(
                    visibleInsets?.left ?: 0,
                    stableInsets[0],
                    rootInsets?.left ?: 0,
                )
                val safeTop = maxOf(
                    visibleInsets?.top ?: 0,
                    stableInsets[1],
                    rootInsets?.top ?: 0,
                )
                val safeRight = maxOf(
                    visibleInsets?.right ?: 0,
                    stableInsets[2],
                    rootInsets?.right ?: 0,
                )
                val rawSafeBottom = maxOf(
                    visibleInsets?.bottom ?: 0,
                    stableInsets[3],
                    rootInsets?.bottom ?: 0,
                )
                val safeBottom = rawSafeBottom
                val viewportWidth = activity?.rootHost?.width
                    ?.takeIf { it > 0 }
                    ?: metrics.widthPixels
                val viewportHeight = activity?.rootHost?.height
                    ?.takeIf { it > 0 }
                    ?: metrics.heightPixels
                WireMap.encode(
                    mapOf(
                        "width" to WireValue.Decimal(viewportWidth / density.toDouble()),
                        "height" to WireValue.Decimal(viewportHeight / density.toDouble()),
                        "density" to WireValue.Decimal(density.toDouble()),
                        "appearance" to WireValue.Integer(appearance.toLong()),
                        "appState" to WireValue.Integer(appState.toLong()),
                        "safeAreaTop" to WireValue.Decimal(
                            safeTop / density.toDouble(),
                        ),
                        "safeAreaRight" to WireValue.Decimal(
                            safeRight / density.toDouble(),
                        ),
                        "safeAreaBottom" to WireValue.Decimal(
                            safeBottom / density.toDouble(),
                        ),
                        "safeAreaLeft" to WireValue.Decimal(
                            safeLeft / density.toDouble(),
                        ),
                    ),
                )
            }.onSuccess { payload ->
                completion.complete(ModuleResultStatus.SUCCESS, payload)
            }.onFailure { error ->
                completion.complete(
                    ModuleResultStatus.FAILURE,
                    (error.message ?: "Device info failed").toByteArray(),
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun stableWindowSafeArea(activity: Activity): IntArray {
        val insets = activity.windowManager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
        return intArrayOf(insets.left, insets.top, insets.right, insets.bottom)
    }

    private fun dismissKeyboard(completion: ModuleCompletion) {
        val activity = context as? PamActivity ?: error("Keyboard requires an active PamActivity")
        val input = context.getSystemService(InputMethodManager::class.java)
        input.hideSoftInputFromWindow(activity.currentFocus?.windowToken, 0)
        activity.currentFocus?.clearFocus()
        completion.success()
    }

    private fun clipboardSetText(payload: ByteArray, completion: ModuleCompletion) {
        val text = WireMap.decode(payload).text("text")
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_CLIPBOARD_BYTES) {
            "Clipboard text exceeds one megabyte"
        }
        main.post {
            clipboard().setPrimaryClip(ClipData.newPlainText("", text))
            completion.success()
        }
    }

    private fun clipboardGetText(completion: ModuleCompletion) {
        main.post {
            val text = clipboard().primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                ?.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_CLIPBOARD_BYTES }
                .orEmpty()
            completion.complete(
                ModuleResultStatus.SUCCESS,
                WireMap.encode(mapOf("text" to WireValue.Text(text))),
            )
        }
    }

    private fun clipboardHasText(completion: ModuleCompletion) {
        main.post {
            val hasText = clipboard().hasPrimaryClip() &&
                (clipboard().primaryClipDescription?.hasMimeType("text/*") == true)
            completion.complete(
                ModuleResultStatus.SUCCESS,
                WireMap.encode(mapOf("hasText" to WireValue.Flag(hasText))),
            )
        }
    }

    private fun clipboard(): ClipboardManager =
        context.getSystemService(ClipboardManager::class.java)

    private fun sensorRead(payload: ByteArray, completion: ModuleCompletion) {
        val values = WireMap.decode(payload)
        val type = values.integer("type", 0L).toInt()
        val timeoutMs = values.integer("timeoutMs", 2_000L).coerceIn(100L, 10_000L)
        val platformType = when (type) {
            1 -> Sensor.TYPE_ACCELEROMETER
            2 -> Sensor.TYPE_GYROSCOPE
            3 -> Sensor.TYPE_MAGNETIC_FIELD
            4 -> Sensor.TYPE_ROTATION_VECTOR
            else -> error("Unknown sensor type $type")
        }
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager.getDefaultSensor(platformType)
            ?: error("Requested sensor is unavailable")
        val completed = AtomicBoolean()
        lateinit var listener: SensorEventListener
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                manager.unregisterListener(listener)
                sensorListeners.remove(listener)
                completion.complete(
                    ModuleResultStatus.FAILURE,
                    "Sensor read timed out".toByteArray(),
                )
            }
        }
        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!completed.compareAndSet(false, true)) return
                manager.unregisterListener(this)
                sensorListeners.remove(this)
                main.removeCallbacks(timeout)
                completion.complete(
                    ModuleResultStatus.SUCCESS,
                    WireMap.encode(
                        mapOf(
                            "x" to WireValue.Decimal(event.values.getOrElse(0) { 0f }.toDouble()),
                            "y" to WireValue.Decimal(event.values.getOrElse(1) { 0f }.toDouble()),
                            "z" to WireValue.Decimal(event.values.getOrElse(2) { 0f }.toDouble()),
                            "timestamp" to WireValue.Integer(event.timestamp / 1_000_000L),
                        ),
                    ),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorListeners += listener
        main.post {
            if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)) {
                sensorListeners.remove(listener)
                completion.complete(
                    ModuleResultStatus.FAILURE,
                    "Could not start sensor".toByteArray(),
                )
                return@post
            }
            main.postDelayed(timeout, timeoutMs)
        }
    }

    private fun checkPermission(payload: ByteArray, completion: ModuleCompletion) {
        val permission = permission(payload)
        val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        completion.permission(granted)
    }

    private fun requestPermission(payload: ByteArray, completion: ModuleCompletion) {
        val activity = context as? PamActivity
            ?: error("Permission requests require an active PamActivity")
        val permission = permission(payload)
        main.post {
            activity.requestPamPermission(permission) { granted ->
                completion.permission(granted)
            }
        }
    }

    private fun permission(payload: ByteArray): String =
        WireMap.decode(payload).text("permission").also { permission ->
            require(
                permission.startsWith("android.permission.") &&
                    permission.all { it.isLetterOrDigit() || it == '_' || it == '.' },
            ) { "Invalid Android permission" }
        }

    private fun safeUri(value: String): Uri =
        Uri.parse(value).also { uri ->
            require(uri.scheme in setOf("https", "http", "mailto", "tel", "geo")) {
                "Unsupported URL scheme"
            }
        }

    private fun ModuleCompletion.success() {
        complete(ModuleResultStatus.SUCCESS, ByteArray(0))
    }

    private fun ModuleCompletion.permission(granted: Boolean) {
        complete(
            ModuleResultStatus.SUCCESS,
            WireMap.encode(mapOf("granted" to WireValue.Flag(granted))),
        )
    }

    private fun Map<String, WireValue>.text(key: String): String =
        (this[key] as? WireValue.Text)?.value ?: error("Missing text value $key")

    private fun Map<String, WireValue>.textOrNull(key: String): String? =
        (this[key] as? WireValue.Text)?.value

    private fun Map<String, WireValue>.flag(key: String, fallback: Boolean): Boolean =
        (this[key] as? WireValue.Flag)?.value ?: fallback

    private fun Map<String, WireValue>.integer(key: String, fallback: Long): Long =
        (this[key] as? WireValue.Integer)?.value ?: fallback

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val manager = context.getSystemService(SensorManager::class.java)
        sensorListeners.toList().forEach(manager::unregisterListener)
        sensorListeners.clear()
        main.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val MAX_CLIPBOARD_BYTES = 1_048_576
        const val APPEARANCE_LIGHT = 1
        const val APPEARANCE_DARK = 2
        const val APP_STATE_ACTIVE = 1
        const val APP_STATE_BACKGROUND = 3
    }
}
