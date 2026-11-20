import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Only the tests write @Composable code; the main source set calls no composable.
    id("org.jetbrains.kotlin.plugin.compose")
    `maven-publish`
}

val composeBom = "2026.06.01"

android {
    namespace = "com.cohorly.android.replay"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("com.cohorly.android.core.replay.CohorlyReplayInternal")
    }
}

dependencies {
    api(project(":cohorly"))
    // Window discovery and touch interception (Square, Apache 2.0, pure Kotlin, no native code).
    implementation("com.squareup.curtains:curtains:1.2.5")
    // Jetpack Compose is read only when the app has it (checked at runtime); 1.0.0 is the oldest supported.
    compileOnly("androidx.compose.ui:ui:1.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    // Pinned: the screenshot mask reads Compose internals by reflection (ComposeImageMask.kt),
    // and a test fails when this version moves them.
    testImplementation(platform("androidx.compose:compose-bom:$composeBom"))
    testImplementation("androidx.compose.ui:ui")
    testImplementation("androidx.compose.foundation:foundation")
    testImplementation("androidx.compose.material:material")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "cohorly-android-replay"
            }
        }
    }
}
