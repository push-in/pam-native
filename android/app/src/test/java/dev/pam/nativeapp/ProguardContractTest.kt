package dev.pam.nativeapp

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** JNI callbacks resolved with GetMethodID must survive R8 in release builds. */
class ProguardContractTest {
    @Test
    fun jniCallbacksAreKeptForMinifiedBuilds() {
        val rules = File("proguard-rules.pro").readText()
        val bridge = File("src/main/cpp/pam_android_bridge.cpp").readText()
        for (callback in listOf("onNativeBatch", "onNativeCall", "onNativeCallTyped", "onNativeError", "onMeasureText")) {
            assertTrue("$callback is looked up from JNI", bridge.contains("\"$callback\""))
            assertTrue("$callback must be kept by proguard-rules.pro", Regex("\\b$callback\\(").containsMatchIn(rules))
        }
    }
}
