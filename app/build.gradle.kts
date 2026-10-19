plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ua.vatsuo.chytalka"
    compileSdk = 34
    defaultConfig {
        applicationId = "ua.vatsuo.chytalka"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" } // Dimensity 1200 = arm64
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    androidResources { noCompress += listOf("onnx") }
    // sherpa-onnx AAR (ORT 1.28.2) and onnxruntime-android both ship libonnxruntime.so; the C API version is 28 for all 1.28.x
    packaging { jniLibs { pickFirsts += "**/libonnxruntime.so" } }
}

dependencies {
    implementation(fileTree("libs") { include("*.aar") }) // sherpa-onnx (run setup.ps1)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0") // StyleTTS2 benchmark (Java API)
}
