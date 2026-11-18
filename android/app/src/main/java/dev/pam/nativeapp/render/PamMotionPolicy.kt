package dev.pam.nativeapp.render

import android.os.Build
import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings

internal object PamMotionPolicy {
    @Volatile
    internal var reduceMotionOverride: Boolean? = null

    fun isReduced(context: Context): Boolean {
        reduceMotionOverride?.let { return it }
        if (!animatorsEnabled(context)) return true
        return runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }

    /** [ValueAnimator.areAnimatorsEnabled] is API 26+; 7.x reads the duration scale setting. */
    fun animatorsEnabled(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ValueAnimator.areAnimatorsEnabled()
        } else {
            runCatching {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
            }.getOrDefault(true)
        }
}
