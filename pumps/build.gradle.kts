import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Backend URL lives in local.properties so it's not baked into the repo.
// Falls back to a placeholder; without it the app boots in mock-data mode
// (the repository returns a canned list).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val backendUrl: String = localProps.getProperty("pumps.backend.url", "https://pump-finder.example.com/")

// Firebase auto-wiring — drop google-services.json into /pumps and the plugin
// kicks in on next build. Without it the app still builds; FCM just no-ops.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    println("[pumps] google-services.json detected — Firebase plugin enabled")
} else {
    println("[pumps] no google-services.json — building without Firebase plugin")
}

android {
    namespace = "com.pumpfinder.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pumpfinder.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // To go live:
        //   pumps.backend.url=https://pump-finder.onrender.com/
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.datastore.preferences)
}
