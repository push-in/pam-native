package dev.pam.nativeapp.modules

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import dev.pam.nativeapp.PamActivity
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.atomic.AtomicBoolean

internal class SystemModule(private val context: Context) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()

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

    private fun deviceInfo(completion: ModuleCompletion) {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val appearance = when (
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        ) {
            Configuration.UI_MODE_NIGHT_YES -> APPEARANCE_DARK
            else -> APPEARANCE_LIGHT
        }
        val appState = if ((context as? PamActivity)?.hasWindowFocus() == true) {
            APP_STATE_ACTIVE
        } else {
            APP_STATE_BACKGROUND
        }
        completion.complete(
            ModuleResultStatus.SUCCESS,
            WireMap.encode(
                mapOf(
                    "width" to WireValue.Decimal(metrics.widthPixels / density.toDouble()),
                    "height" to WireValue.Decimal(metrics.heightPixels / density.toDouble()),
                    "density" to WireValue.Decimal(density.toDouble()),
                    "appearance" to WireValue.Integer(appearance.toLong()),
                    "appState" to WireValue.Integer(appState.toLong()),
                ),
            ),
        )
    }

    private fun dismissKeyboard(completion: ModuleCompletion) {
        val activity = context as? PamActivity ?: error("Keyboard requires an active PamActivity")
        val input = context.getSystemService(InputMethodManager::class.java)
        input.hideSoftInputFromWindow(activity.currentFocus?.windowToken, 0)
        activity.currentFocus?.clearFocus()
        completion.success()
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
        main.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val APPEARANCE_LIGHT = 1
        const val APPEARANCE_DARK = 2
        const val APP_STATE_ACTIVE = 1
        const val APP_STATE_BACKGROUND = 3
    }
}
