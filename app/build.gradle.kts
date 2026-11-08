plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mangabubble"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mangabubble"
        minSdk = 23          // Android 6
        targetSdk = 35       // berjalan juga di Android 16/17 lewat kompatibilitas
        versionCode = 1
        versionName = "1.0"
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
