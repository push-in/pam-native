package dev.pam.nativeapp.modules

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
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
import java.util.TimeZone

internal class SystemModule(private val context: Context) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    /** Choice dialogs still on screen; dismissed (answering -1) when the module closes. Main thread only. */
    private val openDialogs = mutableSetOf<AlertDialog>()

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
                NativeOperation.SENSOR_READ -> completion.complete(
                    ModuleResultStatus.FAILURE,
                    "Sensors moved to the pushinbr/pam-native-sensors plugin".toByteArray(),
                )
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
            (context as? Activity)?.let(::invokeDefaultBack)
            completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
        }
    }

    /**
     * React Native's `invokeDefaultOnBackPressed()`: the platform's own Back.
     * Since Android 12 a root launcher Activity is moved to the background
     * instead of being finished, so returning to the app is a warm resume;
     * older releases finish it (the live runtime re-attaches on relaunch).
     */
    private fun invokeDefaultBack(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && activity.isTaskRoot) {
            if (activity.moveTaskToBack(false)) return
        }
        activity.finish()
    }

    private fun alert(payload: ByteArray, completion: ModuleCompletion) {
        val activity = context as? PamActivity ?: error("Alert requires an active PamActivity")
        val values = WireMap.decode(payload)
        val title = values.text("title")
        val message = values.text("message")
        val buttons = PamAlertButtons.decode(values)
        if (buttons.isNotEmpty()) {
            main.post { choose(activity, title, message, buttons, completion) }
            return
        }
        main.post {
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
                .setOnDismissListener { completion.success() }
                .show()
        }
    }

    /** `Alert::choose()`: reports the pressed button index, or -1 when dismissed otherwise. */
    private fun choose(
        activity: PamActivity,
        title: String,
        message: String,
        buttons: List<PamAlertButton>,
        completion: ModuleCompletion,
    ) {
        if (closed.get() || activity.isFinishing || activity.isDestroyed) {
            completion.complete(ModuleResultStatus.FAILURE, "Alert requires an active PamActivity".toByteArray())
            return
        }
        var pressed = PamAlertButtons.DISMISSED
        val slots = PamAlertButtons.slots(buttons)
        val builder = AlertDialog.Builder(activity).setTitle(title).setMessage(message)
        slots.positive?.let { index -> builder.setPositiveButton(buttons[index].label) { _, _ -> pressed = index } }
        slots.negative?.let { index -> builder.setNegativeButton(buttons[index].label) { _, _ -> pressed = index } }
        slots.neutral?.let { index -> builder.setNeutralButton(buttons[index].label) { _, _ -> pressed = index } }
        val cancelIndex = buttons.indexOfFirst { it.style == PamAlertButtons.STYLE_CANCEL }
        builder.setCancelable(true)
        builder.setOnCancelListener { if (cancelIndex >= 0) pressed = cancelIndex }
        val dialog = builder.create()
        dialog.setOnDismissListener {
            openDialogs.remove(dialog)
            completion.complete(
                ModuleResultStatus.SUCCESS,
                WireMap.encode(mapOf("index" to WireValue.Integer(pressed.toLong()))),
            )
        }
        openDialogs += dialog
        dialog.show()
        listOf(
            slots.positive to AlertDialog.BUTTON_POSITIVE,
            slots.negative to AlertDialog.BUTTON_NEGATIVE,
            slots.neutral to AlertDialog.BUTTON_NEUTRAL,
        ).forEach { (index, which) ->
            if (index != null && buttons[index].style == PamAlertButtons.STYLE_DESTRUCTIVE) {
                dialog.getButton(which)?.setTextColor(DESTRUCTIVE_COLOR)
            }
        }
    }

    private fun toast(payload: ByteArray, completion: ModuleCompletion) {
        val values = WireMap.decode(payload)
        val message = values.text("message")
        val activity = context as? Activity
        if (values.containsKey("title") && activity != null) {
            fun color(key: String, fallback: Long): Int =
                ((values[key] as? WireValue.Integer)?.value ?: fallback).toInt()
            fun number(key: String, fallback: Double): Double =
                (values[key] as? WireValue.Decimal)?.value ?: (values[key] as? WireValue.Integer)?.value?.toDouble() ?: fallback
            val spec = PamInAppToastSpec(
                title = values.text("title"),
                message = message,
                accentColor = color("accentColor", 0xFF87CEFA),
                backgroundColor = color("backgroundColor", 0xFFFFFFFF),
                titleColor = color("titleColor", 0xFF000000),
                messageColor = color("messageColor", 0xFF979797),
                bottom = values.flag("bottom", false),
                offsetDp = number("offset", 40.0).toFloat(),
                durationMs = number("durationMs", 4_000.0).toLong(),
                titleSize = number("titleSize", 12.0).toFloat(),
                messageSize = number("messageSize", 10.0).toFloat(),
                fontFamily = values.textOrNull("fontFamily"),
                borderColor = (values["borderColor"] as? WireValue.Integer)?.value?.toInt(),
                borderWidthDp = number("borderWidth", 0.0).toFloat(),
                cornerRadiusDp = number("cornerRadius", 6.0).toFloat(),
                accentWidthDp = number("accentWidth", 5.0).toFloat(),
                elevationDp = number("elevation", 2.0).toFloat(),
                paddingHorizontalDp = number("paddingHorizontal", 15.0).toFloat(),
                paddingVerticalDp = number("paddingVertical", 10.0).toFloat(),
                minHeightDp = number("minHeight", 60.0).toFloat(),
                titleLines = number("titleLines", 1.0).toInt().coerceIn(1, 4),
                fitContent = values.flag("fitContent", false),
                iconSource = values.textOrNull("iconSource"),
                iconGlyph = values.textOrNull("iconGlyph"),
                iconFontFamily = values.textOrNull("iconFontFamily"),
                iconColor = (values["iconColor"] as? WireValue.Integer)?.value?.toInt(),
                iconSizeDp = number("iconSize", 18.0).toFloat(),
            )
            val typefaces = dev.pam.nativeapp.render.NativeTypefaceLoader.shared(activity)
            main.post {
                PamInAppToast.show(activity, spec) { family, weight -> typefaces.resolve(family, weight, false) }
                completion.success()
            }
            return
        }
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
                val appearance = if (dev.pam.nativeapp.PamAppearance.isDark(context)) {
                    APPEARANCE_DARK
                } else {
                    APPEARANCE_LIGHT
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
                        "timeZone" to WireValue.Text(TimeZone.getDefault().id),
                        "memoryClassMb" to WireValue.Integer(
                            (context.getSystemService(android.app.ActivityManager::class.java)?.memoryClass ?: 0).toLong(),
                        ),
                        "lowRamDevice" to WireValue.Flag(
                            context.getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice == true,
                        ),
                        "powerSaveMode" to WireValue.Flag(
                            context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true,
                        ),
                        "appVersion" to WireValue.Text(installedAppVersion()),
                        "buildNumber" to WireValue.Text(installedBuildNumber()),
                        "locale" to WireValue.Text(java.util.Locale.getDefault().toLanguageTag()),
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
        val values = WireMap.decode(payload)
        val text = values.text("text")
        val sensitive = values.flag("sensitive", false)
        val clearAfterSeconds = values.integer(
            "clearAfterSeconds",
            ClipboardPrivacyPolicy.defaultExpirySeconds,
        )
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_CLIPBOARD_BYTES) {
            "Clipboard text exceeds one megabyte"
        }
        val clearAfterMillis = if (sensitive) ClipboardPrivacyPolicy.expiryMillis(clearAfterSeconds) else 0L
        main.post {
            val clip = ClipData.newPlainText("", text)
            if (sensitive) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipboardPrivacyPolicy.sensitiveExtra, true)
                }
            }
            clipboard().setPrimaryClip(clip)
            if (sensitive) scheduleClipboardClear(text, clearAfterMillis)
            completion.success()
        }
    }

    private fun scheduleClipboardClear(expectedText: String, delayMillis: Long) {
        main.postDelayed({
            if (closed.get()) return@postDelayed
            val manager = clipboard()
            val current = manager.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString()
            if (!ClipboardPrivacyPolicy.shouldClear(expectedText, current)) return@postDelayed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.clearPrimaryClip()
            else manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }, delayMillis)
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
        main.post {
            openDialogs.toList().forEach { runCatching { it.dismiss() } }
            openDialogs.clear()
        }
    }

    private companion object {
        const val MAX_CLIPBOARD_BYTES = 1_048_576
        const val APPEARANCE_LIGHT = 1
        const val APPEARANCE_DARK = 2
        const val APP_STATE_ACTIVE = 1
        const val APP_STATE_BACKGROUND = 3
        const val DESTRUCTIVE_COLOR = 0xFFD32F2F.toInt()
    }

    /** versionName of the installed package ('' when the package manager has no answer). */
    private fun installedAppVersion(): String = runCatching {
        installedPackage()?.versionName.orEmpty()
    }.getOrDefault("")

    /** versionCode of the installed package as text ('' when unknown). */
    private fun installedBuildNumber(): String = runCatching {
        val info = installedPackage() ?: return@runCatching ""
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toString()
        }
    }.getOrDefault("")

    private fun installedPackage(): android.content.pm.PackageInfo? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
}
