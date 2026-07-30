-keep class dev.pam.nativeapp.PamRuntime {
    public protected *;
}

-keepclassmembers class dev.pam.nativeapp.PamRuntime {
    boolean onNativeBatch(java.nio.ByteBuffer, long);
    void onNativeCall(long, java.lang.String, java.lang.String, byte[]);
    void onNativeCallTyped(long, int, byte[]);
    void onNativeError(java.lang.String);
}

-keepclasseswithmembernames class * {
    native <methods>;
}

# AndroidJUnitRunner enters the benchmark target process before application
# code and requires this class there.
-keep class androidx.tracing.Trace { *; }
