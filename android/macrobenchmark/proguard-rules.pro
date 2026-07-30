# Optional annotations and receivers referenced by the AndroidX benchmark
# harness are not required in the instrumentation process itself.
-keep class androidx.tracing.Trace { *; }
-dontwarn androidx.profileinstaller.ProfileInstallReceiver
-dontwarn androidx.startup.Initializer
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
