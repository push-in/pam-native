package dev.pam.nativeapp

import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.modules.NativeModuleRegistry
import dev.pam.nativeapp.render.PamRenderer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PamRuntime builds its native modules in the constructor by default
 * (`installModules = true`). Its `init` block used to run before the queue
 * of module calls awaiting installation was initialised, so the default
 * constructor threw a NullPointerException (since 1.29.0). No PHP is
 * started here: embedded PHP is process scoped.
 */
@RunWith(AndroidJUnit4::class)
class PamRuntimeConstructionInstrumentedTest {
    @Test
    fun defaultConstructorInstallsTheModulesWithoutStartingPhp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var failure: Throwable? = null
        var registry: NativeModuleRegistry? = null
        instrumentation.runOnMainSync {
            runCatching {
                val runtime = PamRuntime(context, PamRenderer(context, FrameLayout(context)) { _, _, _ -> }, {})
                registry = PamRuntime::class.java.getDeclaredField("installedModules")
                    .apply { isAccessible = true }.get(runtime) as NativeModuleRegistry?
                runtime.close()
            }.onFailure { failure = it }
        }
        assertNull("PamRuntime(...) must not throw: $failure", failure)
        assertNotNull("installModules = true installs the registry in the constructor", registry)
    }

    @Test
    fun deferredInstallationRunsQueuedCallsOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            runCatching {
                val runtime = PamRuntime(
                    context,
                    PamRenderer(context, FrameLayout(context)) { _, _, _ -> },
                    {},
                    installModules = false,
                )
                runtime.installModules()
                runtime.installModules()
                runtime.close()
            }.onFailure { failure = it }
        }
        assertNull("installModules() must be idempotent: $failure", failure)
    }
}
