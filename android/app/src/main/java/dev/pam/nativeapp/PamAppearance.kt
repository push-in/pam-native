package dev.pam.nativeapp

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.system.Os

/**
 * Persisted light/dark preference shared by the activity, the `appearance`
 * native module and the PHP boot environment.
 *
 * The preference is read before the activity attaches its resources so the
 * DayNight theme, window background, system bars and the PHP boot metrics all
 * describe the same scheme before the first frame. Android 12+ also receives
 * the preference through [UiModeManager.setApplicationNightMode], which makes
 * the system splash screen follow it across process restarts.
 */
object PamAppearance {
    const val MODE_SYSTEM = 1
    const val MODE_LIGHT = 2
    const val MODE_DARK = 3
    const val APPEARANCE_UNKNOWN = 0
    const val APPEARANCE_LIGHT = 1
    const val APPEARANCE_DARK = 2

    internal const val PREFERENCES = "pam.appearance"
    internal const val KEY_MODE = "mode"
    private const val KEY_PLATFORM_MODE = "platformMode"

    fun isValidMode(mode: Int): Boolean = mode in MODE_SYSTEM..MODE_DARK

    /** The persisted preference, or the app's configured default. */
    fun storedMode(context: Context): Int {
        val stored = preferences(context).getInt(KEY_MODE, 0)
        if (isValidMode(stored)) return stored
        val configured = runCatching {
            context.resources.getInteger(R.integer.pam_appearance_default_mode)
        }.getOrDefault(MODE_SYSTEM)
        return if (isValidMode(configured)) configured else MODE_SYSTEM
    }

    /** Persists synchronously so a process death right after the call keeps it. */
    fun persist(context: Context, mode: Int): Boolean {
        require(isValidMode(mode)) { "Unknown appearance mode $mode" }
        return preferences(context).edit().putInt(KEY_MODE, mode).commit()
    }

    /**
     * The operating-system scheme. Android 12+ reports an explicit system
     * Yes/No setting through [UiModeManager] even while a per-app override is
     * active; otherwise the application configuration is used, which below
     * Android 12 is never affected by the activity override configuration.
     */
    fun systemDark(context: Context): Boolean =
        platformSystemNight(context) ?: isNight(context.applicationContext.resources.configuration)

    fun isDark(context: Context, mode: Int = storedMode(context)): Boolean = when (mode) {
        MODE_LIGHT -> false
        MODE_DARK -> true
        else -> systemDark(context)
    }

    /**
     * Reports the system scheme to PHP. While an Android 12+ per-app override
     * is active and the system uses a scheduled dark theme, the process
     * configuration carries the override, so the system scheme is reported as
     * unknown and PHP waits for the host to resolve it.
     */
    fun systemAppearance(context: Context, mode: Int = storedMode(context)): Int {
        if (
            mode != MODE_SYSTEM &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            platformSystemNight(context) == null
        ) {
            return APPEARANCE_UNKNOWN
        }
        return if (systemDark(context)) APPEARANCE_DARK else APPEARANCE_LIGHT
    }

    private fun platformSystemNight(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val manager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return when (manager?.nightMode) {
            UiModeManager.MODE_NIGHT_YES -> true
            UiModeManager.MODE_NIGHT_NO -> false
            else -> null
        }
    }

    /**
     * Below Android 12 the activity applies the preference as an override
     * configuration before its resources and theme are created. Android 12+
     * receives the persisted per-app night mode from the system instead.
     */
    fun overrideConfiguration(base: Context, mode: Int = storedMode(base)): Configuration? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || mode == MODE_SYSTEM) return null
        val night = if (mode == MODE_DARK) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        return Configuration().apply {
            uiMode = (base.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                night
        }
    }

    /**
     * Synchronises Android 12+ per-app night mode. The handled `uiMode`
     * configuration change restyles the running activity without recreation.
     */
    fun applyPlatformNightMode(context: Context, mode: Int = storedMode(context)) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val preferences = preferences(context)
        if (preferences.getInt(KEY_PLATFORM_MODE, 0) == mode) return
        val manager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return
        runCatching {
            manager.setApplicationNightMode(
                when (mode) {
                    MODE_LIGHT -> UiModeManager.MODE_NIGHT_NO
                    MODE_DARK -> UiModeManager.MODE_NIGHT_YES
                    else -> UiModeManager.MODE_NIGHT_AUTO
                },
            )
        }.onSuccess {
            preferences.edit().putInt(KEY_PLATFORM_MODE, mode).apply()
        }
    }

    /** Resolves a configured colour for an explicit scheme, independent of the current one. */
    fun color(context: Context, resource: Int, dark: Boolean): Int =
        schemeContext(context, dark).getColor(resource)

    fun bool(context: Context, resource: Int, dark: Boolean): Boolean =
        schemeContext(context, dark).resources.getBoolean(resource)

    /**
     * Exports the boot appearance to the embedded PHP process before it starts
     * and after every change, so PHP reads it synchronously.
     */
    fun exportEnvironment(context: Context, mode: Int = storedMode(context)) {
        runCatching {
            Os.setenv("PAM_APPEARANCE_MODE", mode.toString(), true)
            Os.setenv("PAM_SYSTEM_APPEARANCE", systemAppearance(context, mode).toString(), true)
            Os.setenv("PAM_SYSTEM_DARK", if (isDark(context, mode)) "1" else "0", true)
        }
    }

    private fun schemeContext(context: Context, dark: Boolean): Context {
        val current = context.resources.configuration
        if (isNight(current) == dark) return context
        val configuration = Configuration(current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return context.createConfigurationContext(configuration)
    }

    private fun isNight(configuration: Configuration): Boolean =
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
