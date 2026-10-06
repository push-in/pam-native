-keep class dev.pam.nativeapp.PamRuntime {
    public protected *;
}

-keepclassmembers class dev.pam.nativeapp.PamRuntime {
    boolean onNativeBatch(java.nio.ByteBuffer, long);
    void onNativeCall(long, java.lang.String, java.lang.String, byte[]);
    void onNativeCallTyped(long, int, byte[]);
    void onNativeError(java.lang.String);
    void onNativeRequestReleased(java.lang.String);
    # Engine text measurement (JNI GetMethodID in pam_android_bridge.cpp).
    boolean onMeasureText(long, byte[], byte[], java.lang.String, java.lang.String, float, float, float, float, float, int, boolean, boolean, int, int, int, int, float[]);
    # PHP pam_native_crypto() (Pam\Native\Crypto).
    byte[] onNativeCrypto(int, byte[], byte[], byte[], byte[]);
}

-keepclasseswithmembernames class * {
    native <methods>;
}

# AndroidJUnitRunner enters the benchmark target process before application
# code and requires this class there.
-keep class androidx.tracing.Trace { *; }
