plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tombo.billyassistant.companion"
    compileSdk = 36

    signingConfigs {
        getByName("debug") {
            val projectDebugKeystore = rootProject.file("debug.keystore")
            if (projectDebugKeystore.exists()) {
                storeFile = projectDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "com.tombo.billyassistant.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = 55
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("io.rebble.pebblekit2:client:1.2.0")
    implementation("androidx.activity:activity-ktx:1.12.1")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
