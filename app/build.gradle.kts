plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Di GitHub Actions nomor run dipakai sebagai nomor versi (sama dengan tag rilis v1.0.N).
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0

android {
    namespace = "com.mangabubble"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mangabubble"
        minSdk = 23          // Android 6
        targetSdk = 35       // berjalan juga di Android 16/17 lewat kompatibilitas
        versionCode = maxOf(runNumber, 1)
        versionName = "1.0.$runNumber"
    }

    // Kunci debug tetap, supaya APK dari tiap build bisa menimpa versi lama tanpa uninstall.
    // (Kunci ini sengaja publik dan hanya untuk pemakaian pribadi.)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // OCR (model ikut di dalam APK, jalan offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")            // Latin / Inggris
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    // Terjemahan on-device (model diunduh sekali, sesudah itu offline)
    implementation("com.google.mlkit:translate:17.0.3")
}
