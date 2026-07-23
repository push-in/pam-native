import java.util.Properties

plugins {
    id("com.android.application")
}

val pamProperties = Properties().apply {
    rootProject.file("pam-native.properties").inputStream().use { input ->
        load(input)
    }
}
val pamApplicationId = pamProperties.getProperty("applicationId", "dev.pam.nativeapp")
val pamApplicationName = pamProperties.getProperty("applicationName", "Pam Native")
val pamNativeHome = pamProperties.getProperty("nativeHome")
    ?: error("pam-native.properties must define nativeHome")
val pamMinSdk = pamProperties.getProperty("minSdk", "26").toInt()
val pamTargetSdk = pamProperties.getProperty("targetSdk", "36").toInt()
val pamVersionCode = pamProperties.getProperty("versionCode", "1").toInt()
val pamVersionName = pamProperties.getProperty("versionName", "0.1.0")
val pamAbis = pamProperties.getProperty("abis", "arm64-v8a,x86_64")
    .split(',')
    .filter(String::isNotBlank)

android {
    namespace = "dev.pam.nativeapp"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = pamApplicationId
        minSdk = pamMinSdk
        targetSdk = pamTargetSdk
        versionCode = pamVersionCode
        versionName = pamVersionName
        manifestPlaceholders["pamApplicationName"] = pamApplicationName

        externalNativeBuild {
            cmake {
                arguments += "-DPAM_NATIVE_ROOT=$pamNativeHome"
                cppFlags += listOf(
                    "-std=c++20",
                    "-fexceptions",
                    "-frtti",
                    "-fvisibility=hidden",
                )
            }
        }

        ndk {
            abiFilters += pamAbis
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isJniDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").kotlin.directories.add(
            pamProperties.getProperty("projectRoot") + "/android/src/main/kotlin",
        )
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        disable += setOf(
            "AndroidGradlePluginVersion",
            "ChromeOsAbiSupport",
            "GradleDependency",
        )
    }
}

dependencies {
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
