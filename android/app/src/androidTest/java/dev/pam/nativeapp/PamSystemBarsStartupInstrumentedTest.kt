package dev.pam.nativeapp

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** PhoneWindow must have a DecorView before its API 30+ controller is read. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class PamSystemBarsStartupInstrumentedTest {
    @Test
    fun systemBarsCanBeAppliedBeforeContentCreatesDecor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as Application
        var launched: PamActivity? = null
        var checkedBeforeDecor = false
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, state: Bundle?) {
                if (activity !is PamActivity) return
                launched = activity
                checkedBeforeDecor = activity.window.peekDecorView() == null
                // Runs before PamActivity.onCreate/setContentView, reproducing
                // the uninitialized PhoneWindow seen during Android 36 relaunch.
                activity.applyDefaultSystemBars()
                assertNotNull(activity.window.peekDecorView())
                assertNotNull(activity.window.insetsController)
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callback)
        try {
            instrumentation.startActivitySync(
                Intent(context, PamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            assertTrue("must exercise the window before DecorView creation", checkedBeforeDecor)
        } finally {
            app.unregisterActivityLifecycleCallbacks(callback)
            launched?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
        }
    }
}
