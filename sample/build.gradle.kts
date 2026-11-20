import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Session replay sample (VEL-2139). Not published: no maven-publish.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val sampleToken = (findProperty("cohorlySampleToken") as String?).orEmpty()
val sampleApiHost = (findProperty("cohorlySampleApiHost") as String?) ?: "http://10.0.2.2:4000"
// WIREFRAME (default) or SCREENSHOT: ./gradlew :sample:installDebug -PcohorlySampleCaptureMode=SCREENSHOT
val sampleCaptureMode = ((findProperty("cohorlySampleCaptureMode") as String?) ?: "WIREFRAME").uppercase().also {
    require(it == "WIREFRAME" || it == "SCREENSHOT") {
        "cohorlySampleCaptureMode must be WIREFRAME or SCREENSHOT, not $it"
    }
}

// Session replay rates and crash reporting (VEL-2151). Error mode needs a session not drawn for
// full recording: -PcohorlySampleRate=0 records every session in error mode, which sends its
// buffer on a crash (the "Crash" button) from the launch after the first one.
val sampleRate = (findProperty("cohorlySampleRate") as String?)?.toDoubleOrNull() ?: 1.0
val sampleOnErrorRate = (findProperty("cohorlySampleOnErrorRate") as String?)?.toDoubleOrNull() ?: 1.0
val sampleCrashReporting = (findProperty("cohorlySampleCrashReporting") as String?)?.toBoolean() ?: true

android {
    namespace = "com.cohorly.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cohorly.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "COHORLY_API_HOST", "\"$sampleApiHost\"")
        buildConfigField("String", "COHORLY_TOKEN", "\"$sampleToken\"")
        buildConfigField("double", "REPLAY_SAMPLE_RATE", "$sampleRate")
        buildConfigField("double", "REPLAY_ON_ERROR_SAMPLE_RATE", "$sampleOnErrorRate")
        buildConfigField("boolean", "NATIVE_CRASH_REPORTING", "$sampleCrashReporting")
        buildConfigField("String", "COHORLY_CAPTURE_MODE", "\"$sampleCaptureMode\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":cohorly"))
    implementation(project(":cohorly-replay"))
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
}
