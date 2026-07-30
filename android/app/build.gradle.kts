import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
}

abstract class SyncGoogleServicesTask : DefaultTask() {
    @get:InputFile
    abstract val sourceFile: RegularFileProperty

    @get:OutputFile
    abstract val targetFile: RegularFileProperty

    @TaskAction
    fun sync() {
        sourceFile.get().asFile.copyTo(targetFile.get().asFile, overwrite = true)
    }
}

val pamProperties = Properties().apply {
    rootProject.file("pam-native.properties").inputStream().use { input ->
        load(input)
    }
}
val pamPluginProperties = Properties().apply {
    val pluginFile = rootProject.file("pam-plugins.properties")
    if (pluginFile.isFile) {
        pluginFile.inputStream().use { input ->
            load(input)
        }
    }
}
val pamApplicationId = pamProperties.getProperty("applicationId", "dev.pam.nativeapp")
val pamApplicationName = pamProperties.getProperty("applicationName", "Pam Native")
val pamNativeHome = providers.gradleProperty("pamNativeRoot")
    .orElse(providers.environmentVariable("PAM_NATIVE_ROOT"))
    .orElse(pamProperties.getProperty("nativeHome"))
    .get()
val pamRuntimeHome = pamProperties.getProperty(
    "runtimeHome",
    "$pamNativeHome/runtime/android",
)
val pamMinSdk = pamProperties.getProperty("minSdk", "26").toInt()
val pamTargetSdk = pamProperties.getProperty("targetSdk", "36").toInt()
val pamVersionCode = pamProperties.getProperty("versionCode", "1").toInt()
val pamVersionName = pamProperties.getProperty("versionName", "0.2.1")
val pamAbis = pamProperties.getProperty("abis", "arm64-v8a,x86_64")
    .split(',')
    .filter(String::isNotBlank)
val pamProjectRoot = pamProperties.getProperty("projectRoot", "")
val pamGoogleServicesSource = listOf(
    file("$pamProjectRoot/.pam/google-services.json"),
    file("$pamProjectRoot/google-services.json"),
).firstOrNull(File::isFile) ?: file("$pamProjectRoot/.pam/google-services.json")
val pamFirebaseMessagingEnabled = pamProjectRoot.isNotBlank()
    && pamGoogleServicesSource.isFile

if (pamFirebaseMessagingEnabled) {
    apply(plugin = "com.google.gms.google-services")
    val pamGoogleServicesTarget = file("google-services.json")
    val syncPamGoogleServices = tasks.register<SyncGoogleServicesTask>("syncPamGoogleServices") {
        sourceFile.fileValue(pamGoogleServicesSource)
        targetFile.fileValue(pamGoogleServicesTarget)
    }
    tasks.matching {
        it.name.startsWith("process") && it.name.endsWith("GoogleServices")
    }.configureEach {
        dependsOn(syncPamGoogleServices)
    }
}

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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["pamApplicationName"] = pamApplicationName
        manifestPlaceholders["pamFirebaseMessagingEnabled"] =
            pamFirebaseMessagingEnabled.toString()
        manifestPlaceholders["pamFirebaseMessagingService"] =
            if (pamFirebaseMessagingEnabled) {
                "dev.pam.nativeapp.modules.PamFirebaseMessagingService"
            } else {
                "dev.pam.nativeapp.modules.PamDisabledFirebaseMessagingService"
            }

        externalNativeBuild {
            cmake {
                arguments += "-DPAM_NATIVE_ROOT=$pamNativeHome"
                arguments += "-DPAM_PHP_RUNTIME_ROOT=$pamRuntimeHome"
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
            if (!pamFirebaseMessagingEnabled) {
                applicationIdSuffix = ".debug"
            }
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
        if (pamFirebaseMessagingEnabled) {
            getByName("main").kotlin.directories.add("src/firebase/kotlin")
        }
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
    implementation(project(":plugin-api"))
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    if (pamFirebaseMessagingEnabled) {
        implementation("com.google.firebase:firebase-messaging:25.1.1")
    }
    add("benchmarkImplementation", "androidx.tracing:tracing:1.1.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    val pluginCount = pamPluginProperties.getProperty("plugin.count", "0").toInt()
    repeat(pluginCount) { index ->
        val module = pamPluginProperties.getProperty("plugin.$index.module")
            ?: error("pam-plugins.properties is missing plugin.$index.module")
        add("implementation", project(module))
    }
}
