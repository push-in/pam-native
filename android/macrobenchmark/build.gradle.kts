import java.util.Properties

plugins {
    id("com.android.test")
}

val pamProperties = Properties().apply {
    rootProject.file("pam-native.properties").inputStream().use(::load)
}
val pamApplicationId = pamProperties.getProperty("applicationId", "dev.pam.nativeapp")

android {
    namespace = "dev.pam.nativeapp.benchmark"
    compileSdk = 36
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TARGET_PACKAGE", "\"$pamApplicationId\"")
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = false
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("benchmark", "release")
            testProguardFiles(file("proguard-rules.pro"))
        }
    }

    buildFeatures {
        buildConfig = true
    }

}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.0")
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark"
    }
}
