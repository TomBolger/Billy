plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tombo.billyassistant.companion"
    compileSdk = 36

    signingConfigs {
        // Official builds are signed by GitHub Actions with the key stored in the
        // BILLY_KEYSTORE_B64 / BILLY_KEYSTORE_PASSWORD secrets (never in git).
        // Local builds and forks fall back to Android's standard debug key.
        getByName("debug") {
            val keystore = System.getenv("BILLY_KEYSTORE")?.let { file(it) }
            if (keystore != null && keystore.exists()) {
                storeFile = keystore
                storeType = "pkcs12"
                storePassword = System.getenv("BILLY_KEYSTORE_PASSWORD")
                keyAlias = "billy"
                keyPassword = System.getenv("BILLY_KEYSTORE_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.tombo.billyassistant.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = 81
        versionName = "0.7"
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
    testImplementation("junit:junit:4.13.2")
    implementation("io.rebble.pebblekit2:client:1.2.0")
    implementation("androidx.activity:activity-ktx:1.12.1")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
