import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional defaults pulled from glasses/local.properties (never committed).
// Everything here can also be typed into the app's settings screen instead.
//     xai.api.key=xai-...
//     picovoice.access.key=...
//     meta.application.id=0        (0 = Developer Mode)
//     meta.client.token=           (only needed once your app is registered with Meta)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(key: String, default: String = "") = localProps.getProperty(key, default)

android {
    namespace = "com.jarvis.glasses"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.glasses"
        minSdk = 29 // Meta Wearables Device Access Toolkit requires Android 10+
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "DEFAULT_XAI_KEY", "\"${prop("xai.api.key")}\"")
        buildConfigField("String", "DEFAULT_PICOVOICE_KEY", "\"${prop("picovoice.access.key")}\"")
        manifestPlaceholders["metaApplicationId"] = prop("meta.application.id", "0")
        manifestPlaceholders["metaClientToken"] = prop("meta.client.token")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Meta Wearables Device Access Toolkit — glasses camera + buttons.
    implementation("com.meta.wearable:mwdat-core:1.0.0")
    implementation("com.meta.wearable:mwdat-camera:1.0.0")
    implementation("com.meta.wearable:mwdat-inputs:1.0.0")

    // "Jarvis" wake word (built-in keyword, runs fully on the phone).
    implementation("ai.picovoice:porcupine-android:4.0.2")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
