package dev.pam.nativeapp

import android.app.Activity
import android.content.Context
import android.content.Intent

/** Public brownfield contract exposed by the small PAM Native Android AAR. */
object PamNativeLauncher {
    private const val ACTIVITY_CLASS = "dev.pam.nativeapp.PamActivity"

    /** Returns true only when the host application packaged the PAM Native runtime activity. */
    @JvmStatic
    fun isAvailable(context: Context): Boolean = runCatching {
        Class.forName(ACTIVITY_CLASS, false, context.classLoader)
    }.isSuccess

    /** Creates an explicit, non-export-dependent intent into the packaged PAM runtime. */
    @JvmStatic
    fun intent(context: Context): Intent {
        check(isAvailable(context)) {
            "PAM Native runtime is not packaged. Add pushinbr/pam-native Android artifacts first."
        }
        return Intent().setClassName(context.packageName, ACTIVITY_CLASS).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    @JvmStatic
    fun launch(context: Context) {
        context.startActivity(intent(context))
    }
}
