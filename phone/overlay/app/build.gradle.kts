plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fortress.jarvis.overlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fortress.jarvis.overlay"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
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
}

// Plain Views + Canvas keep the APK tiny (no Compose, no AppCompat). androidx.core
// is only here for FileProvider, which the setup wizard needs to hand downloaded
// APKs to the system installer.
dependencies {
    implementation("androidx.core:core:1.15.0")
}
