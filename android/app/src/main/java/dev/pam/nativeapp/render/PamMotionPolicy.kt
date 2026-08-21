package dev.pam.nativeapp.render

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings

internal object PamMotionPolicy {
    @Volatile
    internal var reduceMotionOverride: Boolean? = null

    fun isReduced(context: Context): Boolean {
        reduceMotionOverride?.let { return it }
        if (!ValueAnimator.areAnimatorsEnabled()) return true
        return runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}
