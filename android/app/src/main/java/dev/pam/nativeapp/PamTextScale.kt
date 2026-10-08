package dev.pam.nativeapp

import android.content.Context
import kotlin.math.min

/**
 * Application text scale ("text size" setting inside the app), persisted
 * natively and applied to every text on top of the operating-system font
 * scale: `effective = multiplier * min(system, maxSystemScale)`.
 *
 * Like React Native stylesheets created at boot, the value a process starts
 * with stays in force until the next launch, so layout measurement and
 * rendering never disagree. [persist] stores a new value for the next start.
 */
object PamTextScale {
    const val MIN_MULTIPLIER = 0.5f
    const val MAX_MULTIPLIER = 3f

    internal const val PREFERENCES = "pam.text-scale"
    private const val KEY_MULTIPLIER = "multiplier"
    private const val KEY_MAX_SYSTEM = "maxSystemScale"

    /** (multiplier, maxSystemScale) in force for this process; 0 cap = uncapped. */
    @Volatile
    private var applied: Pair<Float, Float>? = null

    fun isValidMultiplier(value: Float): Boolean = value.isFinite() && value in MIN_MULTIPLIER..MAX_MULTIPLIER

    fun isValidCap(value: Float): Boolean = value.isFinite() && (value == 0f || value >= 1f)

    /** Values stored for the next start. */
    fun stored(context: Context): Pair<Float, Float> {
        val preferences = preferences(context)
        val multiplier = preferences.getFloat(KEY_MULTIPLIER, 1f).takeIf(::isValidMultiplier) ?: 1f
        val cap = preferences.getFloat(KEY_MAX_SYSTEM, 0f).takeIf(::isValidCap) ?: 0f
        return multiplier to cap
    }

    /** Values this process renders with (read once, on first use). */
    fun applied(context: Context): Pair<Float, Float> =
        applied ?: synchronized(this) { applied ?: stored(context).also { applied = it } }

    /** Synchronous so a process death right after the call keeps it. */
    fun persist(context: Context, multiplier: Float, maxSystemScale: Float): Boolean {
        require(isValidMultiplier(multiplier)) { "Text scale multiplier must be between $MIN_MULTIPLIER and $MAX_MULTIPLIER" }
        require(isValidCap(maxSystemScale)) { "maxSystemScale must be 0 (uncapped) or at least 1" }
        return preferences(context).edit()
            .putFloat(KEY_MULTIPLIER, multiplier)
            .putFloat(KEY_MAX_SYSTEM, maxSystemScale)
            .commit()
    }

    /** The scale text renders at for [systemScale] (the OS font scale). */
    fun effective(context: Context, systemScale: Float = context.resources.configuration.fontScale): Float {
        val (multiplier, cap) = applied(context)
        return combine(multiplier, cap, systemScale)
    }

    internal fun combine(multiplier: Float, cap: Float, systemScale: Float): Float {
        val system = if (systemScale.isFinite() && systemScale > 0f) systemScale else 1f
        return multiplier * (if (cap > 0f) min(system, cap) else system)
    }

    /** Tests and process restarts inside instrumentation. */
    internal fun resetApplied() {
        applied = null
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
