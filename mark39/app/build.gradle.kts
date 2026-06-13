import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Optional: bake default API keys from local.properties so a local build can ship
// with them pre-filled. Both default to empty — the app prompts for keys in Settings
// and stores them in DataStore, so committing nothing keeps secrets out of git.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val defaultGeminiKey: String = localProps.getProperty("gemini.api.key", "")
val defaultOpenRouterKey: String = localProps.getProperty("openrouter.api.key", "")

android {
    namespace = "com.mark39.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mark39.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "39.0"

        buildConfigField("String", "DEFAULT_GEMINI_KEY", "\"$defaultGeminiKey\"")
        buildConfigField("String", "DEFAULT_OPENROUTER_KEY", "\"$defaultOpenRouterKey\"")
        vectorDrawables { useSupportLibrary = true }
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

    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.generativeai)
}
