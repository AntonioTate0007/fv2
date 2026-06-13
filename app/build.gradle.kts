import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Pull backend URL + bearer token from local.properties so secrets don't get
// committed to git. Both fall back to safe defaults that put the app in mock mode.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val apiBaseUrl: String = localProps.getProperty("api.base.url", "https://fortress-api.example.com/")
val apiAuthToken: String = localProps.getProperty("api.auth.token", "")

// Firebase auto-wiring: drop google-services.json into /app and the plugin lights up
// on next build — no manifest or gradle changes needed.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    println("[fortress] google-services.json detected — Firebase plugin enabled")
} else {
    println("[fortress] no google-services.json — building without Firebase plugin")
}

android {
    namespace = "com.fortress.app"
    compileSdk = 35

    defaultConfig {
        // Distinct id so the app installs cleanly alongside any older
        // com.fortress.app build (avoids debug-signature install conflicts).
        applicationId = "com.autopilot.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "8.0"

        // Backend URL + bearer come from local.properties (kept out of git).
        // To go live: drop these two lines into local.properties:
        //     api.base.url=https://your-render-app.onrender.com/
        //     api.auth.token=<paste FORTRESS_API_TOKEN value from Render env>
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "API_AUTH_TOKEN", "\"$apiAuthToken\"")
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
    implementation(libs.androidx.fragment.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.biometric)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.generativeai)
    implementation(libs.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
}
