# Optional annotations and receivers referenced by the AndroidX benchmark
# harness are not required in the instrumentation process itself.
-keep class androidx.tracing.Trace { *; }
# The AndroidX instrumentation bootstrap resolves Kotlin helpers through code
# paths R8 cannot see. Keep the runtime in the minified benchmark APK.
-keep class kotlin.** { *; }
-dontwarn androidx.profileinstaller.ProfileInstallReceiver
-dontwarn androidx.startup.Initializer
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
